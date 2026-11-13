package com.memo.workflow.calendar

import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * An SLA duration (ADR-0041): whole [days] and [minutes], kept apart because in working time a day is a working day,
 * not 24 working hours. Parsed from the definitions' ISO 8601 subset (`P2D`, `PT4H30M`, `P1DT2H`).
 */
data class SlaDuration(
    val days: Long,
    val minutes: Long,
) {
    init {
        require(days >= 0 && minutes >= 0) { "an SLA duration is not negative" }
    }

    companion object {
        private val ISO = Regex("""^P(?:(\d{1,3})D)?(?:T(?:(\d{1,4})H)?(?:(\d{1,4})M)?)?$""")

        fun parse(text: String): SlaDuration {
            val match = requireNotNull(ISO.matchEntire(text)) { "not an SLA duration: $text" }
            val (days, hours, minutes) = match.destructured
            return SlaDuration(days.toLongOrNull() ?: 0, (hours.toLongOrNull() ?: 0) * MINUTES_PER_HOUR + (minutes.toLongOrNull() ?: 0))
        }

        fun ofMinutes(minutes: Long) = SlaDuration(0, minutes)

        fun ofDays(days: Long) = SlaDuration(days, 0)

        private const val MINUTES_PER_HOUR = 60
    }
}

/** A stretch of working time within a day, [start] inclusive to [end] exclusive, in the calendar's zone. */
data class WorkingPeriod(
    val start: LocalTime,
    val end: LocalTime,
) {
    init {
        require(end.isAfter(start)) { "a working period ends after it starts" }
    }
}

/**
 * A tenant's business calendar (ADR-0041, design E05 §4.1): working periods per weekday, dated exceptions (a date with
 * other periods, or none: closed) and the tenant's time zone. Pure and DST-safe: periods are local times, and lengths
 * are measured between the instants they map to, so a skipped or repeated hour counts as it really passes.
 */
class BusinessCalendar(
    val zone: ZoneId,
    private val week: Map<DayOfWeek, List<WorkingPeriod>>,
    private val days: Map<LocalDate, List<WorkingPeriod>> = emptyMap(),
) {
    /** The working periods of [date]: its exception if it has one, else its weekday's; empty when closed. */
    fun periods(date: LocalDate): List<WorkingPeriod> = (days[date] ?: week[date.dayOfWeek].orEmpty()).sortedBy { it.start }

    fun isWorkingDay(date: LocalDate): Boolean = periods(date).isNotEmpty()

    /**
     * When something started at [start] is due after [duration]: with [workingTime], whole days are working days (the
     * same time of day on the n-th working day after) and hours and minutes are working minutes; otherwise plain
     * calendar time in the zone. Null when no working time is found within [MAX_DAYS] days (a calendar with no working
     * days at all).
     */
    fun due(
        start: Instant,
        duration: SlaDuration,
        workingTime: Boolean,
    ): Instant? =
        if (workingTime) {
            nextWorkingMoment(start)
                ?.let { plusWorkingDays(it, duration.days) }
                ?.let { plusWorkingMinutes(it, duration.minutes) }
        } else {
            start
                .atZone(zone)
                .plusDays(duration.days)
                .plusMinutes(duration.minutes)
                .toInstant()
        }

    /** [start] if it falls in a working period, else the start of the next working period; null if none is found. */
    fun nextWorkingMoment(start: Instant): Instant? {
        val local = start.atZone(zone)
        var date = local.toLocalDate()
        repeat(MAX_DAYS) {
            for (period in periods(date)) {
                val begins = at(date, period.start)
                val ends = at(date, period.end)
                if (!start.isBefore(ends)) continue
                return if (start.isBefore(begins)) begins else start
            }
            date = date.plusDays(1)
        }
        return null
    }

    /** The same local time on the [n]-th working day after [from]'s date, moved to the next working moment if that time is not worked. */
    private fun plusWorkingDays(
        from: Instant,
        n: Long,
    ): Instant? {
        val local = from.atZone(zone)
        return when (n) {
            0L -> {
                from
            }

            else -> {
                generateSequence(local.toLocalDate().plusDays(1)) { it.plusDays(1) }
                    .take(MAX_DAYS)
                    .filter(::isWorkingDay)
                    .elementAtOrNull((n - 1).toInt())
                    ?.let { nextWorkingMoment(at(it, local.toLocalTime())) }
            }
        }
    }

    /** [minutes] of working time after [from], which is a working moment. */
    private fun plusWorkingMinutes(
        from: Instant,
        minutes: Long,
    ): Instant? {
        var left = Duration.ofMinutes(minutes)
        var date = from.atZone(zone).toLocalDate()
        repeat(MAX_DAYS) {
            for (period in periods(date)) {
                val ends = at(date, period.end)
                val begins = maxOf(at(date, period.start), from)
                if (!begins.isBefore(ends)) continue
                val available = Duration.between(begins, ends)
                if (left <= available) return begins.plus(left)
                left = left.minus(available)
            }
            date = date.plusDays(1)
        }
        return null
    }

    private fun at(
        date: LocalDate,
        time: LocalTime,
    ): Instant = ZonedDateTime.of(date, time, zone).toInstant()

    companion object {
        /** How far the arithmetic looks ahead, so a calendar without working days cannot loop (threat D1). */
        const val MAX_DAYS = 3 * 366

        /** Until an administrator sets one (ADR-0041): Sunday to Thursday, 9:00 to 17:00. */
        val DEFAULT_WEEK: Map<DayOfWeek, List<WorkingPeriod>> =
            listOf(DayOfWeek.SUNDAY, DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY)
                .associateWith { listOf(WorkingPeriod(LocalTime.of(9, 0), LocalTime.of(17, 0))) }
    }
}
