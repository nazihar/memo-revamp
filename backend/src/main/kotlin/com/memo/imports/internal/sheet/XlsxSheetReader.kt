package com.memo.imports.internal.sheet

import com.memo.common.poi.PoiLimits
import org.apache.poi.openxml4j.opc.OPCPackage
import org.apache.poi.ss.usermodel.Comment
import org.apache.poi.ss.usermodel.DataFormatter
import org.apache.poi.ss.usermodel.DateUtil
import org.apache.poi.ss.util.CellReference
import org.apache.poi.ss.util.NumberToTextConverter
import org.apache.poi.util.XMLHelper
import org.apache.poi.xssf.eventusermodel.ReadOnlySharedStringsTable
import org.apache.poi.xssf.eventusermodel.XSSFReader
import org.apache.poi.xssf.eventusermodel.XSSFSheetXMLHandler
import org.apache.poi.xssf.model.StylesTable
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.helpers.DefaultHandler
import org.xml.sax.helpers.XMLFilterImpl
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * Reads a metadata workbook (ADR-0055, design E06 §7) with POI's streaming API: `XSSFReader`, the read-only shared
 * strings and the styles, one SAX pass per sheet. Sheets are found by name (Memos, Files, Approvals; Instructions and
 * Lists skipped); dates are read in the workbook's own date system (POI's handler assumes 1900, T1 spike); a formula gives
 * its cached value, and a formula without one is kept as such. POI's XML reader refuses any DTD. Anything that goes wrong
 * inside POI is `MALFORMED`: no exception leaves [read].
 */
class XlsxSheetReader(
    private val limits: SheetLimits = SheetLimits(),
    private val deadline: ReadDeadline = ReadDeadline.NONE,
) {
    init {
        PoiLimits.apply()
    }

    fun read(
        input: InputStream,
        sink: RowSink,
    ): SheetResult =
        SheetReads.guarded {
            val bytes = SheetReads.bounded(input, limits.maxBytes)
            OPCPackage.open(ByteArrayInputStream(bytes)).use { pkg -> sheets(pkg, sink) }
        }

    private fun sheets(
        pkg: OPCPackage,
        sink: RowSink,
    ): SheetResult {
        val reader = XSSFReader(pkg)
        val date1904 = reader.workbookData.use(::isDate1904)
        val strings = ReadOnlySharedStringsTable(pkg, false)
        val styles = reader.stylesTable
        val problems = mutableListOf<SheetProblem>()
        val rows = linkedMapOf<SheetTable, Int>()
        val sheets = reader.sheetIterator
        while (sheets.hasNext()) {
            sheets.next().use { stream ->
                val name = sheets.sheetName.orEmpty()
                val table = SheetTable.ofSheet(name)
                when {
                    table == null && name.trim().lowercase(Locale.ROOT) in SheetTable.IGNORED_SHEETS -> Unit
                    table == null || table in rows -> problems += unknownSheet(name)
                    else -> rows[table] = sheet(stream, table, Parts(styles, strings, date1904), sink, problems)
                }
            }
        }
        SheetTable.entries.filter { it.required && it !in rows }.forEach { problems += SheetProblem(SheetProblemCode.SHEET_MISSING, it) }
        return SheetResult(problems, rows)
    }

    private fun unknownSheet(name: String) =
        SheetProblem(SheetProblemCode.SHEET_UNKNOWN, params = mapOf("sheet" to name.trim().take(MAX_SHEET_NAME).ifEmpty { "?" }))

    /** One sheet; its header problems join [problems], and a stop inside it ends the whole read. */
    private fun sheet(
        stream: InputStream,
        table: SheetTable,
        parts: Parts,
        sink: RowSink,
        problems: MutableList<SheetProblem>,
    ): Int {
        val collector = TableCollector(table, limits, sink, deadline)
        val formatter = SheetFormatter(parts.date1904)
        val rows = SheetRows(table, collector, formatter)
        val tracker = CellTracker(rows, formatter, deadline, table)
        tracker.contentHandler = XSSFSheetXMLHandler(parts.styles, null, parts.strings, rows, formatter, false)
        val xml = XMLHelper.newXMLReader()
        xml.contentHandler = tracker
        val refused =
            try {
                xml.parse(InputSource(stream))
                null
            } catch (refused: HeaderRefused) {
                refused
            } catch (failure: SAXException) {
                headerOf(failure)
            }
        if (refused != null) problems += refused.problems
        if (refused == null && !rows.headerSeen) problems += SheetProblem(SheetProblemCode.HEADER_MISSING, table, 1)
        return if (refused == null) collector.rows else 0
    }

    /** The header's refusal behind [failure] (a SAX parser may wrap it); anything else goes on up. */
    private fun headerOf(failure: Exception): HeaderRefused = SheetReads.causeOf<HeaderRefused>(failure) ?: throw failure

    private class Parts(
        val styles: StylesTable,
        val strings: ReadOnlySharedStringsTable,
        val date1904: Boolean,
    )

    /** The sheet's rows to [collector]: the first row with a value is the header, the rest are data. */
    private class SheetRows(
        private val table: SheetTable,
        private val collector: TableCollector,
        private val formatter: SheetFormatter,
    ) : XSSFSheetXMLHandler.SheetContentsHandler {
        var headerSeen = false
            private set
        private var cells = sortedMapOf<Int, SheetCell>()
        private var position = 0

        /** The type attribute (`t`) and formula state of the cell being read, set by [CellTracker]. */
        var cellType: String? = null
        var cellFormula = false

        override fun startRow(rowNum: Int) {
            cells = sortedMapOf()
            position = 0
        }

        override fun endRow(rowNum: Int) {
            if (headerSeen) {
                collector.row(rowNum + 1, cells)
                return
            }
            if (cells.values.all { it.text.isEmpty() }) return
            headerSeen = true
            val width = cells.filterValues { it.text.isNotEmpty() }.keys.max() + 1
            if (width > MAX_HEADER_WIDTH) throw HeaderRefused(listOf(tooManyColumns()))
            val problems = collector.header((0 until width).map { cells[it]?.text.orEmpty() })
            if (problems.isNotEmpty()) throw HeaderRefused(problems)
        }

        override fun cell(
            cellReference: String?,
            formattedValue: String?,
            comment: Comment?,
        ) {
            val column = cellReference?.let { CellReference(it).col.toInt() } ?: position
            position = column + 1
            if (column > MAX_HEADER_WIDTH) throw SheetStop(tooManyColumns())
            val numeric = cellType == null || cellType == "n"
            val kind =
                when {
                    numeric && formatter.lastDate -> CellKind.DATE
                    numeric -> CellKind.NUMBER
                    else -> CellKind.TEXT
                }
            val formula = if (cellFormula) FormulaState.CACHED else FormulaState.NONE
            cells[column] = SheetCell(formattedValue.orEmpty().trim(), kind, formula)
        }

        /** A formula cell without a cached value: POI gives no cell at all, so the tracker reports it. */
        fun noValue(cellReference: String?) {
            val column = cellReference?.let { CellReference(it).col.toInt() } ?: position
            position = column + 1
            if (column <= MAX_HEADER_WIDTH) cells[column] = SheetCell("", CellKind.TEXT, FormulaState.NO_VALUE)
        }

        private fun tooManyColumns() =
            SheetProblem(SheetProblemCode.TOO_MANY_COLUMNS, table, params = mapOf("limit" to SheetLimits.MAX_COLUMNS))
    }

    /** Watches each `<c>` for its type, a formula and a value, which POI's handler does not report. */
    private class CellTracker(
        private val rows: SheetRows,
        private val formatter: SheetFormatter,
        private val deadline: ReadDeadline,
        private val table: SheetTable,
    ) : XMLFilterImpl() {
        private var reference: String? = null
        private var hasValue = false

        override fun startElement(
            uri: String?,
            localName: String,
            qName: String?,
            atts: Attributes,
        ) {
            when (localName) {
                "row" -> {
                    if (deadline.passed()) throw SheetStop(SheetProblem(SheetProblemCode.PARSE_TIMEOUT, table))
                }

                "c" -> {
                    reference = atts.getValue("r")
                    rows.cellType = atts.getValue("t")
                    rows.cellFormula = false
                    hasValue = false
                    formatter.lastDate = false
                }

                "f" -> {
                    rows.cellFormula = true
                }

                "v", "is" -> {
                    hasValue = true
                }
            }
            super.startElement(uri, localName, qName, atts)
        }

        override fun endElement(
            uri: String?,
            localName: String,
            qName: String?,
        ) {
            super.endElement(uri, localName, qName)
            if (localName == "c" && rows.cellFormula && !hasValue) rows.noValue(reference)
        }
    }

    /**
     * Formats numbers as plain decimals and date cells as ISO 8601 in the workbook's date system ([date1904]); POI's
     * handler would apply the cell's display format and the 1900 system.
     */
    private class SheetFormatter(
        private val date1904: Boolean,
    ) : DataFormatter(Locale.ROOT) {
        var lastDate = false

        override fun formatRawCellContents(
            value: Double,
            formatIndex: Int,
            formatString: String?,
        ): String {
            val date =
                if (DateUtil.isADateFormat(formatIndex, formatString) && DateUtil.isValidExcelDate(value)) {
                    DateUtil.getLocalDateTime(value, date1904)
                } else {
                    null
                }
            lastDate = date != null
            return date?.let(::iso) ?: NumberToTextConverter.toText(value)
        }

        private fun iso(at: LocalDateTime): String {
            val seconds = at.truncatedTo(ChronoUnit.SECONDS)
            return if (seconds.toLocalTime().toSecondOfDay() == 0) seconds.toLocalDate().toString() else seconds.toString()
        }
    }

    /** A sheet's header has problems: its rows are not read. */
    private class HeaderRefused(
        val problems: List<SheetProblem>,
    ) : RuntimeException("header refused", null, false, false)

    private companion object {
        const val MAX_SHEET_NAME = 100

        /** Columns past this are refused before anything is kept for them (a cell reference can name column XFD). */
        const val MAX_HEADER_WIDTH = SheetLimits.MAX_COLUMNS

        /** `date1904` on `workbookPr` in `workbook.xml`: the workbook counts days from 1904 (old Mac Excel). */
        fun isDate1904(workbook: InputStream): Boolean {
            var date1904 = false
            val xml = XMLHelper.newXMLReader()
            xml.contentHandler =
                object : DefaultHandler() {
                    override fun startElement(
                        uri: String?,
                        localName: String,
                        qName: String?,
                        attributes: Attributes,
                    ) {
                        if (localName == "workbookPr") {
                            date1904 = attributes.getValue("date1904")?.let { it == "1" || it.equals("true", ignoreCase = true) } == true
                        }
                    }
                }
            xml.parse(InputSource(workbook))
            return date1904
        }
    }
}
