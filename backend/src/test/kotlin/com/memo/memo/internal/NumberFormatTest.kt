package com.memo.memo.internal

import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.constraints.IntRange
import net.jqwik.api.constraints.LongRange
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDate

/** Memo number formats (test plan E01, T4; ADR-0044): periods, rendering, skeletons and the format's checks. */
class NumberFormatTest {
    private val credit =
        Numbering(
            series = "credit",
            parts =
                listOf(
                    NumberPart(NumberPartKind.TYPE_CODE),
                    NumberPart(NumberPartKind.TEXT, text = "/"),
                    NumberPart(NumberPartKind.UNIT_CODE),
                    NumberPart(NumberPartKind.TEXT, text = "/"),
                    NumberPart(NumberPartKind.FINANCIAL_YEAR, style = FinancialYearStyle.LONG),
                    NumberPart(NumberPartKind.TEXT, text = "/"),
                    NumberPart(NumberPartKind.SEQUENCE, digits = 4),
                ),
            reset = NumberReset.FINANCIAL_YEAR,
            countPer = NumberCountPer.UNIT,
            fyStartMonth = 7,
        )

    @Test
    fun `the Phase 2 format continues unchanged`() {
        val numbering = Numbering()
        assertThat(NumberFormats.render(numbering, "GM", null, LocalDate.of(2026, 11, 14), 42)).isEqualTo("M-2026-00042")
        assertThat(NumberFormats.period(numbering, LocalDate.of(2026, 11, 14))).isEqualTo("2026")
        assertThat(NumberFormats.problems(numbering)).isEmpty()
    }

    @Test
    fun `a financial year starting in July turns over on 1 July`() {
        assertThat(NumberFormats.render(credit, "CR", "HO-CR", LocalDate.of(2026, 11, 14), 1)).isEqualTo("CR/HO-CR/2026-27/0001")
        assertThat(NumberFormats.render(credit, "CR", "HO-CR", LocalDate.of(2026, 6, 30), 12)).isEqualTo("CR/HO-CR/2025-26/0012")
        assertThat(NumberFormats.period(credit, LocalDate.of(2026, 6, 30))).isEqualTo("FY2025-26")
        assertThat(NumberFormats.period(credit, LocalDate.of(2026, 7, 1))).isEqualTo("FY2026-27")
        val short =
            credit.copy(
                parts =
                    credit.parts.map {
                        if (it.kind ==
                            NumberPartKind.FINANCIAL_YEAR
                        ) {
                            it.copy(style = FinancialYearStyle.SHORT)
                        } else {
                            it
                        }
                    },
            )
        assertThat(NumberFormats.render(short, "CR", "HO", LocalDate.of(2026, 7, 1), 7)).isEqualTo("CR/HO/FY27/0007")
    }

    @Test
    fun `a calendar financial year, two-digit years, and sequences longer than their padding`() {
        val january = credit.copy(fyStartMonth = 1)
        assertThat(NumberFormats.render(january, "CR", "HO", LocalDate.of(2026, 3, 1), 1)).isEqualTo("CR/HO/2026/0001")
        val twoDigits =
            Numbering(parts = listOf(NumberPart(NumberPartKind.YEAR, digits = 2), NumberPart(NumberPartKind.SEQUENCE, digits = 3)))
        assertThat(NumberFormats.render(twoDigits, "X", null, LocalDate.of(2031, 1, 1), 12345)).isEqualTo("3112345")
        assertThat(
            NumberFormats.period(Numbering(reset = NumberReset.NEVER, parts = twoDigits.parts), LocalDate.of(2031, 1, 1)),
        ).isEqualTo("ALL")
    }

    @Test
    fun `the checks name each problem`() {
        val bad =
            Numbering(
                series = "Credit",
                parts =
                    listOf(
                        NumberPart(NumberPartKind.TEXT, text = "M<"),
                        NumberPart(NumberPartKind.YEAR, digits = 3),
                        NumberPart(NumberPartKind.FINANCIAL_YEAR),
                    ),
                reset = NumberReset.FINANCIAL_YEAR,
                countPer = NumberCountPer.UNIT,
                fyStartMonth = 13,
            )
        assertThat(NumberFormats.problems(bad).map { "${it.field}:${it.code}" }).containsExactlyInAnyOrder(
            "numbering/series:FORMAT",
            "numbering/parts:SEQUENCE",
            "numbering/countPer:UNIT_MISSING",
            "numbering/fyStartMonth:RANGE",
            "numbering/parts/0/text:FORMAT",
            "numbering/parts/1/digits:RANGE",
            "numbering/parts/2/style:REQUIRED",
        )
        assertThat(
            NumberFormats.problems(Numbering(reset = NumberReset.YEARLY, parts = listOf(NumberPart(NumberPartKind.SEQUENCE, digits = 4)))),
        ).extracting<String> { it.code }
            .containsExactly("YEAR_MISSING")
        val long = Numbering(parts = List(6) { NumberPart(NumberPartKind.TEXT, text = "A".repeat(20)) } + Numbering.DEFAULT_PARTS)
        assertThat(NumberFormats.problems(long).map { it.code }).contains("TOO_LONG")
    }

    @Test
    fun `skeletons differ by text and type code, not by the values that vary`() {
        assertThat(NumberFormats.skeleton(Numbering(), "GM")).isEqualTo(NumberFormats.skeleton(Numbering(), "CF"))
        assertThat(NumberFormats.skeleton(credit, "CR")).isNotEqualTo(NumberFormats.skeleton(credit, "CF"))
    }

    @Property(tries = 200)
    fun `distinct sequences give distinct numbers`(
        @ForAll @LongRange(min = 1, max = 99_999_999) a: Long,
        @ForAll @LongRange(min = 1, max = 99_999_999) b: Long,
    ) {
        val date = LocalDate.of(2026, 11, 14)
        val left = NumberFormats.render(credit, "CR", "HO", date, a)
        val right = NumberFormats.render(credit, "CR", "HO", date, b)
        assertThat(left == right).isEqualTo(a == b)
    }

    @Property(tries = 200)
    fun `the financial-year period changes exactly on the start month's first day`(
        @ForAll @IntRange(min = 2000, max = 2098) year: Int,
        @ForAll @IntRange(min = 1, max = 12) start: Int,
    ) {
        val numbering = credit.copy(fyStartMonth = start)
        val first = LocalDate.of(year, start, 1)
        assertThat(NumberFormats.period(numbering, first.minusDays(1))).isNotEqualTo(NumberFormats.period(numbering, first))
        assertThat(NumberFormats.period(numbering, first)).isEqualTo(NumberFormats.period(numbering, first.plusMonths(11)))
    }
}
