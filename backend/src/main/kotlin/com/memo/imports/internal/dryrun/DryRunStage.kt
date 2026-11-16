package com.memo.imports.internal.dryrun

import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantTransactions
import com.memo.imports.internal.ImportSources
import com.memo.imports.internal.PathRules
import com.memo.imports.internal.sheet.CellKind
import com.memo.imports.internal.sheet.FormulaState
import com.memo.imports.internal.sheet.RowSink
import com.memo.imports.internal.sheet.SheetCell
import com.memo.imports.internal.sheet.SheetColumns
import com.memo.imports.internal.sheet.SheetProblem
import com.memo.imports.internal.sheet.SheetRow
import com.memo.imports.internal.sheet.SheetTable
import com.memo.memo.LegacyMemos
import com.memo.memo.LegacyNumbers
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * What staging noticed about single cells (design E06 §6.1, §7): a legacy number held as a number (`NUMBER_NOT_TEXT`), a
 * formula's cached value (`FORMULA_CACHED`) or none (`FORMULA_NO_VALUE`), a value cut to fit the staging row. Kept by
 * sheet and row number for the validation, which charges them to the Memos row they belong to.
 */
class StageFindings {
    private val bySheetRow = HashMap<Pair<ProblemSheet, Int>, MutableList<Finding>>()
    var count = 0
        private set

    /** Keeps [finding] while fewer than [DryRunLimits.MAX_PROBLEMS] were found; counts it either way. */
    fun add(finding: Finding) {
        if (count < DryRunLimits.MAX_PROBLEMS) {
            bySheetRow.getOrPut(checkNotNull(finding.sheet) to checkNotNull(finding.rowNumber)) { mutableListOf() } += finding
        }
        count++
    }

    fun of(
        sheet: ProblemSheet,
        rowNumber: Int,
    ): List<Finding> = bySheetRow[sheet to rowNumber].orEmpty()
}

/** What staging gave: the batch problems (none: the rows are staged) and the cell findings. */
data class StageResult(
    val problems: List<Finding>,
    val findings: StageFindings,
    val rows: Map<SheetTable, Int>,
)

/**
 * The dry run's first step (design E06 §5.2 step 1): reads the batch's sources through the T5 readers on the parse pool
 * and stages their rows in place of the previous dry run's, a thousand at a time in write transactions on the staging
 * tables only. A batch problem stops here, and so does a `field:<key>` column no active type has. Call outside a
 * transaction, with the tenant bound.
 */
@Component
class DryRunStager(
    private val sources: ImportSources,
    private val store: StagingStore,
    private val legacy: LegacyMemos,
    private val transactions: TenantTransactions,
) {
    fun stage(batchId: UUID): StageResult {
        val tenant = TenantContext.current()
        transactions.inTenant(tenant) { store.clear(batchId) }
        val sink = Sink(batchId)
        val read = sources.parse(batchId, sink)
        // A staging write that failed stopped the read; it is the job's to retry, not a problem of the sources.
        sink.failure?.let { throw it }
        val problems =
            when {
                !read.ok -> read.problems.map(::batchProblem)
                sink.findings.count > DryRunLimits.MAX_PROBLEMS -> listOf(DryRunLimits.tooManyProblems())
                else -> sink.flush().let { unknownFields(sink.fieldKeys) }
            }
        if (problems.isNotEmpty()) transactions.inTenant(tenant) { store.clear(batchId) }
        return StageResult(problems, sink.findings, read.rows)
    }

    /** `field:<key>` columns whose key no active type with a published version has (design E06 §6.1). */
    private fun unknownFields(keys: Set<String>): List<Finding> {
        if (keys.isEmpty()) return emptyList()
        val known = transactions.readInTenant(TenantContext.current()) { legacy.types().flatMap { it.fieldKeys }.toSet() }
        return keys.filterNot { it in known }.sorted().map { key ->
            Finding(ProblemLevel.BATCH, "COLUMN_UNKNOWN", ProblemSheet.MEMOS, 1, SheetColumns.FIELD_PREFIX + key)
        }
    }

    private fun batchProblem(problem: SheetProblem): Finding =
        Finding(
            ProblemLevel.BATCH,
            problem.code.name,
            problem.table?.let { ProblemSheet.valueOf(it.name) } ?: ProblemSheet.BATCH,
            problem.rowNumber,
            problem.column,
            problem.params,
        )

    /** Receives rows on the parse pool's thread (the tenant travels with it) and stages them in groups. */
    private inner class Sink(
        private val batchId: UUID,
    ) : RowSink {
        val findings = StageFindings()
        val fieldKeys = mutableSetOf<String>()
        private val memos = mutableListOf<StagedMemo>()
        private val files = mutableListOf<StagedFile>()
        private val approvals = mutableListOf<StagedApproval>()

        override fun row(row: SheetRow) {
            notice(row)
            when (row.table) {
                SheetTable.MEMOS -> memos += memo(row)
                SheetTable.FILES -> files += file(row)
                SheetTable.APPROVALS -> approvals += approval(row)
            }
            if (memos.size + files.size + approvals.size >= GROUP) flush()
        }

        var failure: RuntimeException? = null
            private set

        fun flush() {
            if (memos.isEmpty() && files.isEmpty() && approvals.isEmpty()) return
            try {
                transactions.inTenant(TenantContext.current()) {
                    store.memos(batchId, memos)
                    store.files(batchId, files)
                    store.approvals(batchId, approvals)
                }
            } catch (
                @Suppress("TooGenericExceptionCaught") e: RuntimeException,
            ) {
                // Kept so the stager rethrows it: the parser's catch-all would report it as a malformed source.
                failure = e
                throw e
            }
            memos.clear()
            files.clear()
            approvals.clear()
        }

        private fun notice(row: SheetRow) {
            val sheet = ProblemSheet.valueOf(row.table.name)
            row.cells.forEach { (column, cell) ->
                stageFinding(sheet, row.rowNumber, column, cell)?.let(findings::add)
                if (row.table == SheetTable.MEMOS &&
                    SheetColumns.isField(column)
                ) {
                    fieldKeys += column.removePrefix(SheetColumns.FIELD_PREFIX)
                }
            }
        }

        private fun memo(row: SheetRow): StagedMemo {
            val cells = row.cells.mapValues { it.value.text }.filterValues { it.isNotEmpty() }
            val fits = cells.values.sumOf { it.length } <= MAX_ROW_CHARS
            if (!fits) {
                cells.filterValues { it.length > MAX_CUT }.keys.forEach {
                    findings.add(Finding(ProblemLevel.ERROR, "VALUE_TOO_LONG", ProblemSheet.MEMOS, row.rowNumber, it))
                }
            }
            return StagedMemo(row.rowNumber, if (fits) cells else cells.mapValues { it.value.take(MAX_CUT) })
        }

        private fun file(row: SheetRow): StagedFile {
            val path = text(row, "path")
            return StagedFile(
                row.rowNumber,
                key(row),
                path?.let { PathRules.normalise(it.replace('\\', '/')) ?: it },
                text(row, "role"),
                text(row, "title"),
                text(row, "sha256"),
                text(row, "classification"),
            )
        }

        private fun approval(row: SheetRow): StagedApproval =
            StagedApproval(
                row.rowNumber,
                key(row),
                text(row, "order"),
                text(row, "name"),
                text(row, "email"),
                text(row, "legacy_id"),
                text(row, "position"),
                text(row, "decision"),
                text(row, "decided_on"),
                text(row, "remark"),
            )

        private fun key(row: SheetRow): String? = text(row, MemoColumns.LEGACY_NUMBER)?.let(LegacyNumbers::key)?.takeIf { it.isNotEmpty() }

        private fun text(
            row: SheetRow,
            column: String,
        ): String? = row.cells[column]?.text?.takeIf { it.isNotEmpty() }
    }

    companion object {
        /** Rows staged per write transaction (design E06 §14: batched inserts of 1,000). */
        const val GROUP = 1_000

        /** A Memos row's values together stay well inside the staging row's 256 KB; longer values are cut and refused. */
        private const val MAX_ROW_CHARS = 100_000
        private const val MAX_CUT = 1_000

        /** What one cell tells before any mapping: how it was held, and whether it came from a formula. */
        fun stageFinding(
            sheet: ProblemSheet,
            rowNumber: Int,
            column: String,
            cell: SheetCell,
        ): Finding? =
            when {
                cell.formula == FormulaState.NO_VALUE -> {
                    Finding(ProblemLevel.ERROR, "FORMULA_NO_VALUE", sheet, rowNumber, column)
                }

                cell.formula == FormulaState.CACHED -> {
                    Finding(ProblemLevel.WARNING, "FORMULA_CACHED", sheet, rowNumber, column)
                }

                column == MemoColumns.LEGACY_NUMBER && cell.kind == CellKind.NUMBER -> {
                    Finding(ProblemLevel.WARNING, "NUMBER_NOT_TEXT", sheet, rowNumber, column)
                }

                else -> {
                    null
                }
            }
    }
}
