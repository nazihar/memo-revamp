package com.memo.imports.internal

import com.memo.common.authz.Clearances
import com.memo.common.poi.PoiLimits
import com.memo.imports.internal.sheet.SheetTable
import com.memo.memo.LegacyMemos
import com.memo.memo.LegacyStatus
import com.memo.org.OrgUnitDirectory
import org.apache.poi.ss.usermodel.DataValidation
import org.apache.poi.ss.usermodel.Sheet
import org.apache.poi.ss.util.CellRangeAddressList
import org.apache.poi.xssf.usermodel.XSSFCellStyle
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.springframework.stereotype.Component
import java.io.ByteArrayOutputStream

/**
 * The tenant's import template (ADR-0055, design E06 §7): an .xlsx with Instructions, Memos, Files, Approvals and Lists.
 * Every column of the three tables is formatted as Text, so Excel keeps leading zeros and dates as typed; status, type,
 * classification and unit offer the tenant's values as warning lists (legacy values can still be pasted, the mappings
 * handle them). Each table has a sample row marked "delete me". Every value is a string cell: nothing is a formula.
 * Inside a tenant transaction (it reads the tenant's types, levels and units).
 */
@Component
class ImportTemplate(
    private val legacy: LegacyMemos,
    private val clearances: Clearances,
    private val units: OrgUnitDirectory,
) {
    fun build(): ByteArray {
        PoiLimits.apply()
        val types = legacy.types().sortedBy { it.code }
        val lists =
            linkedMapOf(
                "status" to (LegacyStatus.entries.map { it.name } + NOT_IMPORTED),
                "memo_type" to types.map { it.code },
                "classification" to clearances.levels().map { it.name },
                "origin_unit" to
                    units
                        .units(units.allUnits())
                        .values
                        .map { it.code }
                        .sorted(),
                "field_keys" to types.flatMap { it.fieldKeys }.distinct().sorted(),
            )
        return XSSFWorkbook().use { workbook ->
            val text = workbook.createCellStyle().apply { dataFormat = workbook.createDataFormat().getFormat("@") }
            instructions(workbook.createSheet(INSTRUCTIONS))
            SheetTable.entries.forEach { table(workbook, it, text) }
            listSheet(workbook.createSheet(LISTS), lists)
            validations(workbook.getSheet(SheetTable.MEMOS.sheetName), lists)
            ByteArrayOutputStream().also(workbook::write).toByteArray()
        }
    }

    private fun instructions(sheet: Sheet) {
        val required =
            SheetTable.entries.map { t ->
                "Required on ${t.sheetName}: " +
                    t.columns.filter { it.required }.joinToString { it.name }
            }
        (INSTRUCTION_LINES + required).forEachIndexed { i, line -> sheet.createRow(i).createCell(0).setCellValue(line) }
        sheet.setColumnWidth(0, INSTRUCTIONS_WIDTH)
    }

    private fun table(
        workbook: XSSFWorkbook,
        table: SheetTable,
        text: XSSFCellStyle,
    ) {
        val sheet = workbook.createSheet(table.sheetName)
        val header = sheet.createRow(0)
        val sample = sheet.createRow(1)
        table.columns.forEachIndexed { i, column ->
            sheet.setDefaultColumnStyle(i, text)
            sheet.setColumnWidth(i, COLUMN_WIDTH)
            header.createCell(i).apply {
                setCellValue(column.name)
                cellStyle = text
            }
            sample.createCell(i).apply {
                setCellValue(SAMPLES.getValue(table)[i])
                cellStyle = text
            }
        }
        sheet.createFreezePane(0, 1)
    }

    private fun listSheet(
        sheet: Sheet,
        lists: Map<String, List<String>>,
    ) {
        val rows = (lists.values.maxOfOrNull { it.size } ?: 0) + 1
        repeat(rows) { sheet.createRow(it) }
        lists.entries.forEachIndexed { column, (name, values) ->
            sheet.getRow(0).createCell(column).setCellValue(name)
            values.forEachIndexed { i, value -> sheet.getRow(i + 1).createCell(column).setCellValue(value) }
            sheet.setColumnWidth(column, COLUMN_WIDTH)
        }
    }

    /** Warning lists on the Memos sheet, pointing at the Lists sheet's columns. */
    private fun validations(
        memos: Sheet,
        lists: Map<String, List<String>>,
    ) {
        val helper = memos.dataValidationHelper
        val columns = SheetTable.MEMOS.columns.map { it.name }
        lists.keys.toList().forEachIndexed { listColumn, name ->
            val target = columns.indexOf(name)
            val size = lists.getValue(name).size
            if (target < 0 || size == 0) return@forEachIndexed
            val letter = ('A' + listColumn).toString()
            val constraint = helper.createFormulaListConstraint("'$LISTS'!\$$letter\$2:\$$letter\$${size + 1}")
            val validation = helper.createValidation(constraint, CellRangeAddressList(1, MAX_TEMPLATE_ROWS, target, target))
            validation.errorStyle = DataValidation.ErrorStyle.WARNING
            validation.showErrorBox = true
            validation.createErrorBox("Not in the list", "This value is not one of this system's. It will need a mapping.")
            memos.addValidationData(validation)
        }
    }

    private companion object {
        const val INSTRUCTIONS = "Instructions"
        const val LISTS = "Lists"
        const val NOT_IMPORTED = "NOT_IMPORTED"
        const val COLUMN_WIDTH = 20 * 256
        const val INSTRUCTIONS_WIDTH = 120 * 256
        const val MAX_TEMPLATE_ROWS = 50_000
        const val DELETE_ME = "(sample row: delete me)"

        val INSTRUCTION_LINES =
            listOf(
                "Legacy import template",
                "",
                "Fill one row per memo on Memos, one row per original file on Files, and one row per legacy decision on Approvals.",
                "Keep each header row as it is; you may leave out optional columns. The required columns are listed below.",
                "Every column is formatted as Text: type numbers and dates as they appear, dates as yyyy-mm-dd.",
                "Never open a legacy CSV export in Excel before uploading it: Excel removes leading zeros and changes dates.",
                "Each memo needs exactly one Files row with role MAIN; the others are ATTACHMENT.",
                "The path of a file is its path inside the folder you will upload, with / between folders.",
                "Extract any archive on your computer first, then choose the extracted folder in the browser.",
                "Lists shows this system's statuses, memo type codes, classification levels, unit codes and type field keys.",
                "A value not in a list is kept as given and must be mapped to one of ours before the import.",
                "Add a column named field:<key> on Memos for a memo type's field (keys are on Lists).",
                "Delete the sample rows before uploading.",
            )

        val SAMPLES: Map<SheetTable, List<String>> =
            mapOf(
                SheetTable.MEMOS to
                    listOf(
                        "CR/2019/0001",
                        "GEN",
                        DELETE_ME,
                        "APPROVED",
                        "",
                        "HO",
                        "2019-03-14",
                        "2019-03-20",
                        "",
                        "A. Rahman",
                        "a.rahman@example.com",
                        "",
                    ),
                SheetTable.FILES to listOf("CR/2019/0001", "2019/CR-0001/memo.pdf", "MAIN", DELETE_ME, "", ""),
                SheetTable.APPROVALS to
                    listOf("CR/2019/0001", "1", "B. Karim", "", "", "Head of Credit", "Approved", "2019-03-20", DELETE_ME),
            )
    }
}
