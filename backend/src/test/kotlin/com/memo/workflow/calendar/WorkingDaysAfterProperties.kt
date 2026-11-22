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
 * `WorkingDays.after` (design E07 §1.5: when an action without a recent update turns amber, test plan T4) over random
 * weeks and holidays: the result is a working day, n working days lie in (date, result], and it never moves earlier as
 * n grows.
 */
class WorkingDaysAfterProperties {
    @Property
    fun theResultIsAWorkingDayWithNWorkingDaysAfterTheDate(
        @ForAll("calendars") calendar: WorkingDaysBeforeProperties.Calendar,
        @ForAll("days") date: LocalDate,
        @ForAll @IntRange(min = 1, max = 30) n: Int,
    ) {
        val result = checkNotNull(WorkingDayRules.after(date, n, calendar::working))
        assertThat(result).isAfter(date)
        assertThat(calendar.working(result)).isTrue()
        val between = generateSequence(date.plusDays(1)) { it.plusDays(1) }.takeWhile { !it.isAfter(result) }.count(calendar::working)
        assertThat(between).isEqualTo(n)
    }

    @Property
    fun moreWorkingDaysNeverGiveAnEarlierDay(
        @ForAll("calendars") calendar: WorkingDaysBeforeProperties.Calendar,
        @ForAll("days") date: LocalDate,
        @ForAll @IntRange(min = 0, max = 29) n: Int,
    ) {
        val fewer = checkNotNull(WorkingDayRules.after(date, n, calendar::working))
        val more = checkNotNull(WorkingDayRules.after(date, n + 1, calendar::working))
        assertThat(more).isAfter(fewer)
    }

    @Property
    fun zeroIsTheDateAndACalendarWithoutWorkingDaysHasNone(
        @ForAll("days") date: LocalDate,
        @ForAll @IntRange(min = 1, max = 30) n: Int,
    ) {
        assertThat(WorkingDayRules.after(date, 0) { false }).isEqualTo(date)
        assertThat(WorkingDayRules.after(date, n) { false }).isNull()
    }

    @Provide
    fun calendars(): Arbitrary<WorkingDaysBeforeProperties.Calendar> {
        val week = Arbitraries.subsetOf(*DayOfWeek.entries.toTypedArray()).ofMinSize(1)
        val holidays = days().set().ofMaxSize(40)
        return week.flatMap { w -> holidays.map { h -> WorkingDaysBeforeProperties.Calendar(w, h) } }
    }

    @Provide
    fun days(): Arbitrary<LocalDate> = Arbitraries.integers().between(0, 2 * 366).map { LocalDate.of(2026, 1, 1).plusDays(it.toLong()) }
}
