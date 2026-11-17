package com.memo.imports.internal.sheet

import java.util.Locale

/** A table of a batch's metadata (ADR-0055, design E06 §6.1): a sheet of the workbook, or one CSV file. */
enum class SheetTable(
    /** The sheet's name in the workbook (matched without regard to case). */
    val sheetName: String,
    /** The columns, in the template's order; a `field:<key>` column is allowed on [MEMOS] besides these. */
    val columns: List<SheetColumn>,
    /** Whether a batch needs this table (Approvals may be left out). */
    val required: Boolean,
) {
    MEMOS(
        "Memos",
        listOf(
            SheetColumn("legacy_number", true),
            SheetColumn("memo_type", true),
            SheetColumn("subject", true),
            SheetColumn("status", true),
            SheetColumn("classification", false),
            SheetColumn("origin_unit", true),
            SheetColumn("raised_on", true),
            SheetColumn("decided_on", false),
            SheetColumn("closed_on", false),
            SheetColumn("initiator_name", true),
            SheetColumn("initiator_email", false),
            SheetColumn("initiator_id", false),
        ),
        true,
    ),
    FILES(
        "Files",
        listOf(
            SheetColumn("legacy_number", true),
            SheetColumn("path", true),
            SheetColumn("role", true),
            SheetColumn("title", false),
            SheetColumn("sha256", false),
            SheetColumn("classification", false),
        ),
        true,
    ),
    APPROVALS(
        "Approvals",
        listOf(
            SheetColumn("legacy_number", true),
            SheetColumn("order", true),
            SheetColumn("name", true),
            SheetColumn("email", false),
            SheetColumn("legacy_id", false),
            SheetColumn("position", false),
            SheetColumn("decision", true),
            SheetColumn("decided_on", false),
            SheetColumn("remark", false),
        ),
        false,
    ),
    ;

    private val names = columns.map { it.name }.toSet()

    /** Whether [column] (already [SheetColumns.normalise]d) is one of this table's. */
    fun knows(column: String): Boolean = column in names || (this == MEMOS && SheetColumns.isField(column))

    companion object {
        /** Sheets of the template that the reader skips. */
        val IGNORED_SHEETS = setOf("instructions", "lists")

        fun ofSheet(name: String): SheetTable? = entries.firstOrNull { it.sheetName.equals(name.trim(), ignoreCase = true) }
    }
}

data class SheetColumn(
    val name: String,
    val required: Boolean,
)

/** Header names as the admin wrote them, to the column keys of [SheetTable] (design E06 §6.1). */
object SheetColumns {
    const val FIELD_PREFIX = "field:"
    private val SEPARATORS = Regex("[\\s_-]+")
    private val FIELD_KEY = Regex("^[A-Za-z][A-Za-z0-9_]{0,63}$")

    /**
     * Case-insensitive, with spaces, `-` and `_` alike: "Legacy number" and `legacy-number` are `legacy_number`. A
     * `field:<key>` column keeps its key as written (keys are the type's own).
     */
    fun normalise(header: String): String {
        val trimmed = header.trim()
        if (trimmed.startsWith(FIELD_PREFIX, ignoreCase = true)) return FIELD_PREFIX + trimmed.substring(FIELD_PREFIX.length).trim()
        return trimmed.lowercase(Locale.ROOT).replace(SEPARATORS, "_").trim('_')
    }

    fun isField(column: String): Boolean = column.startsWith(FIELD_PREFIX) && FIELD_KEY.matches(column.removePrefix(FIELD_PREFIX))
}

/** How a value was held: text, a number (a numeric cell), or a date (a date cell, given as ISO 8601). */
enum class CellKind { TEXT, NUMBER, DATE }

/** Whether the value came from a formula: none, its cached value (`FORMULA_CACHED`), or none at all (`FORMULA_NO_VALUE`). */
enum class FormulaState { NONE, CACHED, NO_VALUE }

/**
 * One value of a row, trimmed. A date cell is `yyyy-MM-dd` (or `yyyy-MM-ddTHH:mm:ss` with a time) in the workbook's own
 * date system; a number is its plain decimal text. Nothing else is interpreted (ISO text dates stay text).
 */
data class SheetCell(
    val text: String,
    val kind: CellKind = CellKind.TEXT,
    val formula: FormulaState = FormulaState.NONE,
)

/** One data row of [table]: [rowNumber] as the admin sees it (the sheet's row, or the CSV line's record with the header 1). */
data class SheetRow(
    val table: SheetTable,
    val rowNumber: Int,
    /** By column key; blank cells left out. */
    val cells: Map<String, SheetCell>,
)

/** The batch-level problem codes the readers give (design E06 §9): any of them stops the batch. */
enum class SheetProblemCode {
    SOURCE_MISSING,
    SOURCE_KIND_MIXED,
    NOT_UTF8,
    DELIMITER_UNKNOWN,
    HEADER_MISSING,
    COLUMN_UNKNOWN,
    COLUMN_DUPLICATE,
    COLUMN_REQUIRED,
    SHEET_MISSING,
    SHEET_UNKNOWN,
    TOO_MANY_ROWS,
    TOO_MANY_COLUMNS,
    VALUE_TOO_LONG_FOR_FILE,
    MALFORMED,
    PARSE_TIMEOUT,
}

/** A batch problem: where (the table, row and column when known) and values for the message (never content). */
data class SheetProblem(
    val code: SheetProblemCode,
    val table: SheetTable? = null,
    val rowNumber: Int? = null,
    val column: String? = null,
    val params: Map<String, Any> = emptyMap(),
)

/** The caps of design E06 §7. Tests and the fuzz targets use smaller ones. */
data class SheetLimits(
    val maxBytes: Long = MAX_BYTES,
    val maxColumns: Int = MAX_COLUMNS,
    val maxCellChars: Int = MAX_CELL_CHARS,
    val maxRows: Map<SheetTable, Int> = MAX_ROWS,
) {
    fun rows(table: SheetTable): Int = maxRows.getValue(table)

    companion object {
        const val MAX_BYTES = 20L shl 20
        const val MAX_COLUMNS = 100

        /** Excel's own limit per cell. */
        const val MAX_CELL_CHARS = 32_767
        val MAX_ROWS = mapOf(SheetTable.MEMOS to 50_000, SheetTable.FILES to 200_000, SheetTable.APPROVALS to 250_000)
    }
}

/** Receives each data row as it is read (the dry run stages them in batches). Throwing stops the read. */
fun interface RowSink {
    fun row(row: SheetRow)
}

/** What a read gave: the batch problems (empty: the source is usable) and the data rows read per table. */
data class SheetResult(
    val problems: List<SheetProblem>,
    val rows: Map<SheetTable, Int>,
) {
    val ok: Boolean get() = problems.isEmpty()

    companion object {
        fun failed(problem: SheetProblem) = SheetResult(listOf(problem), emptyMap())
    }
}

/** Stops a read where it is: the reader turns it into its [problem]. */
class SheetStop(
    val problem: SheetProblem,
) : RuntimeException(problem.code.name, null, false, false)

/** Checked by the readers between rows and cells; a read whose time is up stops as `PARSE_TIMEOUT`. */
fun interface ReadDeadline {
    fun passed(): Boolean

    companion object {
        val NONE = ReadDeadline { false }
    }
}

/**
 * Collects a table's rows against its header: header checks, the column, value-length and row caps. Shared by both
 * readers so a workbook sheet and a CSV file give the same rows and the same problems.
 */
class TableCollector(
    private val table: SheetTable,
    private val limits: SheetLimits,
    private val sink: RowSink,
    private val deadline: ReadDeadline,
) {
    private var columns: List<String?> = emptyList()
    var rows = 0
        private set

    /** Checks the header row; returns its problems (empty: rows may follow). Positions are 0-based. */
    fun header(names: List<String>): List<SheetProblem> {
        val trimmed = names.map { it.trim() }
        return when {
            trimmed.all { it.isBlank() } -> {
                listOf(SheetProblem(SheetProblemCode.HEADER_MISSING, table, 1))
            }

            trimmed.size > limits.maxColumns -> {
                listOf(SheetProblem(SheetProblemCode.TOO_MANY_COLUMNS, table, 1, params = mapOf("limit" to limits.maxColumns)))
            }

            else -> {
                columns(trimmed)
            }
        }
    }

    private fun columns(trimmed: List<String>): List<SheetProblem> {
        val keys = trimmed.map { SheetColumns.normalise(it) }
        val problems = mutableListOf<SheetProblem>()
        keys.forEachIndexed { i, key ->
            when {
                key.isEmpty() -> problems += SheetProblem(SheetProblemCode.HEADER_MISSING, table, 1, params = mapOf("position" to i + 1))
                !table.knows(key) -> problems += SheetProblem(SheetProblemCode.COLUMN_UNKNOWN, table, 1, clip(trimmed[i]))
                keys.indexOf(key) != i -> problems += SheetProblem(SheetProblemCode.COLUMN_DUPLICATE, table, 1, key)
            }
        }
        table.columns.filter { it.required && it.name !in keys }.forEach {
            problems += SheetProblem(SheetProblemCode.COLUMN_REQUIRED, table, 1, it.name)
        }
        columns = keys
        return problems
    }

    /** A data row: [values] by 0-based position. Blank rows are skipped. */
    fun row(
        rowNumber: Int,
        values: Map<Int, SheetCell>,
    ) {
        val present = values.filterValues { it.text.isNotEmpty() || it.formula == FormulaState.NO_VALUE }
        val problem =
            when {
                deadline.passed() -> {
                    SheetProblem(SheetProblemCode.PARSE_TIMEOUT, table, rowNumber)
                }

                present.isEmpty() -> {
                    null
                }

                rows >= limits.rows(table) -> {
                    SheetProblem(SheetProblemCode.TOO_MANY_ROWS, table, rowNumber, params = mapOf("limit" to limits.rows(table)))
                }

                else -> {
                    present.entries.firstNotNullOfOrNull { (position, cell) -> cellProblem(rowNumber, position, cell) }
                }
            }
        if (problem != null) throw SheetStop(problem)
        if (present.isEmpty()) return
        rows++
        sink.row(SheetRow(table, rowNumber, present.mapKeys { columns[it.key].orEmpty() }))
    }

    /** What is wrong with one value: no header above it, or too long for a cell. */
    private fun cellProblem(
        rowNumber: Int,
        position: Int,
        cell: SheetCell,
    ): SheetProblem? {
        val column = columns.getOrNull(position)
        return when {
            column == null -> {
                headerless(rowNumber, position)
            }

            cell.text.length > limits.maxCellChars -> {
                SheetProblem(SheetProblemCode.VALUE_TOO_LONG_FOR_FILE, table, rowNumber, column, mapOf("limit" to limits.maxCellChars))
            }

            else -> {
                null
            }
        }
    }

    /** A value in a column without a header: past the header's end, or beyond the column cap. */
    private fun headerless(
        rowNumber: Int,
        position: Int,
    ): SheetProblem =
        if (position >= limits.maxColumns) {
            SheetProblem(SheetProblemCode.TOO_MANY_COLUMNS, table, rowNumber, params = mapOf("limit" to limits.maxColumns))
        } else {
            SheetProblem(SheetProblemCode.HEADER_MISSING, table, rowNumber, params = mapOf("position" to position + 1))
        }

    private fun clip(name: String): String = name.trim().take(MAX_COLUMN_NAME).ifEmpty { "?" }

    private companion object {
        const val MAX_COLUMN_NAME = 200
    }
}
