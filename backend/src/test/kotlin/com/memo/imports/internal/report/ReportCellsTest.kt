package com.memo.imports.internal.report

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** The workbook's injection prefix (design E06 §8, threat model I3; OWASP CSV injection). */
class ReportCellsTest {
    @Test
    fun `a value a spreadsheet would read as a formula or a command gets the prefix, in ASCII and full-width forms`() {
        listOf("=HYPERLINK(\"x\")", "+1", "-1", "@SUM(A1)", "\tx", "\rx", "\nx", "＝1", "＋1", "－1", "＠1").forEach { value ->
            assertThat(ReportCells.safe(value)).describedAs(value).isEqualTo("'$value")
        }
        listOf("", "LEG/1", "a=b", " =1", "'=1", "2019-03-14").forEach { value ->
            assertThat(ReportCells.safe(value)).describedAs(value).isEqualTo(value)
        }
    }

    @Test
    fun `a value is cut at the cell limit`() {
        assertThat(ReportCells.safe("x".repeat(ReportCells.MAX_CHARS + 10))).hasSize(ReportCells.MAX_CHARS)
        assertThat(ReportCells.safe("=" + "x".repeat(ReportCells.MAX_CHARS))).hasSize(ReportCells.MAX_CHARS).startsWith("'=")
    }

    @Test
    fun `the balance names each line that does not add up, and says complete and valid`() {
        val balanced = totals()
        assertThat(ReportBalance.of(balanced)).isEqualTo(ReportBalance(emptyList(), complete = true, valid = true))

        val broken =
            balanced.copy(
                rows = balanced.rows.copy(read = 3),
                files = balanced.files.copy(listed = 3, notReceived = 1),
                renditions = mapOf("PDF" to RENDITIONS.copy(pending = 1), "TEXT" to RENDITIONS),
                fixity = balanced.fixity.copy(mismatches = 1),
            )
        val balance = ReportBalance.of(broken)
        assertThat(balance.failing).containsExactly("ROWS", "RENDITIONS_PDF", "FIXITY")
        assertThat(balance.balanced).isFalse()
        assertThat(balance.complete).describedAs("a Files row without its original").isFalse()
        assertThat(balance.valid).isFalse()
    }

    private fun totals(): ReportTotals {
        val one = FilesAndBytes(1, 10)
        return ReportTotals(
            rows = RowTotals(2, 1, 0, 1, 0, 0, mapOf("NUMBER_IN_USE" to 1)),
            files = FileRowTotals(2, 2, 0, 0),
            originals = OriginalTotals(one + one, one + one, FilesAndBytes.NONE, FilesAndBytes.NONE, one, one, FilesAndBytes.NONE),
            renditions = mapOf("PDF" to RENDITIONS, "TEXT" to RENDITIONS),
            fixity = FixityTotals(1, 1, 0, 0, 0),
            storedNotBound = 0,
            problems = emptyMap(),
        )
    }

    private companion object {
        val RENDITIONS = RenditionTotals(1, 1, 0, 0, 0, 0)
    }
}
