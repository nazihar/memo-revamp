package com.memo.imports.internal.sheet

import com.memo.testsupport.ImportSheets.csv
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

/**
 * The CSV reader (ADR-0055, design E06 §7; test plan E06 T5 `CsvSheetReaderTest`): the strict profile, header matching,
 * the delimiter from the header, UTF-8 only with or without its BOM, every cap, and malformed files ending as batch
 * problems.
 */
class CsvSheetReaderTest {
    private val memosHeader = "legacy_number,memo_type,subject,status,origin_unit,raised_on,initiator_name"

    private fun read(
        bytes: ByteArray,
        table: SheetTable = SheetTable.MEMOS,
        limits: SheetLimits = SheetLimits(),
    ): Pair<SheetResult, List<SheetRow>> {
        val rows = mutableListOf<SheetRow>()
        return CsvSheetReader(limits).read(table, bytes.inputStream(), rows::add) to rows
    }

    private fun codes(bytes: ByteArray) = read(bytes).first.problems.map { it.code }

    @Test
    fun `rows are read against the header, trimmed, with quoting, and numbered as records`() {
        val (result, rows) =
            read(
                csv(
                    "Legacy Number,memo-type,Subject,STATUS,origin unit,raised_on,initiator_name,field:amount",
                    "CR/2019/0001,GEN,\"Rent, floors 3 and 4\",Approved,HO,2019-03-14,A. Rahman,1200",
                    "",
                    "  CR/2019/0002 ,GEN,\"A \"\"quoted\"\" word\nover two lines\",Rejected,HO,2019-03-15,B. Karim,",
                    ",,,,,,,",
                ),
            )

        assertThat(result.problems).isEmpty()
        assertThat(result.rows).containsExactlyEntriesOf(mapOf(SheetTable.MEMOS to 2))
        assertThat(rows.map { it.rowNumber }).containsExactly(2, 3)
        assertThat(rows[0].cells.mapValues { it.value.text })
            .containsEntry("legacy_number", "CR/2019/0001")
            .containsEntry("subject", "Rent, floors 3 and 4")
            .containsEntry("field:amount", "1200")
        assertThat(rows[1].cells.getValue("legacy_number")).isEqualTo(SheetCell("CR/2019/0002"))
        assertThat(rows[1].cells.getValue("subject").text).isEqualTo("A \"quoted\" word\nover two lines")
        assertThat(rows[1].cells).doesNotContainKey("field:amount")
    }

    @Test
    fun `the delimiter is a comma or a semicolon, whichever splits the header into our columns`() {
        val (semicolon, rows) = read(csv(memosHeader.replace(',', ';'), "CR/1;GEN;Rent, plot 7;Approved;HO;2019-03-14;A. Rahman"))
        assertThat(semicolon.problems).isEmpty()
        assertThat(
            rows
                .single()
                .cells
                .getValue("subject")
                .text,
        ).isEqualTo("Rent, plot 7")

        assertThat(codes(csv("number|subject", "1|x"))).containsExactly(SheetProblemCode.DELIMITER_UNKNOWN)
        assertThat(codes(ByteArray(0))).containsExactly(SheetProblemCode.HEADER_MISSING)
    }

    @Test
    fun `UTF-8 only, with or without its BOM`() {
        val body = csv(memosHeader, "CR/1,GEN,Café,Approved,HO,2019-03-14,A. Rahman")
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        val (withBom, rows) = read(bom + body)
        assertThat(withBom.problems).isEmpty()
        assertThat(
            rows
                .single()
                .cells
                .getValue("subject")
                .text,
        ).isEqualTo("Café")

        val refused =
            mapOf(
                "Windows-1252" to String(body, StandardCharsets.UTF_8).toByteArray(Charset.forName("windows-1252")),
                "UTF-16 with its BOM" to String(body, StandardCharsets.UTF_8).toByteArray(StandardCharsets.UTF_16),
                "UTF-16LE BOM" to byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + String(body).toByteArray(StandardCharsets.UTF_16LE),
                "UTF-32BE BOM" to byteArrayOf(0, 0, 0xFE.toByte(), 0xFF.toByte()) + String(body).toByteArray(Charset.forName("UTF-32BE")),
                "invalid UTF-8 in a row" to csv(memosHeader) + "CR/1,GEN,".toByteArray() + byteArrayOf(0xC3.toByte(), 0x28),
            )
        refused.forEach { (case, bytes) ->
            val (result, read) = read(bytes)
            assertThat(result.problems.map { it.code }).describedAs(case).containsExactly(SheetProblemCode.NOT_UTF8)
            assertThat(result.problems.single().table).describedAs(case).isEqualTo(SheetTable.MEMOS)
            assertThat(read).describedAs(case).isEmpty()
        }
    }

    @Test
    fun `duplicate, unknown, blank and missing columns are batch problems that name the column`() {
        assertThat(read(csv("$memosHeader,legacy_number", "x")).first.problems.map { it.code to it.column })
            .containsExactly(SheetProblemCode.COLUMN_DUPLICATE to "legacy_number")
        assertThat(read(csv("$memosHeader,Legacy-Number", "x")).first.problems.map { it.code to it.column })
            .containsExactly(SheetProblemCode.COLUMN_DUPLICATE to "legacy_number")
        assertThat(read(csv("$memosHeader,colour", "x")).first.problems.map { it.code to it.column })
            .containsExactly(SheetProblemCode.COLUMN_UNKNOWN to "colour")
        assertThat(read(csv("$memosHeader,", "x")).first.problems.map { it.code })
            .containsExactly(SheetProblemCode.HEADER_MISSING)
        assertThat(read(csv("legacy_number,subject", "x")).first.problems.map { it.column })
            .containsExactly("memo_type", "status", "origin_unit", "raised_on", "initiator_name")
        // Field columns belong to Memos only.
        assertThat(read(csv("legacy_number,path,role,field:amount", "x"), SheetTable.FILES).first.problems.map { it.code })
            .containsExactly(SheetProblemCode.COLUMN_UNKNOWN)
    }

    @Test
    fun `malformed files are refused with the row where it went wrong`() {
        assertThat(codes(csv(memosHeader, "CR/1,GEN,\"open"))).containsExactly(SheetProblemCode.MALFORMED)
        assertThat(codes(csv(memosHeader, "CR/1,GEN,\"x\"y,Approved,HO,2019-03-14,A"))).containsExactly(SheetProblemCode.MALFORMED)
        assertThat(read(csv(memosHeader, "CR/1,GEN,Rent,Approved,HO,2019-03-14,A,extra")).first.problems.single())
            .isEqualTo(SheetProblem(SheetProblemCode.HEADER_MISSING, SheetTable.MEMOS, 2, params = mapOf("position" to 8)))
    }

    @Test
    fun `rows, value length and size are capped`() {
        val rows = csv(memosHeader, *(1..4).map { "CR/$it,GEN,Rent,Approved,HO,2019-03-14,A" }.toTypedArray())
        val (many, read) = read(rows, limits = SheetLimits(maxRows = SheetLimits.MAX_ROWS + (SheetTable.MEMOS to 3)))
        assertThat(many.problems.single().code).isEqualTo(SheetProblemCode.TOO_MANY_ROWS)
        assertThat(read).hasSize(3)

        val giant = csv(memosHeader, "CR/1,GEN,\"${"y".repeat(40_000)}\",Approved,HO,2019-03-14,A")
        assertThat(read(giant).first.problems.single())
            .isEqualTo(SheetProblem(SheetProblemCode.VALUE_TOO_LONG_FOR_FILE, SheetTable.MEMOS, 2, "subject", mapOf("limit" to 32_767)))

        val big = csv(memosHeader) + ByteArray((21 shl 20)) { 'x'.code.toByte() }
        assertThat(codes(big)).containsExactly(SheetProblemCode.MALFORMED)
    }
}
