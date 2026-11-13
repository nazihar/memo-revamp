package com.memo.workflow.calendar

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/** Working-time arithmetic (design E05 §4.1, test plan T1), on the pilot's calendar: Friday and Saturday off, Asia/Dhaka. */
class BusinessCalendarTest {
    private val dhaka = ZoneId.of("Asia/Dhaka")
    private val nineToFive = listOf(WorkingPeriod(LocalTime.of(9, 0), LocalTime.of(17, 0)))

    private fun calendar(
        days: Map<LocalDate, List<WorkingPeriod>> = emptyMap(),
        zone: ZoneId = dhaka,
    ) = BusinessCalendar(zone, BusinessCalendar.DEFAULT_WEEK, days)

    private fun at(
        text: String,
        zone: ZoneId = dhaka,
    ) = LocalDateTime.parse(text).atZone(zone).toInstant()

    @Test
    fun `a working day after Thursday afternoon is Sunday, or Monday when Sunday is a holiday`() {
        // 2026-11-12 is a Thursday.
        assertThat(calendar().due(at("2026-11-12T15:00"), SlaDuration.ofDays(1), workingTime = true)).isEqualTo(at("2026-11-15T15:00"))
        val holiday = calendar(mapOf(LocalDate.of(2026, 11, 15) to emptyList()))
        assertThat(holiday.due(at("2026-11-12T15:00"), SlaDuration.ofDays(1), workingTime = true)).isEqualTo(at("2026-11-16T15:00"))
    }

    @Test
    fun `working hours run over the weekend and skip time outside the periods`() {
        // Thursday 15:00 + 4 working hours: 2 on Thursday, 2 on Sunday.
        assertThat(calendar().due(at("2026-11-12T15:00"), SlaDuration.ofMinutes(240), workingTime = true)).isEqualTo(at("2026-11-15T11:00"))
        // Starting at night counts from the next morning.
        assertThat(calendar().due(at("2026-11-11T22:00"), SlaDuration.ofMinutes(60), workingTime = true)).isEqualTo(at("2026-11-12T10:00"))
    }

    @Test
    fun `an extra working Saturday and shorter Ramadan hours count`() {
        val saturday = LocalDate.of(2026, 11, 14)
        val days =
            mapOf(
                saturday to nineToFive,
                LocalDate.of(2026, 11, 15) to listOf(WorkingPeriod(LocalTime.of(9, 30), LocalTime.of(14, 30))),
            )
        val due = calendar(days).due(at("2026-11-12T16:00"), SlaDuration.ofMinutes(600), workingTime = true)
        // 1 h Thursday, 8 h Saturday, 1 h Sunday from 9:30.
        assertThat(due).isEqualTo(at("2026-11-15T10:30"))
    }

    @Test
    fun `calendar time ignores the working week`() {
        assertThat(calendar().due(at("2026-11-12T15:00"), SlaDuration.ofDays(2), workingTime = false)).isEqualTo(at("2026-11-14T15:00"))
    }

    @Test
    fun `several periods a day are worked in order, and the lunch break is not`() {
        val split = listOf(WorkingPeriod(LocalTime.of(9, 0), LocalTime.of(13, 0)), WorkingPeriod(LocalTime.of(14, 0), LocalTime.of(18, 0)))
        val week = BusinessCalendar.DEFAULT_WEEK.mapValues { split }
        val due = BusinessCalendar(dhaka, week).due(at("2026-11-12T12:00"), SlaDuration.ofMinutes(120), workingTime = true)
        assertThat(due).isEqualTo(at("2026-11-12T15:00"))
    }

    @Test
    fun `SLA durations keep days apart from hours`() {
        assertThat(SlaDuration.parse("P1DT2H30M")).isEqualTo(SlaDuration(1, 150))
        assertThat(SlaDuration.parse("PT24H")).isEqualTo(SlaDuration(0, 1440))
        assertThat(calendar().due(at("2026-11-12T15:00"), SlaDuration.parse("PT16H"), workingTime = true)).isEqualTo(at("2026-11-16T15:00"))
    }

    @Test
    fun `a calendar without working days has no due time`() {
        val closed = BusinessCalendar(dhaka, emptyMap())
        assertThat(closed.due(at("2026-11-12T15:00"), SlaDuration.ofMinutes(60), workingTime = true)).isNull()
    }

    @Test
    fun `across a daylight saving change working hours are real hours`() {
        val london = ZoneId.of("Europe/London")
        // Clocks go back at 02:00 on Sunday 2026-10-25; a period from midnight to 06:00 that day lasts 7 real hours.
        val nights = mapOf(DayOfWeek.SUNDAY to listOf(WorkingPeriod(LocalTime.MIDNIGHT, LocalTime.of(6, 0))))
        val due = BusinessCalendar(london, nights).due(at("2026-10-25T00:00", london), SlaDuration.ofMinutes(420), workingTime = true)
        assertThat(due).isEqualTo(at("2026-10-25T06:00", london))
    }
}
