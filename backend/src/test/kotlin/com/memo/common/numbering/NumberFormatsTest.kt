package com.memo.common.numbering

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDate

/**
 * The shared number format (E06 T1, ADR-0065): a resolution number with the committee's code and the meeting's number,
 * written by the code memo numbers use (memo's own suite is `memo.internal.NumberFormatTest`).
 */
class NumberFormatsTest {
    private val resolution =
        NumberFormat(
            listOf(
                FormatPart.Code("committee", varies = false, maxLength = 10),
                FormatPart.Text("/"),
                FormatPart.Year(4),
                FormatPart.Text("/"),
                FormatPart.Code("meeting", varies = true, maxLength = 6),
                FormatPart.Text("/"),
                FormatPart.Sequence(3),
            ),
            NumberReset.YEARLY,
            7,
        )

    private fun codes(committee: String) = { name: String -> if (name == "committee") committee else "4" }

    @Test
    fun `a resolution number has the committee's code, the year, the meeting and the sequence`() {
        val date = LocalDate.of(2026, 11, 19)
        assertThat(NumberFormats.render(resolution, date, 1, codes("BRC"))).isEqualTo("BRC/2026/4/001")
        assertThat(NumberFormats.period(resolution, date)).isEqualTo("2026")
        assertThat(NumberFormats.problems(resolution, "minutes/resolutionNumbering")).isEmpty()
        assertThat(NumberFormats.skeleton(resolution, codes("BRC"))).isEqualTo("BRC/{year4}/{meeting}/{sequence}")
        assertThat(NumberFormats.skeleton(resolution, codes("MCC"))).isNotEqualTo(NumberFormats.skeleton(resolution, codes("BRC")))
    }

    @Test
    fun `problems are named under the caller's path, with its own problems after the reset checks`() {
        val bad = NumberFormat(listOf(FormatPart.Text("R<"), FormatPart.Code("committee", false, 10)), NumberReset.FINANCIAL_YEAR, 0)
        val own =
            listOf(
                com.memo.common.web
                    .FieldProblem("minutes/resolutionNumbering/x", "OWN", "Own."),
            )
        assertThat(NumberFormats.problems(bad, "minutes/resolutionNumbering", own).map { "${it.field}:${it.code}" }).containsExactly(
            "minutes/resolutionNumbering/parts:SEQUENCE",
            "minutes/resolutionNumbering/reset:FINANCIAL_YEAR_MISSING",
            "minutes/resolutionNumbering/x:OWN",
            "minutes/resolutionNumbering/fyStartMonth:RANGE",
            "minutes/resolutionNumbering/parts/0/text:FORMAT",
        )
        val long = NumberFormat(List(5) { FormatPart.Code("c$it", false, 20) } + FormatPart.Sequence(3), NumberReset.NEVER, 1)
        assertThat(NumberFormats.problems(long, "f").map { it.code }).containsExactly("TOO_LONG")
    }

    @Test
    fun `the financial year written short and long`() {
        val fy =
            NumberFormat(
                listOf(
                    FormatPart.FinancialYear(FinancialYearStyle.SHORT),
                    FormatPart.Text("-"),
                    FormatPart.FinancialYear(FinancialYearStyle.LONG),
                ),
                NumberReset.FINANCIAL_YEAR,
                7,
            )
        assertThat(NumberFormats.render(fy, LocalDate.of(2026, 7, 1), 1) { "" }).isEqualTo("FY27-2026-27")
        assertThat(NumberFormats.period(fy, LocalDate.of(2026, 6, 30))).isEqualTo("FY2025-26")
    }
}
