package com.memo.forum

import com.memo.common.numbering.FinancialYearStyle
import com.memo.common.numbering.NumberReset
import net.jqwik.api.Arbitraries
import net.jqwik.api.Arbitrary
import net.jqwik.api.Combinators
import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.Provide
import net.jqwik.api.constraints.IntRange
import net.jqwik.api.constraints.LongRange
import org.assertj.core.api.Assertions.assertThat
import java.time.LocalDate
import java.util.regex.Pattern

/**
 * ADR-0065, test plan T2: for random valid resolution formats, dates and sequences, the rendered number reads back by its
 * parts: the sequence, the committee's code and the meeting's number, and the period the counter uses is the one the
 * year or financial year in the number names.
 */
class ResolutionFormatProperties {
    @Property
    fun renderedNumbersReadBackTheirSequenceAndPeriod(
        @ForAll("numberings") numbering: ResolutionNumbering,
        @ForAll("dates") date: LocalDate,
        @ForAll @LongRange(min = 1, max = 999_999) sequence: Long,
        @ForAll @IntRange(min = 1, max = 99) meeting: Int,
    ) {
        assertThat(ResolutionFormats.problems(numbering, "n")).isEmpty()
        val number = ResolutionFormats.render(numbering, "BRC", meeting, date, sequence)
        assertThat(number.length).isLessThanOrEqualTo(100)
        val matcher = pattern(numbering).matcher(number)
        assertThat(matcher.matches()).describedAs(number).isTrue()
        assertThat(matcher.group("sequence").toLong()).isEqualTo(sequence)
        numbering.parts.firstOrNull { it.kind == ResolutionPartKind.MEETING_NUMBER }?.let {
            assertThat(matcher.group("meeting").toInt()).isEqualTo(meeting)
        }
        val period = ResolutionFormats.period(numbering, date)
        when (numbering.reset) {
            NumberReset.NEVER -> {
                assertThat(period).isEqualTo("ALL")
            }

            NumberReset.YEARLY -> {
                assertThat(period).endsWith(matcher.group("year")).hasSize(4)
            }

            NumberReset.FINANCIAL_YEAR -> {
                val fy = matcher.group("fy")
                assertThat(period).startsWith("FY")
                if (fy.startsWith("FY")) {
                    assertThat(period).endsWith(fy.removePrefix("FY"))
                } else {
                    assertThat(period).startsWith("FY" + fy.take(4))
                }
            }
        }
    }

    @Property
    fun numbersOfOnePeriodDifferOnlyBySequence(
        @ForAll("numberings") numbering: ResolutionNumbering,
        @ForAll("dates") date: LocalDate,
        @ForAll @LongRange(min = 1, max = 99_999) a: Long,
        @ForAll @LongRange(min = 1, max = 99_999) b: Long,
    ) {
        val first = ResolutionFormats.render(numbering, "BRC", 2, date, a)
        val second = ResolutionFormats.render(numbering, "BRC", 2, date, b)
        assertThat(first == second).isEqualTo(a == b)
    }

    /** A regex for [numbering]'s numbers, with named groups for the sequence, the meeting, the year and the financial year. */
    private fun pattern(numbering: ResolutionNumbering): Pattern {
        val regex =
            numbering.parts.joinToString("") { part ->
                when (part.kind) {
                    ResolutionPartKind.TEXT -> {
                        Pattern.quote(part.text.orEmpty())
                    }

                    ResolutionPartKind.COMMITTEE_CODE -> {
                        "BRC"
                    }

                    ResolutionPartKind.MEETING_NUMBER -> {
                        "(?<meeting>\\d+)"
                    }

                    ResolutionPartKind.YEAR -> {
                        "(?<year>\\d{${part.digits}})"
                    }

                    ResolutionPartKind.FINANCIAL_YEAR -> {
                        if (part.style ==
                            FinancialYearStyle.SHORT
                        ) {
                            "(?<fy>FY\\d{2})"
                        } else {
                            "(?<fy>\\d{4}(-\\d{2})?)"
                        }
                    }

                    ResolutionPartKind.SEQUENCE -> {
                        "(?<sequence>\\d+)"
                    }
                }
            }
        return Pattern.compile(regex)
    }

    @Provide
    fun dates(): Arbitrary<LocalDate> = Arbitraries.longs().between(0, 365L * 60).map { LocalDate.of(2000, 1, 1).plusDays(it) }

    /**
     * Valid formats: the committee's code, the sequence and at most one of each other part, in any order, with a separator
     * between parts, and the year or financial year the reset needs.
     */
    @Provide
    fun numberings(): Arbitrary<ResolutionNumbering> {
        val reset = Arbitraries.of(NumberReset::class.java)
        val fyStart = Arbitraries.integers().between(1, 12)
        val year = Arbitraries.of(2, 4)
        val style = Arbitraries.of(FinancialYearStyle::class.java)
        val digits = Arbitraries.integers().between(1, 6)
        val meeting = Arbitraries.of(true, false)
        val separator = Arbitraries.of("/", "-", " ", ".", "_", "/R")
        return Combinators.combine(reset, fyStart, year, style, digits, meeting, separator).`as` { r, fy, y, s, d, m, sep ->
            val core =
                listOfNotNull(
                    ResolutionNumberPart(ResolutionPartKind.COMMITTEE_CODE),
                    ResolutionNumberPart(ResolutionPartKind.MEETING_NUMBER).takeIf { m },
                    ResolutionNumberPart(ResolutionPartKind.YEAR, digits = y).takeIf { r != NumberReset.FINANCIAL_YEAR },
                    ResolutionNumberPart(ResolutionPartKind.FINANCIAL_YEAR, style = s).takeIf { r == NumberReset.FINANCIAL_YEAR },
                    ResolutionNumberPart(ResolutionPartKind.SEQUENCE, digits = d),
                )
            val parts =
                core.flatMapIndexed { i, p ->
                    if (i ==
                        0
                    ) {
                        listOf(p)
                    } else {
                        listOf(ResolutionNumberPart(ResolutionPartKind.TEXT, sep), p)
                    }
                }
            ResolutionNumbering(parts, r, fy)
        }
    }
}
