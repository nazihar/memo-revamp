package com.memo.imports.internal.sheet

import com.memo.testsupport.Formula
import com.memo.testsupport.ImportSheets
import com.memo.testsupport.ImportSheets.FILE_HEADER
import com.memo.testsupport.ImportSheets.MEMO_HEADER
import com.memo.testsupport.ImportSheets.file
import com.memo.testsupport.ImportSheets.memo
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * The workbook reader (ADR-0055, design E06 §6.1, §7; test plan E06 T5 `XlsxSheetReaderTest`): header matching, sheets by
 * name, dates in both date systems and nothing guessed, cached formulas, numbers held as numbers, every cap, and hostile
 * packages ending as batch problems.
 */
class XlsxSheetReaderTest {
    private fun read(
        bytes: ByteArray,
        limits: SheetLimits = SheetLimits(),
        deadline: ReadDeadline = ReadDeadline.NONE,
    ): Pair<SheetResult, List<SheetRow>> {
        val rows = mutableListOf<SheetRow>()
        return XlsxSheetReader(limits, deadline).read(bytes.inputStream(), rows::add) to rows
    }

    private fun codes(result: SheetResult) = result.problems.map { it.code }

    private fun files() = listOf(FILE_HEADER, file("CR/2019/0001", "2019/1/memo.pdf"))

    @Test
    fun `headers match without regard to case, spaces, dashes and underscores, and rows keep their sheet row numbers`() {
        val header =
            listOf("Legacy number", "MEMO-TYPE", "subject", " Status ", "origin unit", "raised_on", "Initiator Name", "field:amount")
        val (result, rows) =
            read(
                ImportSheets.workbook(
                    mapOf(
                        "Instructions" to listOf(listOf("Read me")),
                        "memos" to
                            listOf(
                                header,
                                listOf("CR/2019/0001", "GEN", "  Rent  ", "Approved", "HO", "2019-03-14", "A. Rahman", "1200"),
                                listOf(null, null, null),
                                listOf("CR/2019/0002", "GEN", "Lease", "Rejected", "HO", "2019-03-15", "B. Karim"),
                            ),
                        "Files" to files(),
                        "Lists" to listOf(listOf("APPROVED")),
                    ),
                ),
            )

        assertThat(result.problems).isEmpty()
        assertThat(result.rows).containsEntry(SheetTable.MEMOS, 2).containsEntry(SheetTable.FILES, 1)
        val memos = rows.filter { it.table == SheetTable.MEMOS }
        assertThat(memos.map { it.rowNumber }).containsExactly(2, 4)
        assertThat(memos[0].cells.mapValues { it.value.text })
            .containsEntry("legacy_number", "CR/2019/0001")
            .containsEntry("memo_type", "GEN")
            .containsEntry("subject", "Rent")
            .containsEntry("status", "Approved")
            .containsEntry("origin_unit", "HO")
            .containsEntry("initiator_name", "A. Rahman")
            .containsEntry("field:amount", "1200")
        // ISO text stays text: nothing is guessed from it.
        assertThat(memos[0].cells.getValue("raised_on")).isEqualTo(SheetCell("2019-03-14"))
    }

    @Test
    fun `unknown, duplicate and missing columns are batch problems that name the column`() {
        val header = listOf("legacy_number", "Legacy Number", "subject", "colour", "field:", "status")
        val (result, rows) = read(ImportSheets.workbook(mapOf("Memos" to listOf(header, memo("CR/1")), "Files" to files())))

        assertThat(rows.filter { it.table == SheetTable.MEMOS }).isEmpty()
        assertThat(result.problems.map { it.code to it.column })
            .contains(
                SheetProblemCode.COLUMN_DUPLICATE to "legacy_number",
                SheetProblemCode.COLUMN_UNKNOWN to "colour",
                SheetProblemCode.COLUMN_UNKNOWN to "field:",
                SheetProblemCode.COLUMN_REQUIRED to "memo_type",
                SheetProblemCode.COLUMN_REQUIRED to "origin_unit",
                SheetProblemCode.COLUMN_REQUIRED to "raised_on",
                SheetProblemCode.COLUMN_REQUIRED to "initiator_name",
            )
        assertThat(result.problems).allMatch { it.table == SheetTable.MEMOS && it.rowNumber == 1 }
    }

    @Test
    fun `sheets are found by name, others are refused, and Memos and Files are required`() {
        val (unknown, _) =
            read(ImportSheets.workbook(mapOf("Memos" to listOf(MEMO_HEADER), "Files" to files(), "Notes" to listOf(listOf("x")))))
        assertThat(unknown.problems.single()).isEqualTo(SheetProblem(SheetProblemCode.SHEET_UNKNOWN, params = mapOf("sheet" to "Notes")))

        val (missing, _) = read(ImportSheets.workbook(mapOf("Memos" to listOf(MEMO_HEADER))))
        assertThat(missing.problems.single()).isEqualTo(SheetProblem(SheetProblemCode.SHEET_MISSING, SheetTable.FILES))

        val (empty, _) = read(ImportSheets.workbook(mapOf("Memos" to emptyList(), "Files" to files())))
        assertThat(codes(empty)).containsExactly(SheetProblemCode.HEADER_MISSING)
    }

    @Test
    fun `date cells read in the workbook's own date system, numbers as plain digits`() {
        for (date1904 in listOf(false, true)) {
            val row =
                listOf(
                    1234.0,
                    "GEN",
                    "Rent",
                    "Approved",
                    "",
                    "HO",
                    LocalDate.of(2019, 3, 14),
                    LocalDateTime.of(2019, 3, 20, 10, 30),
                    "",
                    "A. Rahman",
                )
            val (result, rows) =
                read(ImportSheets.workbook(mapOf("Memos" to listOf(MEMO_HEADER, row), "Files" to files()), date1904 = date1904))

            assertThat(result.problems).isEmpty()
            val cells = rows.first { it.table == SheetTable.MEMOS }.cells
            assertThat(cells.getValue("raised_on")).describedAs("1904: $date1904").isEqualTo(SheetCell("2019-03-14", CellKind.DATE))
            assertThat(cells.getValue("decided_on")).isEqualTo(SheetCell("2019-03-20T10:30", CellKind.DATE))
            assertThat(cells.getValue("legacy_number")).isEqualTo(SheetCell("1234", CellKind.NUMBER))
        }
    }

    @Test
    fun `a formula gives its cached value, and one without a cached value is kept as such`() {
        val row = listOf(Formula("\"CR/\"&\"1\"", 0.0), "GEN", "Rent", "Approved", "", "HO", "2019-03-14", "", "", Formula("1+1"), "", "")
        val (result, rows) = read(ImportSheets.workbook(mapOf("Memos" to listOf(MEMO_HEADER, row), "Files" to files())))

        assertThat(result.problems).isEmpty()
        val cells = rows.first { it.table == SheetTable.MEMOS }.cells
        assertThat(cells.getValue("legacy_number")).isEqualTo(SheetCell("CR/1", CellKind.TEXT, FormulaState.CACHED))
        assertThat(cells.getValue("initiator_name")).isEqualTo(SheetCell("", CellKind.TEXT, FormulaState.NO_VALUE))
    }

    @Test
    fun `rows, columns, value length and size are capped`() {
        val many = ImportSheets.workbook(mapOf("Memos" to listOf(MEMO_HEADER) + (1..4).map { memo("CR/$it") }, "Files" to files()))
        val (rows, _) = read(many, SheetLimits(maxRows = SheetLimits.MAX_ROWS + (SheetTable.MEMOS to 3)))
        assertThat(rows.problems.single())
            .isEqualTo(SheetProblem(SheetProblemCode.TOO_MANY_ROWS, SheetTable.MEMOS, 5, params = mapOf("limit" to 3)))

        val (columns, _) = read(ImportSheets.good(), SheetLimits(maxColumns = 5))
        assertThat(codes(columns)).isNotEmpty().containsOnly(SheetProblemCode.TOO_MANY_COLUMNS)

        val long = ImportSheets.workbook(mapOf("Memos" to listOf(MEMO_HEADER, memo("CR/1", "x".repeat(11))), "Files" to files()))
        val (value, _) = read(long, SheetLimits(maxCellChars = 10))
        assertThat(value.problems.single())
            .isEqualTo(SheetProblem(SheetProblemCode.VALUE_TOO_LONG_FOR_FILE, SheetTable.MEMOS, 2, "subject", mapOf("limit" to 10)))

        val (size, _) = read(ImportSheets.good(), SheetLimits(maxBytes = 100))
        assertThat(codes(size)).containsExactly(SheetProblemCode.MALFORMED)
    }

    @Test
    fun `a value in a column without a header is refused`() {
        val (result, _) =
            read(ImportSheets.workbook(mapOf("Memos" to listOf(MEMO_HEADER, memo("CR/1") + listOf("", "stray")), "Files" to files())))
        assertThat(result.problems.single())
            .isEqualTo(SheetProblem(SheetProblemCode.HEADER_MISSING, SheetTable.MEMOS, 2, params = mapOf("position" to 14)))
    }

    @Test
    fun `hostile packages end as batch problems and nothing is resolved`() {
        val good = ImportSheets.good()
        val hostile =
            mapOf(
                "DTD in the shared strings" to ImportSheets.withDoctype(good, "xl/sharedStrings.xml", "sst"),
                "DTD in a sheet" to ImportSheets.withDoctype(good, "xl/worksheets/sheet1.xml", "worksheet"),
                "zip bomb" to ImportSheets.bomb(),
                "not a zip" to "legacy_number,subject\n".toByteArray(),
                "empty" to ByteArray(0),
                "truncated" to good.copyOf(good.size / 2),
            )
        hostile.forEach { (case, bytes) ->
            val (result, rows) = read(bytes)
            assertThat(codes(result)).describedAs(case).containsExactly(SheetProblemCode.MALFORMED)
            assertThat(rows.flatMap { it.cells.values }.map { it.text }).describedAs(case).noneMatch { it.contains("localhost") }
        }
    }

    @Test
    fun `a read whose deadline passed stops as a timeout`() {
        val (result, rows) = read(ImportSheets.good(), deadline = { true })
        assertThat(codes(result)).containsExactly(SheetProblemCode.PARSE_TIMEOUT)
        assertThat(rows).isEmpty()
    }

    @Test
    fun `the tenant template's own sample rows read back`() {
        val (result, rows) = read(ImportSheets.good(3))
        assertThat(result.problems).isEmpty()
        assertThat(rows.groupingBy { it.table }.eachCount()).containsEntry(SheetTable.MEMOS, 3).containsEntry(SheetTable.FILES, 3)
    }
}
