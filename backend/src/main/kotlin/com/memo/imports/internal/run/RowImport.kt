package com.memo.imports.internal.run

import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantTransactions
import com.memo.files.FileUploads
import com.memo.files.Files
import com.memo.files.UploadRefusedException
import com.memo.imports.internal.dryrun.AssembledRow
import com.memo.imports.internal.dryrun.ChunkReads
import com.memo.imports.internal.dryrun.DryRunLimits
import com.memo.imports.internal.dryrun.DryRunValidator
import com.memo.imports.internal.dryrun.Finding
import com.memo.imports.internal.dryrun.FindingStore
import com.memo.imports.internal.dryrun.MemoColumns
import com.memo.imports.internal.dryrun.ProblemLevel
import com.memo.imports.internal.dryrun.ProblemSheet
import com.memo.imports.internal.dryrun.ProblemStage
import com.memo.imports.internal.dryrun.RowAssembly
import com.memo.imports.internal.dryrun.RowResult
import com.memo.imports.internal.dryrun.ValidationContext
import com.memo.memo.LegacyMemoRefusedException
import com.memo.memo.LegacyMemoRow
import com.memo.memo.LegacyMemos
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.UUID

/** What the pre-check decided for a row: settle it now with [findings], or import [row] with its originals. */
sealed interface RowPlan {
    data class Settle(
        val result: RowResult,
        val findings: List<Finding>,
    ) : RowPlan

    data class Import(
        val row: LegacyMemoRow,
        val assembled: AssembledRow,
        val promotions: List<Promotion>,
    ) : RowPlan
}

/**
 * One Memos row of a running import (design E06 §5.3 steps 3 to 6): taken by a guarded update, checked again in a
 * read-only transaction (an earlier import of its key, and `LegacyMemos.check`), its originals promoted through the upload
 * pipeline into `memo.legacy` with their intake SHA-256 (outside a transaction), then the memo created and the row marked
 * IMPORTED in one transaction, and the originals kept for the type's retention. A failure stays with its row: a refused
 * original fails it, a check rejects it, anything else returns it to READY until its attempts run out. Runs as the import's
 * system actor, with the tenant bound, outside a transaction.
 */
@Component
class RowImport(
    private val rows: RunRows,
    private val promotions: Promotions,
    private val reads: ChunkReads,
    private val legacy: LegacyMemos,
    private val uploads: FileUploads,
    private val files: Files,
    private val findings: FindingStore,
    private val transactions: TenantTransactions,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** Imports row [rowId] of [ctx]'s batch; does nothing when another job took it first. */
    @Suppress("TooGenericExceptionCaught") // a row's failure must stay with the row, whatever it is (design E06 §5.3)
    fun run(
        ctx: ValidationContext,
        rowId: UUID,
    ) {
        val tenant = TenantContext.current()
        val attempt = transactions.inTenant(tenant) { rows.take(rowId) } ?: return
        val held = Held(ctx.batch.id, rowId, attempt)
        try {
            when (val plan = transactions.readInTenant(tenant) { plan(ctx, rowId) }) {
                is RowPlan.Settle -> settle(held, plan.result, plan.findings)
                is RowPlan.Import -> import(held, plan)
            }
        } catch (e: OriginalRefused) {
            if (e.reason == SCAN_UNAVAILABLE) retry(held, e) else refused(held, e)
        } catch (e: RuntimeException) {
            retry(held, e)
        }
    }

    /** The pre-check (design E06 §5.3 step 4), in a read-only transaction. */
    private fun plan(
        ctx: ValidationContext,
        rowId: UUID,
    ): RowPlan {
        val memo = checkNotNull(reads.memo(rowId)) { "row $rowId is gone" }
        val target = checkNotNull(promotions.target(rowId)) { "row $rowId has no reserved memo id" }
        val assembled = RowAssembly(ctx).assemble(memo, reads.files(listOf(rowId)), reads.approvals(listOf(rowId)))
        val row = assembled.row.copy(memoId = target.memoId)
        val earlier = earlier(row)
        val errors = assembled.findings.filter { it.level == ProblemLevel.ERROR }
        val unreceived = target.promotions.filter { it.intakeVersionId == null || it.sha256 == null }
        return when {
            earlier != null -> earlier
            errors.isNotEmpty() -> RowPlan.Settle(RowResult.REJECTED, errors)
            unreceived.isNotEmpty() -> RowPlan.Settle(RowResult.REJECTED, unreceived.map { notReceived(it) })
            else -> checked(row, assembled, target)
        }
    }

    /** An earlier import of the row key: the same fingerprint is SKIPPED, another is REJECTED `ALREADY_IMPORTED_CHANGED`. */
    private fun earlier(row: LegacyMemoRow): RowPlan.Settle? {
        val source = row.source
        val existing = legacy.existing(source.sourceCode, source.legacyNumber) ?: return null
        val same = existing.fingerprint == source.fingerprint
        val level = if (same) ProblemLevel.INFO else ProblemLevel.ERROR
        val code = if (same) "ALREADY_IMPORTED" else "ALREADY_IMPORTED_CHANGED"
        val finding = Finding(level, code, ProblemSheet.MEMOS, source.rowNumber, MemoColumns.LEGACY_NUMBER)
        return RowPlan.Settle(if (same) RowResult.SKIPPED else RowResult.REJECTED, listOf(finding))
    }

    /** `LegacyMemos.check` again: a type unpublished, a unit gone or a number taken since the dry run rejects the row. */
    private fun checked(
        row: LegacyMemoRow,
        assembled: AssembledRow,
        target: RowTarget,
    ): RowPlan {
        val problems = legacy.check(row).map { DryRunValidator.locate(it, assembled) }.distinct()
        return if (problems.isEmpty()) RowPlan.Import(row, assembled, target.promotions) else RowPlan.Settle(RowResult.REJECTED, problems)
    }

    /** Promotes the originals, then commits the memo and the row together; keeps the originals for the type's years. */
    private fun import(
        held: Held,
        plan: RowPlan.Import,
    ) {
        val tenant = TenantContext.current()
        val stored = plan.promotions.associate { it.rowNumber to promote(held, plan.row.memoId, it) }
        val originals = plan.row.originals.mapIndexed { i, original -> original.copy(fileId = stored[plan.assembled.originRows[i]]) }
        val row = plan.row.copy(originals = originals)
        try {
            transactions.inTenant(tenant) {
                check(rows.settle(held.rowId, held.attempt, RowResult.IMPORTED)) { "row ${held.rowId} is no longer this attempt's" }
                legacy.create(row)
            }
        } catch (e: LegacyMemoRefusedException) {
            refusedAtCommit(held, row, plan.assembled, e)
            return
        }
        retain(row.memoId)
    }

    /** One original through the pipeline into `memo.legacy` (idempotent on its key); returns the stored file's id. */
    private fun promote(
        held: Held,
        memoId: UUID,
        promotion: Promotion,
    ): UUID {
        val tenant = TenantContext.current()
        val versionId =
            try {
                uploads.copyFrom(
                    checkNotNull(promotion.intakeVersionId),
                    LegacyMemos.ORIGINAL_PURPOSE,
                    memoId.toString(),
                    "imp-${held.batchId}-${promotion.rowFileId}",
                    checkNotNull(promotion.sha256),
                )
            } catch (e: UploadRefusedException) {
                throw OriginalRefused(promotion, e.reason, e)
            }
        val fileId = transactions.readInTenant(tenant) { checkNotNull(files.version(versionId)).fileId }
        transactions.inTenant(tenant) { promotions.stored(promotion.rowFileId, fileId, versionId) }
        return fileId
    }

    /**
     * The memo module refused the row at commit (a race since the pre-check): the key imported meanwhile is decided as the
     * pre-check would; anything else rejects the row. Originals already promoted stay stored under the reserved id.
     */
    private fun refusedAtCommit(
        held: Held,
        row: LegacyMemoRow,
        assembled: AssembledRow,
        e: LegacyMemoRefusedException,
    ) {
        val tenant = TenantContext.current()
        val earlier = if (e.problems.any { it.code == "ALREADY_IMPORTED" }) transactions.readInTenant(tenant) { earlier(row) } else null
        val plan = earlier ?: RowPlan.Settle(RowResult.REJECTED, e.problems.map { DryRunValidator.locate(it, assembled) }.distinct())
        settle(held, plan.result, plan.findings)
    }

    /** A refused original fails its row with the pipeline's reason (a differing hash as `FILE_HASH_MISMATCH`). */
    private fun refused(
        held: Held,
        e: OriginalRefused,
    ) {
        val promotion = e.promotion
        val params = listOfNotNull(promotion.path?.let { "path" to it.take(DryRunLimits.MAX_PARAM) }, "reason" to e.reason).toMap()
        val code = if (e.reason == HASH_MISMATCH) "FILE_HASH_MISMATCH" else "FILE_REFUSED"
        settle(held, RowResult.FAILED, listOf(Finding(ProblemLevel.ERROR, code, ProblemSheet.FILES, promotion.rowNumber, "path", params)))
    }

    /** Another attempt later, or FAILED `IMPORT_ERROR` once the row had [ImportRunLimits.ATTEMPTS]. */
    private fun retry(
        held: Held,
        e: RuntimeException,
    ) {
        val tenant = TenantContext.current()
        if (held.attempt < ImportRunLimits.ATTEMPTS) {
            log.warn("Import of row {} of batch {} failed (attempt {}); it will be tried again", held.rowId, held.batchId, held.attempt, e)
            transactions.inTenant(tenant) { rows.release(held.rowId, held.attempt) }
        } else {
            log.error("Import of row {} of batch {} failed for good", held.rowId, held.batchId, e)
            settle(held, RowResult.FAILED, listOf(Finding(ProblemLevel.ERROR, "IMPORT_ERROR", ProblemSheet.MEMOS)))
        }
    }

    /** The row's result and its problems (stage IMPORT), if this attempt still holds it. */
    private fun settle(
        held: Held,
        result: RowResult,
        found: List<Finding>,
    ) = transactions.inTenant(TenantContext.current()) {
        val errors = found.count { it.level == ProblemLevel.ERROR }
        if (rows.settle(held.rowId, held.attempt, result, errors)) findings.add(held.batchId, held.rowId, found, ProblemStage.IMPORT)
    }

    /** The type's retention for the stored originals; a failure is left to the verification pass, which repeats it. */
    @Suppress("TooGenericExceptionCaught") // the memo is committed; storage trouble here must not fail its row
    private fun retain(memoId: UUID) {
        try {
            legacy.retainOriginals(memoId)
        } catch (e: RuntimeException) {
            log.warn("Retention of the originals of imported memo {} not extended yet", memoId, e)
        }
    }

    /** The row a job holds: its batch, its id and the attempt that took it. */
    private data class Held(
        val batchId: UUID,
        val rowId: UUID,
        val attempt: Int,
    )

    /** The pipeline refused one original ([reason] as in `file.upload.rejected`), with the Files row it came from. */
    private class OriginalRefused(
        val promotion: Promotion,
        val reason: String,
        cause: UploadRefusedException,
    ) : RuntimeException(reason, cause)

    private companion object {
        /** Refusal reasons of the pipeline (`UploadRefusedException.reason`): the scanner was down (try again), the hash differed. */
        const val SCAN_UNAVAILABLE = "SCAN_UNAVAILABLE"
        const val HASH_MISMATCH = "HASH_MISMATCH"
    }
}

/** A Files row whose original was not received whole (the dry run reports it; the import meets it only after a race). */
private fun notReceived(promotion: Promotion): Finding =
    Finding(
        ProblemLevel.ERROR,
        "FILE_NOT_RECEIVED",
        ProblemSheet.FILES,
        promotion.rowNumber,
        "path",
        promotion.path?.let { mapOf("path" to it.take(DryRunLimits.MAX_PARAM)) }.orEmpty(),
    )
