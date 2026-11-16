package com.memo.testsupport

import org.apache.poi.ss.usermodel.Cell
import org.apache.poi.ss.usermodel.CellStyle
import org.apache.poi.ss.usermodel.DateUtil
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** A formula cell: [formula], and its cached value when [cached] is set (written as Excel would after a calculation). */
data class Formula(
    val formula: String,
    val cached: Double? = null,
)

/** Legacy import metadata for tests (ADR-0055): workbooks written with POI, CSV text, and hostile variants. */
object ImportSheets {
    val MEMO_HEADER =
        listOf(
            "legacy_number",
            "memo_type",
            "subject",
            "status",
            "classification",
            "origin_unit",
            "raised_on",
            "decided_on",
            "closed_on",
            "initiator_name",
            "initiator_email",
            "initiator_id",
        )
    val FILE_HEADER = listOf("legacy_number", "path", "role", "title", "sha256", "classification")
    val APPROVAL_HEADER = listOf("legacy_number", "order", "name", "email", "legacy_id", "position", "decision", "decided_on", "remark")

    fun memo(
        number: String,
        subject: String = "Rent of the branch at plot 7",
    ): List<Any?> = listOf(number, "GEN", subject, "Approved", "Internal", "HO", "2019-03-14", "2019-03-20", "", "A. Rahman", "", "")

    fun file(
        number: String,
        path: String,
        role: String = "MAIN",
    ): List<Any?> = listOf(number, path, role, "Memo", "", "")

    /** A workbook with [sheets] (name to rows, the first row the header), in the 1904 date system when [date1904]. */
    fun workbook(
        sheets: Map<String, List<List<Any?>>>,
        date1904: Boolean = false,
    ): ByteArray =
        XSSFWorkbook().use { workbook ->
            workbook.ctWorkbook.addNewWorkbookPr().date1904 = date1904
            val writer = CellWriter(workbook, date1904)
            sheets.forEach { (name, rows) ->
                val sheet = workbook.createSheet(name)
                rows.forEachIndexed { r, values ->
                    val row = sheet.createRow(r)
                    values.forEachIndexed { c, value -> if (value != null) writer.write(row.createCell(c), value) }
                }
            }
            ByteArrayOutputStream().also(workbook::write).toByteArray()
        }

    /** Writes a test value as Excel would hold it: text, a number, a date cell, or a formula (calculated when cached). */
    private class CellWriter(
        workbook: XSSFWorkbook,
        private val date1904: Boolean,
    ) {
        private val dates = workbook.createCellStyle().apply { dataFormat = workbook.createDataFormat().getFormat("dd/mm/yyyy") }
        private val times = workbook.createCellStyle().apply { dataFormat = workbook.createDataFormat().getFormat("dd/mm/yyyy hh:mm") }
        private val evaluator = workbook.creationHelper.createFormulaEvaluator()

        fun write(
            cell: Cell,
            value: Any,
        ) {
            when (value) {
                is String -> {
                    cell.setCellValue(value)
                }

                is Number -> {
                    cell.setCellValue(value.toDouble())
                }

                is LocalDate -> {
                    date(cell, DateUtil.getExcelDate(value.atStartOfDay(), date1904), dates)
                }

                is LocalDateTime -> {
                    date(cell, DateUtil.getExcelDate(value, date1904), times)
                }

                is Formula -> {
                    cell.cellFormula = value.formula
                    if (value.cached != null) evaluator.evaluateFormulaCell(cell)
                }

                else -> {
                    error("unsupported cell $value")
                }
            }
        }

        private fun date(
            cell: Cell,
            serial: Double,
            style: CellStyle,
        ) {
            cell.setCellValue(serial)
            cell.cellStyle = style
        }
    }

    /** A workbook with good Memos and Files sheets of [memos] rows. */
    fun good(memos: Int = 2): ByteArray =
        workbook(
            mapOf(
                "Memos" to listOf(MEMO_HEADER) + (1..memos).map { memo("CR/2019/%04d".format(it)) },
                "Files" to listOf(FILE_HEADER) + (1..memos).map { file("CR/2019/%04d".format(it), "2019/$it/memo.pdf") },
            ),
        )

    /** [csv] with CRLF line ends, as Excel writes it. */
    fun csv(vararg lines: String): ByteArray = lines.joinToString("\r\n", postfix = "\r\n").toByteArray(StandardCharsets.UTF_8)

    /** [zip] with [entry] rewritten by [change] (to plant a DTD, an entity or a bomb). */
    fun rewrite(
        zip: ByteArray,
        entry: String,
        change: (String) -> String,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        ZipInputStream(ByteArrayInputStream(zip)).use { input ->
            ZipOutputStream(out).use { output ->
                generateSequence { input.nextEntry }.forEach { e ->
                    val bytes = input.readAllBytes()
                    output.putNextEntry(ZipEntry(e.name))
                    output.write(if (e.name == entry) change(String(bytes, StandardCharsets.UTF_8)).toByteArray() else bytes)
                    output.closeEntry()
                }
            }
        }
        return out.toByteArray()
    }

    /** A DOCTYPE with an external entity naming a local file, planted in [entry] (a sheet or the shared strings). */
    fun withDoctype(
        workbook: ByteArray,
        entry: String,
        root: String,
    ): ByteArray =
        rewrite(workbook, entry) { xml ->
            xml.replaceFirst("<$root", "<!DOCTYPE $root [<!ENTITY x SYSTEM \"file:///etc/hostname\">]><$root")
        }

    /** A workbook whose sheet inflates far beyond its compressed size (a zip bomb inside a valid package). */
    fun bomb(): ByteArray =
        rewrite(good(1), "xl/worksheets/sheet1.xml") { xml ->
            xml.replace("</sheetData>", "</sheetData>" + "<!--" + " ".repeat(BOMB_CHARS) + "-->")
        }

    private const val BOMB_CHARS = 10 shl 20
}
