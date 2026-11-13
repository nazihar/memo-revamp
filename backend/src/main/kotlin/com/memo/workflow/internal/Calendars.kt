package com.memo.workflow.internal

import com.memo.audit.AuditLog
import com.memo.common.concurrency.ConcurrentModificationException
import com.memo.common.tenant.TenantDsl
import com.memo.jooq.tables.references.WORKFLOW_CALENDAR
import com.memo.jooq.tables.references.WORKFLOW_CALENDAR_DAY
import com.memo.org.OrgDirectory
import com.memo.workflow.calendar.BusinessCalendar
import com.memo.workflow.calendar.WorkingPeriod
import org.jooq.JSONB
import org.springframework.stereotype.Component
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneOffset

/** The weekly hours as stored and served: weekday → periods as `["09:00", "17:00"]` pairs. */
typealias Week = Map<DayOfWeek, List<WorkingPeriod>>

/** A tenant's weekly hours, with [defaulted] true while nobody has set them. */
data class CalendarWeek(
    val week: Week,
    val version: Long,
    val defaulted: Boolean,
)

/** A dated exception: other working periods on [day], or none (closed). */
data class CalendarDay(
    val day: LocalDate,
    val periods: List<WorkingPeriod>,
    val note: String?,
)

/** A refused calendar change: 400 with the reason. */
class CalendarInvalidException(
    message: String,
) : IllegalArgumentException(message)

/**
 * The tenant's business calendar (ADR-0041, design E05 §4.1): weekly working periods and dated exceptions, in the
 * tenant's time zone. Changes are audited (dates and counts only) and apply to due times computed afterwards. Inside a
 * tenant transaction.
 */
@Component
class Calendars(
    private val tenantDsl: TenantDsl,
    private val directory: OrgDirectory,
    private val audit: AuditLog,
    private val json: JsonMapper,
    private val clock: Clock,
) {
    fun week(): CalendarWeek {
        val row = tenantDsl.current().selectFrom(WORKFLOW_CALENDAR).fetchOne()
        return row?.let { CalendarWeek(readWeek(it.week.data()), it.version ?: 0, defaulted = false) }
            ?: CalendarWeek(BusinessCalendar.DEFAULT_WEEK, 0, defaulted = true)
    }

    /** Replaces the weekly hours; [version] is the client's `If-Match` (0 while defaulted). */
    fun replaceWeek(
        version: Long,
        week: Week,
    ): CalendarWeek {
        week.values.forEach(::check)
        val dsl = tenantDsl.current()
        val body = JSONB.valueOf(json.writeValueAsString(writeWeek(week)))
        val now = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC)
        val changed =
            if (dsl.fetchExists(WORKFLOW_CALENDAR)) {
                dsl
                    .update(WORKFLOW_CALENDAR)
                    .set(WORKFLOW_CALENDAR.WEEK, body)
                    .set(WORKFLOW_CALENDAR.VERSION, WORKFLOW_CALENDAR.VERSION.plus(1))
                    .set(WORKFLOW_CALENDAR.UPDATED_AT, now)
                    .where(WORKFLOW_CALENDAR.VERSION.eq(version))
                    .execute()
            } else {
                if (version != 0L) throw ConcurrentModificationException("workflow calendar", version)
                dsl
                    .insertInto(WORKFLOW_CALENDAR)
                    .set(WORKFLOW_CALENDAR.TENANT_ID, tenantDsl.tenant().value)
                    .set(WORKFLOW_CALENDAR.WEEK, body)
                    .set(WORKFLOW_CALENDAR.VERSION, 1)
                    .set(WORKFLOW_CALENDAR.UPDATED_AT, now)
                    .onConflictDoNothing()
                    .execute()
            }
        if (changed != 1) throw ConcurrentModificationException("workflow calendar", version)
        audit.record(
            "workflow.calendar.updated",
            OBJECT_TYPE,
            tenantDsl.tenant().toString(),
            after =
                mapOf("workingDays" to week.count { it.value.isNotEmpty() }),
        )
        return week()
    }

    /** The exceptions from [from] to [to], inclusive. */
    fun days(
        from: LocalDate,
        to: LocalDate,
    ): List<CalendarDay> =
        tenantDsl
            .current()
            .selectFrom(WORKFLOW_CALENDAR_DAY)
            .where(WORKFLOW_CALENDAR_DAY.DAY.between(from, to))
            .orderBy(WORKFLOW_CALENDAR_DAY.DAY)
            .fetch { CalendarDay(it.day, readPeriods(it.periods.data()), it.note) }

    /** Sets [from] to [to] (one day when [to] is null) to [periods] (none: closed), at most [MAX_RANGE] days at once. */
    fun setDays(
        from: LocalDate,
        to: LocalDate?,
        periods: List<WorkingPeriod>,
        note: String?,
    ): List<CalendarDay> {
        val last = to ?: from
        if (last.isBefore(from) || from.plusDays(MAX_RANGE.toLong() - 1).isBefore(last)) {
            throw CalendarInvalidException("A range runs forwards, at most $MAX_RANGE days.")
        }
        if ((note?.length ?: 0) > MAX_NOTE) throw CalendarInvalidException("A note has at most $MAX_NOTE characters.")
        check(periods)
        val dsl = tenantDsl.current()
        val body = JSONB.valueOf(json.writeValueAsString(periods.map(::pair)))
        generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(last) }.forEach { day ->
            dsl
                .insertInto(WORKFLOW_CALENDAR_DAY)
                .set(WORKFLOW_CALENDAR_DAY.TENANT_ID, tenantDsl.tenant().value)
                .set(WORKFLOW_CALENDAR_DAY.DAY, day)
                .set(WORKFLOW_CALENDAR_DAY.PERIODS, body)
                .set(WORKFLOW_CALENDAR_DAY.NOTE, note)
                .onConflict(WORKFLOW_CALENDAR_DAY.TENANT_ID, WORKFLOW_CALENDAR_DAY.DAY)
                .doUpdate()
                .set(WORKFLOW_CALENDAR_DAY.PERIODS, body)
                .set(WORKFLOW_CALENDAR_DAY.NOTE, note)
                .execute()
        }
        val details = mapOf("from" to from.toString(), "to" to last.toString(), "closed" to periods.isEmpty())
        audit.record("workflow.calendar.day_set", OBJECT_TYPE, tenantDsl.tenant().toString(), after = details)
        return days(from, last)
    }

    /** Removes the exception on [day]: it follows the weekly hours again. */
    fun removeDay(day: LocalDate) {
        val removed =
            tenantDsl
                .current()
                .deleteFrom(WORKFLOW_CALENDAR_DAY)
                .where(WORKFLOW_CALENDAR_DAY.DAY.eq(day))
                .execute()
        if (removed > 0) {
            audit.record(
                "workflow.calendar.day_removed",
                OBJECT_TYPE,
                tenantDsl.tenant().toString(),
                after = mapOf("day" to day.toString()),
            )
        }
    }

    /** The calendar for due times from today on: the weekly hours, the exceptions ahead and the tenant's zone. */
    fun calendar(): BusinessCalendar {
        val today = directory.today()
        val ahead = days(today.minusDays(1), today.plusDays(BusinessCalendar.MAX_DAYS.toLong()))
        return BusinessCalendar(directory.zone(), week().week, ahead.associate { it.day to it.periods })
    }

    private fun check(periods: List<WorkingPeriod>) {
        if (periods.size > MAX_PERIODS) throw CalendarInvalidException("At most $MAX_PERIODS working periods a day.")
        val sorted = periods.sortedBy { it.start }
        if (sorted.zipWithNext().any { (a, b) -> b.start.isBefore(a.end) }) {
            throw CalendarInvalidException("Working periods of a day do not overlap.")
        }
    }

    private fun readWeek(text: String): Week =
        json
            .readValue(text, WEEK)
            .mapNotNull { (day, pairs) ->
                runCatching { DayOfWeek.valueOf(day) }.getOrNull()?.let { it to pairs.map(::period) }
            }.toMap()

    private fun writeWeek(week: Week): Map<String, List<List<String>>> = week.mapKeys { it.key.name }.mapValues { (_, p) -> p.map(::pair) }

    private fun readPeriods(text: String): List<WorkingPeriod> = json.readValue(text, PERIODS).map(::period)

    companion object {
        const val OBJECT_TYPE = "workflow_calendar"
        const val MAX_RANGE = 366
        const val MAX_PERIODS = 4
        const val MAX_NOTE = 200
        private val WEEK = object : TypeReference<Map<String, List<List<String>>>>() {}
        private val PERIODS = object : TypeReference<List<List<String>>>() {}

        fun period(pair: List<String>): WorkingPeriod {
            if (pair.size != 2) throw CalendarInvalidException("A working period is a start and an end.")
            return runCatching { WorkingPeriod(LocalTime.parse(pair[0]), LocalTime.parse(pair[1])) }
                .getOrElse { throw CalendarInvalidException("A working period runs from HH:mm to a later HH:mm.") }
        }

        fun pair(period: WorkingPeriod): List<String> = listOf(period.start.toString(), period.end.toString())
    }
}
