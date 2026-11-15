package com.memo.memo.internal

import com.memo.common.authz.ForbiddenException
import com.memo.common.authz.Permission
import com.memo.common.context.Actor
import com.memo.common.context.ActorContext
import com.memo.common.tenant.TenantDsl
import com.memo.identity.UserSummaries
import com.memo.jooq.tables.references.MEMO_TYPE
import com.memo.org.OrgDirectory
import com.memo.org.OrgUnitDirectory
import com.memo.workflow.DecidedInstance
import com.memo.workflow.TaskItem
import com.memo.workflow.WorkflowTeamQueries
import io.swagger.v3.oas.annotations.media.Schema
import org.jooq.Condition
import org.jooq.impl.DSL
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.UUID
import kotlin.math.ceil
import com.memo.jooq.tables.references.MEMO_ as MEMO

data class AgeBucket(
    /** `0-2`, `3-5`, `6-10` or `10+` working days. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val bucket: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val count: Int,
)

data class PersonPending(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val person: Named,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val pending: Int,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val overdue: Int,
)

data class CycleTime(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val type: Named,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val decided: Int,
    /** Days from submission to the final decision. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val medianDays: Double,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val p90Days: Double,
)

data class WeekThroughput(
    /** The Monday the week starts on. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val week: LocalDate,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val created: Int,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val completed: Int,
)

/** The approvals dashboard (design E05 §6, flow K) for the viewer (`me`) or a unit and its subtree. */
data class ApprovalsDashboard(
    /** `me` or `unit`. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val scope: String,
    val unit: Named?,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val from: LocalDate,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val to: LocalDate,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val pendingByAge: List<AgeBucket>,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val overdue: Int,
    /** Unit scope only: open tasks by person in the unit. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val pendingByPerson: List<PersonPending>,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val cycleTimes: List<CycleTime>,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val throughput: List<WeekThroughput>,
)

/**
 * The approvals dashboard (design E05 §6): pending tasks by age in working days, overdue tasks, cycle time per memo type
 * (median and P90, submission to final decision) and memos created and completed per week. `me` is anyone's own work;
 * a unit needs its head seat or `memo.dashboard.read` covering it. Memo figures count only memos the viewer may read
 * (`me`: those they take part in; a unit: those from the unit that they may read); pending tasks count the people seated
 * in the unit. Inside a tenant transaction.
 */
@Service
class MemoDashboards(
    private val scopes: TeamScopes,
    private val team: WorkflowTeamQueries,
    private val readable: MemoReadable,
    private val users: UserSummaries,
    private val units: OrgUnitDirectory,
    private val org: OrgDirectory,
    private val tenantDsl: TenantDsl,
    private val clock: Clock,
) {
    fun approvals(
        scope: String,
        from: LocalDate?,
        to: LocalDate?,
    ): ApprovalsDashboard {
        val viewer = viewer()
        val end = to ?: org.today()
        val start = from ?: end.minusWeeks(DEFAULT_WEEKS).plusDays(1)
        require(!start.isAfter(end) && ChronoUnit.DAYS.between(start, end) <= MAX_DAYS) { "from must be before to, at most a year" }
        val unit = unitOf(scope)
        val (people, memos) =
            if (unit == null) {
                setOf(viewer) to DSL.exists(readable.participant(MEMO.ID, viewer)).and(readable.condition(viewer))
            } else {
                val units = scopes.narrowed(scopes.of(viewer, Permission.MEMO_DASHBOARD_READ), unit, Permission.MEMO_DASHBOARD_READ)
                scopes.seated(units).keys to MEMO.ORIGIN_UNIT_ID.`in`(units).and(readable.condition(viewer))
            }
        val open = team.allOpenTasksOf(people)
        return ApprovalsDashboard(
            scope = if (unit == null) "me" else "unit",
            unit = unit?.let { id -> units.units(listOf(id))[id]?.let { Named(id, it.name) } },
            from = start,
            to = end,
            pendingByAge = ages(open),
            overdue = open.count { it.dueAt?.isBefore(clock.instant()) == true },
            pendingByPerson = if (unit == null) emptyList() else byPerson(open),
            cycleTimes = cycleTimes(decided(start, end, memos)),
            throughput = throughput(start, end, memos),
        )
    }

    /** The unit of `unit:{id}`; null for `me`; anything else is a bad request. */
    private fun unitOf(scope: String): UUID? =
        when {
            scope == "me" -> {
                null
            }

            scope.startsWith("unit:") -> {
                runCatching {
                    UUID.fromString(
                        scope.removePrefix("unit:"),
                    )
                }.getOrElse { throw IllegalArgumentException("scope is me or unit:{id}") }
            }

            else -> {
                throw IllegalArgumentException("scope is me or unit:{id}")
            }
        }

    private fun ages(open: List<TaskItem>): List<AgeBucket> {
        val today = org.today()
        val ages = open.map { team.workingDays(LocalDate.ofInstant(it.createdAt, org.zone()), today) }
        return BUCKETS.map { (label, range) -> AgeBucket(label, ages.count { it in range }) }
    }

    private fun byPerson(open: List<TaskItem>): List<PersonPending> {
        val now = clock.instant()
        val names = users.of(open.mapNotNull { it.assigneeId }.toSet())
        return open
            .filter { it.assigneeId != null }
            .groupBy { requireNotNull(it.assigneeId) }
            .map { (person, tasks) ->
                PersonPending(
                    Named(person, names[person]?.displayName.orEmpty()),
                    tasks.size,
                    tasks.count { it.dueAt?.isBefore(now) == true },
                )
            }.sortedWith(compareByDescending<PersonPending> { it.pending }.thenBy { it.person.name })
    }

    /** Memos decided between [start] and [end] (whole days in the tenant's zone) that [memos] allows, with their types. */
    private fun decided(
        start: LocalDate,
        end: LocalDate,
        memos: Condition,
    ): List<Pair<DecidedInstance, Named>> {
        val zone = org.zone()
        val instances =
            team.decided(MemoWorkflowSubject.TYPE, start.atStartOfDay(zone).toInstant(), end.plusDays(1).atStartOfDay(zone).toInstant())
        if (instances.isEmpty()) return emptyList()
        val types =
            tenantDsl
                .current()
                .select(MEMO.ID, MEMO_TYPE.ID, MEMO_TYPE.NAME)
                .from(MEMO)
                .join(MEMO_TYPE)
                .on(MEMO_TYPE.ID.eq(MEMO.MEMO_TYPE_ID))
                .where(MEMO.ID.`in`(instances.map { it.subjectId }), memos)
                .fetch()
                .associate { requireNotNull(it.value1()) to Named(requireNotNull(it.value2()), it.value3().orEmpty()) }
        return instances.mapNotNull { instance -> types[instance.subjectId]?.let { instance to it } }
    }

    private fun cycleTimes(decided: List<Pair<DecidedInstance, Named>>): List<CycleTime> =
        decided
            .groupBy({ it.second }, { Duration.between(it.first.submittedAt, it.first.decidedAt).toMinutes() / MINUTES_PER_DAY })
            .map { (type, days) ->
                val sorted = days.sorted()
                CycleTime(type, sorted.size, percentile(sorted, MEDIAN), percentile(sorted, P90))
            }.sortedBy { it.type.name }

    /** Memos created and completed per week (weeks from Monday) from [start] to [end]. */
    private fun throughput(
        start: LocalDate,
        end: LocalDate,
        memos: Condition,
    ): List<WeekThroughput> {
        val zone = org.zone()
        val created =
            tenantDsl
                .current()
                .select(MEMO.CREATED_AT)
                .from(MEMO)
                .where(
                    MEMO.CREATED_AT.ge(start.atStartOfDay(zone).toOffsetDateTime()),
                    MEMO.CREATED_AT.lt(end.plusDays(1).atStartOfDay(zone).toOffsetDateTime()),
                    memos,
                ).fetch { week(requireNotNull(it.value1()).atZoneSameInstant(zone).toLocalDate()) }
                .groupingBy { it }
                .eachCount()
        val completed = decided(start, end, memos).groupingBy { week(LocalDate.ofInstant(it.first.decidedAt, zone)) }.eachCount()
        return generateSequence(week(start)) { it.plusWeeks(1) }
            .takeWhile { !it.isAfter(end) }
            .map { WeekThroughput(it, created[it] ?: 0, completed[it] ?: 0) }
            .toList()
    }

    private fun week(day: LocalDate): LocalDate = day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    private fun viewer(): UUID = checkNotNull((ActorContext.current().actor as? Actor.User)?.userId) { "dashboards need a signed-in user" }

    private companion object {
        const val DEFAULT_WEEKS = 12L
        const val MAX_DAYS = 366L
        const val MINUTES_PER_DAY = 1440.0
        const val MEDIAN = 0.5
        const val P90 = 0.9
        val BUCKETS = listOf("0-2" to 0..2, "3-5" to 3..5, "6-10" to 6..10, "10+" to 11..Int.MAX_VALUE)

        /** The nearest-rank percentile of [sorted] (non-empty), in days rounded to one decimal. */
        fun percentile(
            sorted: List<Double>,
            p: Double,
        ): Double {
            val index = (ceil(p * sorted.size).toInt() - 1).coerceIn(0, sorted.size - 1)
            return Math.round(sorted[index] * ROUNDING) / ROUNDING
        }

        const val ROUNDING = 10.0
    }
}
