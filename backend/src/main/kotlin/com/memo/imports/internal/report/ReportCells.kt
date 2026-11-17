package com.memo.imports.internal.report

import org.apache.poi.ss.usermodel.Sheet

/**
 * Cells of the report's workbook (design E06 §8, threat model I3): every value is a string cell, and one that a
 * spreadsheet could read as a formula or a command (starting with `=`, `+`, `-`, `@`, a tab, CR or LF, or the full-width
 * forms of the first four) gets the injection prefix `'` (OWASP). Values are cut at the cell limit of 32,767 characters.
 */
object ReportCells {
    const val MAX_CHARS = 32_767
    private const val PREFIX = '\''
    private val TRIGGERS = setOf('=', '+', '-', '@', '\t', '\r', '\n', '＝', '＋', '－', '＠')

    /** [value] as it is written: prefixed when it could be read as a formula, and cut to the cell limit. */
    fun safe(value: String): String {
        val text = if (value.firstOrNull() in TRIGGERS) PREFIX + value else value
        return text.take(MAX_CHARS)
    }

    /** Appends a row of string cells at [index] (values null as empty cells). */
    fun row(
        sheet: Sheet,
        index: Int,
        values: List<Any?>,
    ) {
        val row = sheet.createRow(index)
        values.forEachIndexed { column, value ->
            if (value != null) row.createCell(column).setCellValue(safe(value.toString()))
        }
    }
}
