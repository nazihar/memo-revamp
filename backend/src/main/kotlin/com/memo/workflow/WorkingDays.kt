package com.memo.workflow

import com.memo.org.OrgDirectory
import com.memo.workflow.internal.Calendars
import org.springframework.stereotype.Service
import java.time.Instant

/** The tenant's business calendar for other modules (ADR-0041): how many working days have passed. Inside a tenant transaction. */
@Service
class WorkingDays(
    private val calendars: Calendars,
    private val org: OrgDirectory,
) {
    /** The working days after the day of [start] up to and including today, in the tenant's zone: 0 on the day itself. */
    fun since(start: Instant): Int = calendars.workingDays(start.atZone(org.zone()).toLocalDate(), org.today())
}
