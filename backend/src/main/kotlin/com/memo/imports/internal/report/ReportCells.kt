package com.memo.imports.internal.report

import com.memo.common.poi.SheetCells
import org.apache.poi.ss.usermodel.Sheet

/**
 * Cells of the report's workbook (design E06 §8, threat model I3): every value is a string cell with the injection prefix
 * where a spreadsheet could read it as a formula or a command, cut at the cell limit ([SheetCells]).
 */
object ReportCells {
    const val MAX_CHARS = SheetCells.MAX_CHARS

    /** [value] as it is written: prefixed when it could be read as a formula, and cut to the cell limit. */
    fun safe(value: String): String = SheetCells.safe(value)

    /** Appends a row of string cells at [index] (values null as empty cells). */
    fun row(
        sheet: Sheet,
        index: Int,
        values: List<Any?>,
    ) = SheetCells.row(sheet, index, values)
}
