package com.memo.workflow

import com.memo.org.OrgDirectory
import com.memo.workflow.calendar.WorkingDayRules
import com.memo.workflow.internal.Calendars
import org.springframework.stereotype.Service
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit

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
     * The calendar of the days from [from] to [to], read once (the weekly hours and that window's dated exceptions), as a
     * counter of working days between two of those days: many spans counted with one read (analytics' time to decision in
     * working days, design E08 §2.1 M3). A span reaching outside the window is counted on the calendar directly.
     */
    fun counter(
        from: LocalDate,
        to: LocalDate,
    ): WorkingDayCounter {
        if (to.isBefore(from)) return WorkingDayCounter { a, b -> calendars.workingDays(a, b) }
        val week = calendars.week().week
        val exceptions = calendars.days(from, to).associate { it.day to it.periods.isNotEmpty() }
        val days =
            ChronoUnit.DAYS
                .between(from, to)
                .toInt() + 1
        // working[i]: the working days among from+1 .. from+i (prefix sums), so a span (a, b] is working[b] - working[a].
        val working = IntArray(days)
        for (i in 1 until days) {
            val day = from.plusDays(i.toLong())
            val open = exceptions[day] ?: week[day.dayOfWeek].orEmpty().isNotEmpty()
            working[i] = working[i - 1] + if (open) 1 else 0
        }
        return WorkingDayCounter { a, b ->
            when {
                !b.isAfter(a) -> {
                    0
                }

                a.isBefore(from) || b.isAfter(to) -> {
                    calendars.workingDays(a, b)
                }

                else -> {
                    working[ChronoUnit.DAYS.between(from, b).toInt()] -
                        working[ChronoUnit.DAYS.between(from, a).toInt()]
                }
            }
        }
    }

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
     * The [n]-th working day after [date] on the tenant's calendar, [date] not counted (design E06 §1.6.3: the minutes'
     * comment deadline; design E07 §1.5); [date] itself when [n] is 0, and null when the calendar has no working day in the
     * next two years.
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

/**
 * Working days between two days on the tenant's calendar ([WorkingDays.counter]): the working days after [from] up to and
 * including [to], 0 when [to] is not after [from].
 */
fun interface WorkingDayCounter {
    fun between(
        from: LocalDate,
        to: LocalDate,
    ): Int
}
