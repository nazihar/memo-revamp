package com.memo.workflow.internal

import com.memo.common.authz.Permission
import com.memo.common.authz.RequiresPermission
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantTransactions
import com.memo.common.web.etagOf
import com.memo.common.web.ifMatchVersion
import com.memo.org.OrgDirectory
import com.memo.workflow.calendar.SlaDuration
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate

data class CalendarResponse(
    /** The tenant's time zone (Administration → Language and region). */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val timeZone: String,
    /** Working periods per weekday (`MONDAY` … `SUNDAY`), each `["09:00", "17:00"]`; a missing day is not worked. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val week: Map<String, List<List<String>>>,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Send as If-Match on changes") val version: Long,
    /** True while nobody has set the hours: Sunday to Thursday, 9:00 to 17:00. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val defaulted: Boolean,
)

data class CalendarWeekRequest(
    val week: Map<String, List<List<String>>>,
)

data class CalendarDayResponse(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val day: LocalDate,
    /** Empty: closed (a holiday). */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val periods: List<List<String>>,
    val note: String?,
)

data class CalendarDaysRequest(
    val from: LocalDate,
    /** The last day of a range, inclusive; one day when left out. */
    val to: LocalDate? = null,
    /** Empty: closed. */
    val periods: List<List<String>> = emptyList(),
    val note: String? = null,
)

data class DuePreviewRequest(
    /** When the work starts; now when left out. */
    val start: Instant? = null,
    /** ISO 8601 days, hours and minutes, such as `P2D` or `PT4H`. */
    val due: String,
    val workingTime: Boolean = true,
)

data class DuePreviewResponse(
    /** Null when the calendar has no working time ahead. */
    val due: Instant?,
)

/**
 * The tenant's business calendar (design E05 §5): weekly working hours, holidays and other special days, and a preview
 * of when an SLA would be due; for holders of `workflow.calendar.manage`.
 */
@RestController
@RequestMapping("/api/v1/admin/workflow/calendar")
@Tag(name = "workflow")
class CalendarAdminController(
    private val calendars: Calendars,
    private val directory: OrgDirectory,
    private val transactions: TenantTransactions,
    private val clock: Clock,
) {
    @GetMapping
    @RequiresPermission(Permission.WORKFLOW_CALENDAR_MANAGE)
    @Operation(operationId = "getWorkflowCalendar", summary = "The weekly working hours and time zone")
    fun get(): ResponseEntity<CalendarResponse> {
        val body = transactions.readInTenant(TenantContext.current()) { response(calendars.week()) }
        return ResponseEntity
            .ok()
            .cacheControl(CacheControl.noStore())
            .eTag(etagOf(body.version))
            .body(body)
    }

    @PutMapping
    @RequiresPermission(Permission.WORKFLOW_CALENDAR_MANAGE)
    @Operation(operationId = "replaceWorkflowCalendar", summary = "Set the weekly working hours")
    fun replace(
        @RequestHeader("If-Match", required = false) ifMatch: String?,
        @RequestBody body: CalendarWeekRequest,
    ): ResponseEntity<CalendarResponse> {
        val version = ifMatchVersion(ifMatch)
        val week =
            body.week
                .map { (day, pairs) ->
                    val weekday =
                        runCatching {
                            DayOfWeek.valueOf(
                                day,
                            )
                        }.getOrElse { throw CalendarInvalidException("Unknown weekday $day.") }
                    weekday to pairs.map(Calendars::period)
                }.toMap()
        val saved = transactions.inTenant(TenantContext.current()) { response(calendars.replaceWeek(version, week)) }
        return ResponseEntity.ok().eTag(etagOf(saved.version)).body(saved)
    }

    @GetMapping("/days")
    @RequiresPermission(Permission.WORKFLOW_CALENDAR_MANAGE)
    @Operation(operationId = "listWorkflowCalendarDays", summary = "Holidays and other special days between two dates")
    fun days(
        @RequestParam from: LocalDate,
        @RequestParam to: LocalDate,
    ): List<CalendarDayResponse> {
        if (to.isBefore(from) || from.plusDays(MAX_LIST_DAYS).isBefore(to)) {
            throw CalendarInvalidException("List at most $MAX_LIST_DAYS days at a time.")
        }
        return transactions.readInTenant(TenantContext.current()) { calendars.days(from, to).map(::day) }
    }

    @PostMapping("/days")
    @RequiresPermission(Permission.WORKFLOW_CALENDAR_MANAGE)
    @Operation(operationId = "setWorkflowCalendarDays", summary = "Set a day or a range: closed, or with its own hours")
    fun setDays(
        @RequestBody body: CalendarDaysRequest,
    ): List<CalendarDayResponse> =
        transactions.inTenant(TenantContext.current()) {
            calendars.setDays(body.from, body.to, body.periods.map(Calendars::period), body.note?.trim()?.ifEmpty { null }).map(::day)
        }

    @DeleteMapping("/days/{day}")
    @RequiresPermission(Permission.WORKFLOW_CALENDAR_MANAGE)
    @Operation(operationId = "removeWorkflowCalendarDay", summary = "Let a day follow the weekly hours again")
    fun removeDay(
        @PathVariable day: LocalDate,
    ): ResponseEntity<Void> {
        transactions.inTenant(TenantContext.current()) { calendars.removeDay(day) }
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/preview")
    @RequiresPermission(Permission.WORKFLOW_CALENDAR_MANAGE)
    @Operation(operationId = "previewWorkflowDue", summary = "When an SLA started at a time would be due")
    fun preview(
        @RequestBody body: DuePreviewRequest,
    ): DuePreviewResponse {
        val duration =
            runCatching {
                SlaDuration.parse(body.due)
            }.getOrElse { throw CalendarInvalidException("Use days, hours and minutes, such as P2D or PT4H.") }
        val start = body.start ?: clock.instant()
        return DuePreviewResponse(
            transactions.readInTenant(TenantContext.current()) {
                calendars.calendar().due(start, duration, body.workingTime)
            },
        )
    }

    private fun response(week: CalendarWeek) =
        CalendarResponse(
            directory.zone().id,
            week.week.mapKeys { it.key.name }.mapValues { (_, periods) -> periods.map(Calendars::pair) },
            week.version,
            week.defaulted,
        )

    private fun day(day: CalendarDay) = CalendarDayResponse(day.day, day.periods.map(Calendars::pair), day.note)

    private companion object {
        const val MAX_LIST_DAYS = 400L
    }
}
