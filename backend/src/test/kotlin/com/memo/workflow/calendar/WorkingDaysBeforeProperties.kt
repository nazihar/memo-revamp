package com.memo.workflow.calendar

import net.jqwik.api.Arbitraries
import net.jqwik.api.Arbitrary
import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.Provide
import net.jqwik.api.constraints.IntRange
import org.assertj.core.api.Assertions.assertThat
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * `WorkingDays.before` (design E03 §1.4, test plan T1) over random weeks and holidays: the result is a working day, n
 * working days lie in [result, date), and it never moves later as n grows.
 */
class WorkingDaysBeforeProperties {
    data class Calendar(
        val week: Set<DayOfWeek>,
        val holidays: Set<LocalDate>,
    ) {
        fun working(day: LocalDate): Boolean = day.dayOfWeek in week && day !in holidays
    }

    @Property
    fun theResultIsAWorkingDayWithNWorkingDaysBeforeTheDate(
        @ForAll("calendars") calendar: Calendar,
        @ForAll("days") date: LocalDate,
        @ForAll @IntRange(min = 1, max = 30) n: Int,
    ) {
        val result = checkNotNull(WorkingDayRules.before(date, n, calendar::working))
        assertThat(result).isBefore(date)
        assertThat(calendar.working(result)).isTrue()
        val between = generateSequence(result) { it.plusDays(1) }.takeWhile { it.isBefore(date) }.count(calendar::working)
        assertThat(between).isEqualTo(n)
    }

    @Property
    fun moreWorkingDaysNeverGiveALaterDay(
        @ForAll("calendars") calendar: Calendar,
        @ForAll("days") date: LocalDate,
        @ForAll @IntRange(min = 0, max = 29) n: Int,
    ) {
        val fewer = checkNotNull(WorkingDayRules.before(date, n, calendar::working))
        val more = checkNotNull(WorkingDayRules.before(date, n + 1, calendar::working))
        assertThat(more).isBefore(fewer)
    }

    @Property
    fun zeroIsTheDateAndACalendarWithoutWorkingDaysHasNone(
        @ForAll("days") date: LocalDate,
        @ForAll @IntRange(min = 1, max = 30) n: Int,
    ) {
        assertThat(WorkingDayRules.before(date, 0) { false }).isEqualTo(date)
        assertThat(WorkingDayRules.before(date, n) { false }).isNull()
    }

    @Provide
    fun calendars(): Arbitrary<Calendar> {
        val week = Arbitraries.subsetOf(*DayOfWeek.entries.toTypedArray()).ofMinSize(1)
        val holidays = days().set().ofMaxSize(40)
        return week.flatMap { w -> holidays.map { h -> Calendar(w, h) } }
    }

    @Provide
    fun days(): Arbitrary<LocalDate> = Arbitraries.integers().between(0, 2 * 366).map { LocalDate.of(2026, 1, 1).plusDays(it.toLong()) }
}
