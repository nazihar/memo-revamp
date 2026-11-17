package com.memo.imports.internal.dryrun

import com.memo.common.authz.Clearances
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantDsl
import com.memo.common.tenant.TenantTransactions
import com.memo.imports.internal.ImportBatchNotFoundException
import com.memo.imports.internal.ImportBatchStore
import com.memo.imports.internal.ImportMappingStore
import com.memo.imports.internal.IntakeState
import com.memo.memo.LegacyMemos
import com.memo.memo.LegacyProblem
import org.springframework.stereotype.Component
import java.util.UUID

/** What the validation step gave: the highest rank the READY rows write, and whether the problem cap was reached. */
data class ValidationResult(
    val maxRank: Int?,
    val tooManyProblems: Boolean,
)

/**
 * The dry run's validation (design E06 §5.2 step 4): every Memos row, in chunks of [DryRunLimits.CHUNK], assembled
 * through the mappings and checked by `LegacyMemos.check` and the imports module's own rules, inside a `readInTenant`
 * transaction that PostgreSQL holds read-only (the T1 spike), so the check cannot write; each chunk's results are written
 * afterwards in a write transaction on the staging tables. Then the Files and Approvals rows without a Memos row, and
 * the originals no row uses. Call outside a transaction, with the tenant bound.
 */
@Component
class DryRunValidator(
    private val reads: ChunkReads,
    private val cross: CrossRowReads,
    private val mappings: ImportMappingStore,
    private val batches: ImportBatchStore,
    private val legacy: LegacyMemos,
    private val clearances: Clearances,
    private val results: FindingStore,
    private val transactions: TenantTransactions,
    private val tenantDsl: TenantDsl,
) {
    fun validate(
        batchId: UUID,
        findings: StageFindings,
    ): ValidationResult {
        val tenant = TenantContext.current()
        val ctx = transactions.readInTenant(tenant) { context(batchId, findings) }
        val budget = Budget()
        var maxRank: Int? = null
        var after = 0
        while (true) {
            val outcomes = transactions.readInTenant(tenant) { chunk(ctx, after) }
            if (outcomes.isEmpty()) break
            transactions.inTenant(tenant) { results.results(batchId, outcomes.map(budget::cap)) }
            val ready = outcomes.filter { it.first.result == RowResult.READY }.mapNotNull { it.first.rank }
            maxRank = (listOfNotNull(maxRank) + ready).maxOrNull()
            after = outcomes.last().second
        }
        val rest = transactions.readInTenant(tenant) { orphans(batchId, findings) + originals(batchId) }
        transactions.inTenant(tenant) { results.add(batchId, null, budget.take(rest)) }
        return ValidationResult(maxRank, budget.exceeded)
    }

    private fun context(
        batchId: UUID,
        findings: StageFindings,
    ): ValidationContext {
        val batch = batches.find(batchId) ?: throw ImportBatchNotFoundException()
        return ValidationContext(
            batch = batch,
            mappings = mappings.all(batchId),
            levels = clearances.levels(),
            series = legacy.series(),
            duplicateKeys = cross.duplicateKeys(batchId),
            similarKeys = cross.similarKeys(batchId),
            sharedRows = cross.sharedRows(batchId),
            findings = findings,
        )
    }

    /** One chunk of rows after row [after], each with its outcome and row number. Inside a read-only transaction. */
    private fun chunk(
        ctx: ValidationContext,
        after: Int,
    ): List<Pair<RowOutcome, Int>> {
        // The check runs read-only by construction (ADR-0055): refuse to run in a transaction that could write.
        check(readOnly()) { "the dry run validates only in a read-only transaction" }
        val memos = reads.memos(ctx.batch.id, after, DryRunLimits.CHUNK)
        if (memos.isEmpty()) return emptyList()
        val ids = memos.map { it.id }
        val files = reads.files(ids).groupBy { it.rowId }
        val approvals = reads.approvals(ids).groupBy { it.rowId }
        val assembly = RowAssembly(ctx)
        return memos.map { memo ->
            val assembled = assembly.assemble(memo, files[memo.id].orEmpty(), approvals[memo.id].orEmpty())
            val all = (assembled.findings + checked(assembled)).distinct()
            val result = if (all.any { it.level == ProblemLevel.ERROR }) RowResult.REJECTED else RowResult.READY
            RowOutcome(memo.id, result, assembled.row.source.fingerprint, assembled.rank, all) to memo.rowNumber
        }
    }

    /**
     * The memo module's check, placed in the sheets, less what the imports module already said about the same cell; and
     * an earlier import of the row key: the same fingerprint is imported already (`ALREADY_IMPORTED`, skipped at import),
     * another is a conflict (`ALREADY_IMPORTED_CHANGED`). Either way its reference is that memo's, not a clash.
     */
    private fun checked(assembled: AssembledRow): List<Finding> {
        val row = assembled.row
        val number = row.source.legacyNumber
        val existing = number.takeIf { it.isNotBlank() }?.let { legacy.existing(row.source.sourceCode, it) }
        val own =
            assembled.findings
                .filter { it.level == ProblemLevel.ERROR }
                .map { Triple(it.sheet, it.rowNumber, it.column) }
                .toSet()
        val located =
            legacy
                .check(row)
                .filterNot { existing != null && it.code == "NUMBER_IN_USE" }
                .map { locate(it, assembled) }
                .filterNot { Triple(it.sheet, it.rowNumber, it.column) in own }
        val at = row.source.rowNumber
        val again =
            when {
                existing == null -> {
                    null
                }

                existing.fingerprint == row.source.fingerprint -> {
                    Finding(ProblemLevel.INFO, "ALREADY_IMPORTED", ProblemSheet.MEMOS, at, MemoColumns.LEGACY_NUMBER)
                }

                else -> {
                    Finding(ProblemLevel.ERROR, "ALREADY_IMPORTED_CHANGED", ProblemSheet.MEMOS, at, MemoColumns.LEGACY_NUMBER)
                }
            }
        return located + listOfNotNull(again)
    }

    private fun orphans(
        batchId: UUID,
        findings: StageFindings,
    ): List<Finding> =
        cross.orphans(batchId).flatMap { orphan ->
            findings.of(orphan.sheet, orphan.rowNumber) +
                Finding(
                    ProblemLevel.ERROR,
                    "ORPHAN_ROW",
                    orphan.sheet,
                    orphan.rowNumber,
                    MemoColumns.LEGACY_NUMBER,
                    orphan.key?.let { mapOf("legacyNumber" to it.take(DryRunLimits.MAX_PARAM)) }.orEmpty(),
                )
        }

    /** Originals no Files row uses, and paths that differ from another only in case. */
    private fun originals(batchId: UUID): List<Finding> {
        val unused =
            cross.unreferenced(batchId).map { original ->
                val params = mapOf("path" to original.path.take(DryRunLimits.MAX_PARAM))
                if (original.state == IntakeState.REFUSED) {
                    file("FILE_REFUSED", original.intakeId, params + ("reason" to original.refusal.orEmpty()))
                } else {
                    file("FILE_UNREFERENCED", original.intakeId, params)
                }
            }
        val caseOnly =
            cross.caseOnly(batchId).map { (id, path) ->
                file(
                    "PATH_CASE_ONLY",
                    id,
                    mapOf("path" to path.take(DryRunLimits.MAX_PARAM)),
                )
            }
        return unused + caseOnly
    }

    private fun file(
        code: String,
        intakeId: UUID,
        params: Map<String, Any>,
    ) = Finding(ProblemLevel.FILE, code, ProblemSheet.ORIGINALS, params = params, intakeId = intakeId)

    private fun readOnly(): Boolean = tenantDsl.current().fetchValue("show transaction_read_only")?.toString() == "on"

    /** The batch's problem cap and the per-row cap (errors first, then warnings, then the rest). */
    private class Budget {
        private var used = 0
        var exceeded = false
            private set

        fun cap(outcome: Pair<RowOutcome, Int>): RowOutcome {
            val row = outcome.first
            val kept = row.findings.sortedBy { it.level.ordinal }.take(DryRunLimits.MAX_PER_ROW)
            return row.copy(findings = take(kept))
        }

        fun take(findings: List<Finding>): List<Finding> {
            val room = (DryRunLimits.MAX_PROBLEMS - used).coerceAtLeast(0)
            if (findings.size > room) exceeded = true
            return findings.take(room).also { used += it.size }
        }
    }

    companion object {
        /**
         * Where a problem of `LegacyMemos.check` sits in the sheets (design E06 §10: the problem list links to the row): an
         * original's on its Files row, an approval's on its Approvals row, everything else on the Memos row.
         */
        fun locate(
            problem: LegacyProblem,
            row: AssembledRow,
        ): Finding {
            val parts = problem.field.split('/')
            val memo = row.row.source.rowNumber
            val (sheet, rowNumber, column) =
                when (parts[0]) {
                    "originals" -> part(parts, ProblemSheet.FILES, parts.getOrNull(1)?.toIntOrNull()?.let(row.originRows::getOrNull), memo)
                    "approvals" -> part(parts, ProblemSheet.APPROVALS, parts.getOrNull(1)?.toIntOrNull()?.let(row.approvalRows::get), memo)
                    "initiator" -> Triple(ProblemSheet.MEMOS, memo, INITIATOR[parts.getOrNull(1)])
                    "reference" -> Triple(ProblemSheet.MEMOS, memo, MemoColumns.LEGACY_NUMBER)
                    in WHOLE_ROW -> Triple(ProblemSheet.MEMOS, memo, null)
                    else -> Triple(ProblemSheet.MEMOS, memo, problem.field)
                }
            return Finding(ProblemLevel.ERROR, problem.code, sheet, rowNumber, column)
        }

        private fun part(
            parts: List<String>,
            sheet: ProblemSheet,
            rowNumber: Int?,
            memo: Int,
        ): Triple<ProblemSheet, Int, String?> =
            if (rowNumber == null) Triple(ProblemSheet.MEMOS, memo, null) else Triple(sheet, rowNumber, parts.getOrNull(2))

        private val WHOLE_ROW = setOf("dates", "people", "source_code", "row_number")
        private val INITIATOR =
            mapOf("name" to MemoColumns.INITIATOR_NAME, "email" to MemoColumns.INITIATOR_EMAIL, "legacy_id" to MemoColumns.INITIATOR_ID)
    }
}
