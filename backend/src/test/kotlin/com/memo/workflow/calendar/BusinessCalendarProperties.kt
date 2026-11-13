package com.memo.workflow.calendar

import net.jqwik.api.Arbitraries
import net.jqwik.api.Arbitrary
import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.Provide
import org.assertj.core.api.Assertions.assertThat
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * Properties of working-time arithmetic (test plan T1), over random starts, durations, holidays and zones with and
 * without daylight saving: a due time is never before its start, lands inside a working period, grows with the
 * duration, and adding in two parts equals adding at once.
 */
class BusinessCalendarProperties {
    private val zones = listOf("Asia/Dhaka", "Europe/London", "America/New_York", "UTC").map(ZoneId::of)

    private fun calendar(
        zone: ZoneId,
        holidays: List<Int>,
    ): BusinessCalendar {
        val base = LocalDate.of(2026, 1, 1)
        return BusinessCalendar(
            zone,
            BusinessCalendar.DEFAULT_WEEK,
            holidays.associate {
                base.plusDays(it.toLong()) to
                    emptyList<WorkingPeriod>()
            },
        )
    }

    @Provide
    fun starts(): Arbitrary<Instant> =
        Arbitraries.longs().between(0, 365L * 24 * 60).map {
            Instant.parse("2026-01-01T00:00:00Z").plusSeconds(it * 60)
        }

    @Provide
    fun minutes(): Arbitrary<Long> = Arbitraries.longs().between(0, 5L * 8 * 60)

    @Provide
    fun holidays(): Arbitrary<List<Int>> =
        Arbitraries
            .integers()
            .between(0, 400)
            .list()
            .ofMaxSize(40)

    @Provide
    fun zoneIndex(): Arbitrary<Int> = Arbitraries.integers().between(0, 3)

    @Property(tries = 300)
    fun dueLandsInAWorkingPeriodNeverBeforeTheStart(
        @ForAll("starts") start: Instant,
        @ForAll("minutes") minutes: Long,
        @ForAll("holidays") holidays: List<Int>,
        @ForAll("zoneIndex") zone: Int,
    ) {
        val calendar = calendar(zones[zone], holidays)
        val due = checkNotNull(calendar.due(start, SlaDuration.ofMinutes(minutes), workingTime = true))
        assertThat(due).isAfterOrEqualTo(start)
        val local = due.atZone(calendar.zone)
        val inside =
            calendar.periods(local.toLocalDate()).any { !local.toLocalTime().isBefore(it.start) && !local.toLocalTime().isAfter(it.end) }
        assertThat(inside).describedAs("due $local is inside a working period").isTrue()
    }

    @Property(tries = 300)
    fun addingInTwoPartsEqualsAddingAtOnce(
        @ForAll("starts") start: Instant,
        @ForAll("minutes") first: Long,
        @ForAll("minutes") second: Long,
        @ForAll("holidays") holidays: List<Int>,
        @ForAll("zoneIndex") zone: Int,
    ) {
        val calendar = calendar(zones[zone], holidays)
        val once = checkNotNull(calendar.due(start, SlaDuration.ofMinutes(first + second), workingTime = true))
        val half = checkNotNull(calendar.due(start, SlaDuration.ofMinutes(first), workingTime = true))
        val twice = checkNotNull(calendar.due(half, SlaDuration.ofMinutes(second), workingTime = true))
        // The end of one working period and the start of the next are the same moment in working time.
        assertThat(calendar.nextWorkingMoment(twice)).isEqualTo(calendar.nextWorkingMoment(once))
        assertThat(once).isAfterOrEqualTo(half)
    }

    @Property(tries = 200)
    fun aWorkingDayLaterIsAWorkingDayAtAWorkingMoment(
        @ForAll("starts") start: Instant,
        @ForAll("holidays") holidays: List<Int>,
        @ForAll("zoneIndex") zone: Int,
    ) {
        val calendar = calendar(zones[zone], holidays)
        val due = checkNotNull(calendar.due(start, SlaDuration.ofDays(1), workingTime = true))
        val date = due.atZone(calendar.zone).toLocalDate()
        assertThat(calendar.isWorkingDay(date)).isTrue()
        assertThat(date.dayOfWeek).isNotIn(DayOfWeek.FRIDAY, DayOfWeek.SATURDAY)
        assertThat(due.atZone(calendar.zone).toLocalTime()).isBetween(LocalTime.of(9, 0), LocalTime.of(17, 0))
    }
}
