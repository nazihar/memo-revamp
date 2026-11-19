package com.memo.forum

import com.memo.common.numbering.FinancialYearStyle
import com.memo.common.numbering.NumberReset
import com.memo.forum.internal.MinutesChecks
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.time.LocalDate

/**
 * Resolution numbers (ADR-0065; test plan T2 `ResolutionFormatTest`, `MinutesPolicyChecksTest`): the parts on the shared
 * format code, periods by the meeting's date, the 100-character cap, the committee's code required, and the minutes
 * policy's ranges and defaults.
 */
class ResolutionFormatTest {
    private val day = LocalDate.of(2026, 11, 19)

    private fun part(
        kind: ResolutionPartKind,
        text: String? = null,
        digits: Int? = null,
        style: FinancialYearStyle? = null,
    ) = ResolutionNumberPart(kind, text, digits, style)

    @Test
    fun `the default is BRC slash year slash three digits, restarting every calendar year`() {
        val numbering = ResolutionNumbering()
        assertThat(ResolutionFormats.render(numbering, "BRC", 3, day, 14)).isEqualTo("BRC/2026/014")
        assertThat(ResolutionFormats.render(numbering, "BRC", 1, LocalDate.of(2027, 1, 2), 1)).isEqualTo("BRC/2027/001")
        assertThat(ResolutionFormats.render(numbering, "MCC", 9, day, 1234)).isEqualTo("MCC/2026/1234")
        assertThat(ResolutionFormats.period(numbering, day)).isEqualTo("2026")
        assertThat(ResolutionFormats.skeleton(numbering, "BRC")).isEqualTo("BRC/{year4}/{sequence}")
        assertThat(ResolutionFormats.problems(numbering, "n")).isEmpty()
    }

    @Test
    fun `the meeting number and the financial year`() {
        val numbering =
            ResolutionNumbering(
                parts =
                    listOf(
                        part(ResolutionPartKind.TEXT, "R-"),
                        part(ResolutionPartKind.COMMITTEE_CODE),
                        part(ResolutionPartKind.TEXT, " "),
                        part(ResolutionPartKind.MEETING_NUMBER),
                        part(ResolutionPartKind.TEXT, "/"),
                        part(ResolutionPartKind.FINANCIAL_YEAR, style = FinancialYearStyle.LONG),
                        part(ResolutionPartKind.TEXT, "-"),
                        part(ResolutionPartKind.SEQUENCE, digits = 2),
                    ),
                reset = NumberReset.FINANCIAL_YEAR,
                fyStartMonth = 7,
            )
        assertThat(ResolutionFormats.problems(numbering, "n")).isEmpty()
        assertThat(ResolutionFormats.render(numbering, "ALCO", 12, day, 5)).isEqualTo("R-ALCO 12/2026-27-05")
        assertThat(ResolutionFormats.render(numbering, "ALCO", 4, LocalDate.of(2026, 6, 30), 5)).isEqualTo("R-ALCO 4/2025-26-05")
        assertThat(ResolutionFormats.period(numbering, day)).isEqualTo("FY2026-27")
        assertThat(ResolutionFormats.period(numbering, LocalDate.of(2026, 6, 30))).isEqualTo("FY2025-26")
        assertThat(ResolutionFormats.skeleton(numbering, "ALCO")).isEqualTo("R-ALCO {meeting}/{fy-LONG}-{sequence}")
        val never = numbering.copy(reset = NumberReset.NEVER)
        assertThat(ResolutionFormats.period(never, day)).isEqualTo("ALL")
    }

    @Test
    fun `problems name their part under the caller's path`() {
        fun problems(numbering: ResolutionNumbering) =
            ResolutionFormats.problems(numbering, "minutes/resolutionNumbering").map { "${it.field}:${it.code}" }

        assertThat(problems(ResolutionNumbering(parts = listOf(part(ResolutionPartKind.SEQUENCE, digits = 3)), reset = NumberReset.NEVER)))
            .containsExactly("minutes/resolutionNumbering/parts:COMMITTEE_CODE_MISSING")
        assertThat(problems(ResolutionNumbering(parts = listOf(part(ResolutionPartKind.COMMITTEE_CODE)), reset = NumberReset.NEVER)))
            .containsExactly("minutes/resolutionNumbering/parts:SEQUENCE")
        assertThat(problems(ResolutionNumbering(reset = NumberReset.FINANCIAL_YEAR)))
            .containsExactly("minutes/resolutionNumbering/reset:FINANCIAL_YEAR_MISSING")
        val noYear =
            ResolutionNumbering(parts = listOf(part(ResolutionPartKind.COMMITTEE_CODE), part(ResolutionPartKind.SEQUENCE, digits = 3)))
        assertThat(problems(noYear)).containsExactly("minutes/resolutionNumbering/reset:YEAR_MISSING")
        val bad =
            ResolutionNumbering(
                parts =
                    listOf(
                        part(ResolutionPartKind.COMMITTEE_CODE),
                        part(ResolutionPartKind.TEXT, "#"),
                        part(ResolutionPartKind.YEAR, digits = 3),
                        part(ResolutionPartKind.SEQUENCE, digits = 10),
                    ),
                fyStartMonth = 13,
            )
        assertThat(problems(bad)).containsExactly(
            "minutes/resolutionNumbering/fyStartMonth:RANGE",
            "minutes/resolutionNumbering/parts/1/text:FORMAT",
            "minutes/resolutionNumbering/parts/2/digits:RANGE",
            "minutes/resolutionNumbering/parts/3/digits:RANGE",
        )
        val long =
            ResolutionNumbering(
                parts =
                    listOf(part(ResolutionPartKind.COMMITTEE_CODE), part(ResolutionPartKind.YEAR, digits = 4)) +
                        List(5) { part(ResolutionPartKind.TEXT, "x".repeat(20)) } + part(ResolutionPartKind.SEQUENCE, digits = 3),
            )
        assertThat(problems(long)).containsExactly("minutes/resolutionNumbering/parts:TOO_LONG")
        val many = ResolutionNumbering(parts = ResolutionNumbering.DEFAULT_PARTS + List(8) { part(ResolutionPartKind.TEXT, "-") })
        assertThat(problems(many)).containsExactly("minutes/resolutionNumbering/parts:COUNT")
    }

    @Test
    fun `the minutes policy's defaults, ranges and size`() {
        val json = JsonMapper.builder().build()
        assertThat(MinutesChecks.problems(MinutesPolicy(), json)).isEmpty()
        val defaults = MinutesPolicy()
        assertThat(defaults.approval).isEqualTo(MinutesApproval.AT_NEXT_MEETING)
        assertThat(defaults.circulationWorkingDays).isEqualTo(5)
        assertThat(defaults.draftWorkingDays).isEqualTo(10)
        assertThat(defaults.memoResumes).isEqualTo(MemoResumes.AT_MEETING_END)
        assertThat(defaults.templateFileId).isNull()

        fun problems(policy: MinutesPolicy) = MinutesChecks.problems(policy, json).map { "${it.field}:${it.code}" }

        val circulation = MinutesPolicy(approval = MinutesApproval.BY_CIRCULATION, circulationWorkingDays = 0)
        assertThat(problems(circulation)).containsExactly("minutes/circulationWorkingDays:RANGE")
        assertThat(problems(MinutesPolicy(circulationWorkingDays = 21))).containsExactly("minutes/circulationWorkingDays:RANGE")
        assertThat(problems(MinutesPolicy(circulationWorkingDays = 20, draftWorkingDays = 30))).isEmpty()
        assertThat(problems(MinutesPolicy(draftWorkingDays = 0))).containsExactly("minutes/draftWorkingDays:RANGE")
        assertThat(problems(MinutesPolicy(draftWorkingDays = 31))).containsExactly("minutes/draftWorkingDays:RANGE")
        val huge = ResolutionNumbering(parts = List(400) { part(ResolutionPartKind.TEXT, "-".repeat(20)) })
        assertThat(
            problems(MinutesPolicy(resolutionNumbering = huge)),
        ).contains("minutes:TOO_LARGE", "minutes/resolutionNumbering/parts:COUNT")
    }
}
