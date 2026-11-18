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
}
