package com.memo.workflow.internal

import com.github.kagkarlsson.scheduler.SchedulerClient
import com.github.kagkarlsson.scheduler.task.helper.OneTimeTask
import com.github.kagkarlsson.scheduler.task.helper.RecurringTask
import com.github.kagkarlsson.scheduler.task.schedule.Schedules
import com.memo.common.context.Actor
import com.memo.common.jobs.ActiveTenants
import com.memo.common.jobs.Jobs
import com.memo.common.jobs.TenantScopedPayload
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantDsl
import com.memo.common.tenant.TenantTransactions
import com.memo.jooq.tables.references.WORKFLOW_HOLDER_SYNC
import com.memo.jooq.tables.references.WORKFLOW_INSTANCE
import com.memo.jooq.tables.references.WORKFLOW_STEP
import com.memo.org.OrgDirectory
import com.memo.org.OrgHolderChanges
import com.memo.workflow.InstanceStatus
import com.memo.workflow.SlotHolder
import com.memo.workflow.WorkflowRefusedException
import com.memo.workflow.WorkflowSubject
import com.memo.workflow.engine.Reresolve
import com.memo.workflow.engine.StaffChanges
import org.jooq.Condition
import org.jooq.JSONB
import org.jooq.impl.DSL
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Component
import org.springframework.stereotype.Repository
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * The day a tenant's open position steps were last brought in line with the holders on that day (`workflow_holder_sync`,
 * one row per tenant), inside a tenant transaction. The row is locked while a run plans, so two nodes never plan a
 * tenant's day together.
 */
@Repository
class HolderSyncStore(
    private val tenantDsl: TenantDsl,
) {
    /** Locks the tenant's row, creating it at null first; the day last synced, or null before the first run. */
    fun lock(): LocalDate? {
        val s = WORKFLOW_HOLDER_SYNC
        tenantDsl
            .current()
            .insertInto(s)
            .set(s.TENANT_ID, tenantDsl.tenant().value)
            .onConflictDoNothing()
            .execute()
        return tenantDsl
            .current()
            .select(s.SYNCED_ON)
            .from(s)
            .where(s.TENANT_ID.eq(tenantDsl.tenant().value))
            .forUpdate()
            .fetchOne(s.SYNCED_ON)
    }

    /** Records [day] as synced at [at]; never moves the day back. */
    fun synced(
        day: LocalDate,
        at: OffsetDateTime,
    ) {
        val s = WORKFLOW_HOLDER_SYNC
        tenantDsl
            .current()
            .update(s)
            .set(s.SYNCED_ON, day)
            .set(s.SYNCED_AT, at)
            .where(s.SYNCED_ON.isNull.or(s.SYNCED_ON.lt(day)))
            .execute()
    }
}

/**
 * Position steps follow holder changes that take effect on a date (ADR-0068 amendment, 2026-11-28). [StaffChangeListener]
 * re-resolves a step when an assignment is edited; a term that starts or ends on a recorded date (a holder change recorded
 * for next Monday, a term ending) announces nothing on that day. So once per tenant day, at the start of the day in the
 * tenant's zone, every active step resolved from a position whose holder changed since the last run, and whose recorded
 * resolution no longer matches the holders on that day, resolves again. The first run, or one after more than
 * [MAX_CATCH_UP_DAYS] days, checks every active position step.
 *
 * Re-resolving is the holder-change path of ADR-0040 ([Reresolve], `HOLDER_CHANGED`): the new holder gets the task (or
 * their substitute, acting for them), a vacant position leaves the step to its `fallback` or to an administrator
 * (`UNRESOLVED`), a pool keeps the claim of a member still in it, a named route entry keeps its person. Recorded as a
 * reassignment by the workflow ([TimerEscalations.WORKFLOW_ACTOR]) and audited as `workflow.task.reassigned` with the
 * cause `HOLDER_CHANGED`, by the system actor of the task. Each step moves in a transaction of its own, after checking
 * again that it is still out of line, so a second run (or a second node) changes nothing.
 */
@Component
class DatedHolderChanges(
    private val tenantDsl: TenantDsl,
    private val transactions: TenantTransactions,
    private val state: HolderSyncStore,
    private val directory: OrgDirectory,
    private val holderChanges: OrgHolderChanges,
    private val subjects: List<WorkflowSubject>,
    private val runner: WorkflowRunner,
    private val json: JsonMapper,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** Brings the current tenant's open position steps in line with today's holders; how many steps resolved again. */
    fun daily(): Int {
        val tenant = TenantContext.current()
        val plan = transactions.inTenant(tenant) { plan() } ?: return 0
        val moved = plan.steps.count { step -> transactions.inTenant(tenant) { move(step, plan.day) } }
        transactions.inTenant(tenant) { state.synced(plan.day, OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC)) }
        return moved
    }

    /** Today's run, or null when the tenant's day already ran: the steps out of line with the holders today. */
    private fun plan(): Plan? {
        val today = LocalDate.now(clock.withZone(directory.zone()))
        val last = state.lock()
        if (last != null && !today.isAfter(last)) return null
        val steps = candidates(last, today)
        val held = directory.holders(steps.flatMap { it.positions }.toSet(), today)
        return Plan(today, steps.filter { outOfLine(it, held, today) })
    }

    /**
     * The active steps resolved from a position whose holder changed after [last] up to [today]; every active position
     * step on the first run or after a gap of more than [MAX_CATCH_UP_DAYS] days.
     */
    private fun candidates(
        last: LocalDate?,
        today: LocalDate,
    ): List<PositionStep> {
        if (last == null || ChronoUnit.DAYS.between(last, today) > MAX_CATCH_UP_DAYS) return positionSteps(ANY_POSITION)
        return holderChanges
            .between(last, today)
            .chunked(CHUNK)
            .flatMap { chunk -> positionSteps(DSL.or(chunk.flatMap(::resolvedFrom))) }
            .distinctBy { it.stepId }
    }

    /** Resolves [step] again if it is still out of line with the holders on [day]; whether it did. */
    private fun move(
        step: PositionStep,
        day: LocalDate,
    ): Boolean {
        val current = positionSteps(WORKFLOW_STEP.ID.eq(step.stepId)).singleOrNull()
        val due = current != null && outOfLine(current, directory.holders(current.positions, day), day)
        return due && reresolve(checkNotNull(current), day)
    }

    private fun reresolve(
        current: PositionStep,
        day: LocalDate,
    ): Boolean =
        try {
            runner.staffChange(current.instanceId, Actor.User(TimerEscalations.WORKFLOW_ACTOR), day) {
                Reresolve(it, current.stepId, StaffChanges.HOLDER_CHANGED)
            }
            true
        } catch (refused: WorkflowRefusedException) {
            // A named route entry keeps its person; an item that moved on meanwhile has nothing to resolve.
            log.info("Dated holder change not applied to an item: {}", refused.reason)
            false
        }

    /**
     * Whether [step]'s recorded resolution differs from what it would be on [day], position by position: a position's
     * holder then ([held]), or for a slot of the item (ADR-0068) the people it names now with its positions' holders then.
     */
    private fun outOfLine(
        step: PositionStep,
        held: Map<UUID, UUID>,
        day: LocalDate,
    ): Boolean =
        step.entries.any { entry ->
            when {
                entry.slot != null && entry.positionIds.isNotEmpty() -> slotPeople(step, entry.slot, held, day) != entry.people
                entry.positionId != null -> setOfNotNull(held[entry.positionId]) != entry.people
                else -> false
            }
        }

    private fun slotPeople(
        step: PositionStep,
        slot: String,
        held: Map<UUID, UUID>,
        day: LocalDate,
    ): Set<UUID> {
        val subject = subjects.singleOrNull { it.type == step.subjectType } ?: return emptySet()
        val named = subject.slot(step.subjectId, slot)
        val unknown =
            named
                .filterIsInstance<SlotHolder.Position>()
                .map { it.positionId }
                .filter { it !in held }
        val all = if (unknown.isEmpty()) held else held + directory.holders(unknown, day)
        val people = OrgResolvers.peopleOf(named, all)
        return if (people.size > OrgResolvers.MAX_CANDIDATES) emptySet() else people.toSet()
    }

    /** Active steps of running instances matching [condition] whose resolution read a position, with what it recorded. */
    private fun positionSteps(condition: Condition): List<PositionStep> =
        tenantDsl
            .current()
            .select(
                WORKFLOW_STEP.INSTANCE_ID,
                WORKFLOW_STEP.ID,
                WORKFLOW_INSTANCE.SUBJECT_TYPE,
                WORKFLOW_INSTANCE.SUBJECT_ID,
                WORKFLOW_STEP.RESOLUTION,
            ).from(WORKFLOW_STEP)
            .join(WORKFLOW_INSTANCE)
            .on(WORKFLOW_INSTANCE.TENANT_ID.eq(WORKFLOW_STEP.TENANT_ID), WORKFLOW_INSTANCE.ID.eq(WORKFLOW_STEP.INSTANCE_ID))
            .where(WORKFLOW_STEP.STATE.eq(ACTIVE), WORKFLOW_INSTANCE.STATUS.eq(InstanceStatus.RUNNING.name), ANY_POSITION, condition)
            .fetch { r ->
                PositionStep(
                    r.value1()!!,
                    r.value2()!!,
                    r.value3()!!,
                    r.value4()!!,
                    entries(json.readValue(r.value5()!!.data(), RESOLUTION)),
                )
            }

    /** The resolution's entries that read a position (design E04 §4.1: `resolved[]` with `positionId` or `positionIds`). */
    private fun entries(resolution: Map<String, Any?>): List<Entry> =
        (resolution["resolved"] as? List<*>)
            .orEmpty()
            .filterIsInstance<Map<*, *>>()
            .map { entry ->
                Entry(
                    positionId = (entry["positionId"] as? String)?.let(UUID::fromString),
                    positionIds = ids(entry["positionIds"]),
                    slot = entry["slot"] as? String,
                    people = ids(entry["people"]).toSet(),
                )
            }.filter { it.positionId != null || it.positionIds.isNotEmpty() }

    private fun ids(value: Any?): List<UUID> = (value as? List<*>).orEmpty().filterIsInstance<String>().map(UUID::fromString)

    /** A step resolved from [position], as a position assignee or within a slot (as [StaffChangeListener] finds them). */
    private fun resolvedFrom(position: UUID): List<Condition> =
        listOf(
            DSL.condition(
                "{0} @> {1}",
                WORKFLOW_STEP.RESOLUTION,
                DSL.value(JSONB.valueOf("""{"resolved":[{"positionId":"$position"}]}""")),
            ),
            DSL.condition(
                "{0} @> {1}",
                WORKFLOW_STEP.RESOLUTION,
                DSL.value(JSONB.valueOf("""{"resolved":[{"positionIds":["$position"]}]}""")),
            ),
        )

    private data class Plan(
        val day: LocalDate,
        val steps: List<PositionStep>,
    )

    private data class PositionStep(
        val instanceId: UUID,
        val stepId: UUID,
        val subjectType: String,
        val subjectId: UUID,
        val entries: List<Entry>,
    ) {
        val positions: Set<UUID> get() = entries.flatMap { listOfNotNull(it.positionId) + it.positionIds }.toSet()
    }

    private data class Entry(
        val positionId: UUID?,
        val positionIds: List<UUID>,
        val slot: String?,
        val people: Set<UUID>,
    )

    companion object {
        /** Longer gaps than this check every active position step rather than the positions that changed holder. */
        const val MAX_CATCH_UP_DAYS = 31L
        private const val ACTIVE = "ACTIVE"
        private const val CHUNK = 100
        private val RESOLUTION = object : TypeReference<Map<String, Any?>>() {}

        /** A resolution that read a position at all (the catch-up's filter, and a guard on every query). */
        private val ANY_POSITION: Condition =
            DSL.condition(
                "jsonb_path_exists({0}, {1}::jsonpath)",
                WORKFLOW_STEP.RESOLUTION,
                DSL.value("\$.resolved[*] ? (exists(@.positionId) || exists(@.positionIds))"),
            )
    }
}

/** Task data of one tenant's run of [DatedHolderChanges]. */
data class HolderSyncTask(
    override val tenantId: UUID,
) : TenantScopedPayload

/**
 * The dated holder tasks (ADR-0018): a platform sweep every [EVERY] fans out to one `workflow-position-holders` per active
 * tenant, run as the system. A tenant's run does its work once a day, at the first sweep of the tenant's new day, so open
 * position steps follow a holder change recorded for a date within [EVERY] of midnight in the tenant's zone. The sweep
 * is on unless `memo.workflow.holder-sweep` is false (integration tests run the day themselves on a moved clock).
 */
@Configuration(proxyBeanMethods = false)
class DatedHolderJobs {
    @Bean
    fun workflowPositionHoldersTask(
        jobs: Jobs,
        holders: ObjectProvider<DatedHolderChanges>,
    ): OneTimeTask<HolderSyncTask> = jobs.tenantTask(TASK, HolderSyncTask::class.java) { holders.getObject().daily() }

    @Bean
    @ConditionalOnProperty("memo.workflow.holder-sweep", havingValue = "true", matchIfMissing = true)
    fun workflowPositionHoldersSweep(
        jobs: Jobs,
        tenants: ActiveTenants,
        scheduler: ObjectProvider<SchedulerClient>,
        task: ObjectProvider<OneTimeTask<HolderSyncTask>>,
    ): RecurringTask<Void> =
        jobs.platformTask("$TASK-sweep", Schedules.fixedDelay(EVERY)) {
            val client = scheduler.getObject()
            val work = task.getObject()
            tenants.ids().forEach { tenant ->
                client.scheduleIfNotExists(work.schedulableInstance(tenant.toString(), HolderSyncTask(tenant.value)))
            }
        }

    private companion object {
        const val TASK = "workflow-position-holders"
        val EVERY: Duration = Duration.ofMinutes(15)
    }
}
