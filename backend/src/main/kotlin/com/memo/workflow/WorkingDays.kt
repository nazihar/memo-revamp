package com.memo.workflow

import com.memo.org.OrgDirectory
import com.memo.workflow.calendar.WorkingDayRules
import com.memo.workflow.internal.Calendars
import org.springframework.stereotype.Service
import java.time.Instant
import java.time.LocalDate

/**
 * The tenant's business calendar for other modules (ADR-0041): how many working days have passed, and which day lies a
 * number of working days before another (a meeting's paper deadline, design E03 §1.4). Inside a tenant transaction.
 */
@Service
class WorkingDays(
    private val calendars: Calendars,
    private val org: OrgDirectory,
) {
    /** The working days after the day of [start] up to and including today, in the tenant's zone: 0 on the day itself. */
    fun since(start: Instant): Int = calendars.workingDays(start.atZone(org.zone()).toLocalDate(), org.today())

    /**
     * The [n]-th working day before [date] on the tenant's calendar (weekly hours and dated exceptions), [date] not
     * counted; [date] itself when [n] is 0, and null when the calendar has no working day in the last two years.
     */
    fun before(
        date: LocalDate,
        n: Int,
    ): LocalDate? {
        val week = calendars.week().week
        val exceptions =
            calendars
                .days(date.minusDays(WorkingDayRules.MAX_DAYS.toLong()), date.minusDays(1))
                .associate { it.day to it.periods.isNotEmpty() }
        return WorkingDayRules.before(date, n) { day -> exceptions[day] ?: week[day.dayOfWeek].orEmpty().isNotEmpty() }
    }

    /**
     * The [n]-th working day after [date] on the tenant's calendar, [date] not counted (design E07 §1.5); [date] itself when
     * [n] is 0, and null when the calendar has no working day in the next two years.
     */
    fun after(
        date: LocalDate,
        n: Int,
    ): LocalDate? {
        val week = calendars.week().week
        val exceptions =
            calendars
                .days(date.plusDays(1), date.plusDays(WorkingDayRules.MAX_DAYS.toLong()))
                .associate { it.day to it.periods.isNotEmpty() }
        return WorkingDayRules.after(date, n) { day -> exceptions[day] ?: week[day.dayOfWeek].orEmpty().isNotEmpty() }
    }

    /**
     * When the working day [date] ends: the end of its last working period on the tenant's calendar (its exception's, else
     * its weekday's), in the tenant's zone; the last second of the day when it has none (a day off).
     */
    fun closeOf(date: LocalDate): Instant {
        val zone = org.zone()
        val periods = calendars.days(date, date).firstOrNull()?.periods ?: calendars.week().week[date.dayOfWeek].orEmpty()
        val end = periods.maxOfOrNull { it.end }
        return end?.let { date.atTime(it).atZone(zone).toInstant() } ?: date
            .plusDays(1)
            .atStartOfDay(zone)
            .toInstant()
            .minusSeconds(1)
    }

    /** The working days after the day of [start] up to and including the day of [end], in the tenant's zone: 0 on the same day. */
    fun between(
        start: Instant,
        end: Instant,
    ): Int = calendars.workingDays(dayOf(start), dayOf(end))

    /** Today on the tenant's calendar, in its zone. */
    fun today(): LocalDate = org.today()

    /** [time] on [date] in the tenant's zone. */
    fun at(
        date: LocalDate,
        time: java.time.LocalTime,
    ): Instant = date.atTime(time).atZone(org.zone()).toInstant()

    /** [instant]'s day on the tenant's calendar. */
    fun dayOf(instant: Instant): LocalDate = instant.atZone(org.zone()).toLocalDate()
}
