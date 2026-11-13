package com.memo.workflow.internal

import com.github.kagkarlsson.scheduler.SchedulerClient
import com.github.kagkarlsson.scheduler.task.SchedulableInstance
import com.github.kagkarlsson.scheduler.task.helper.OneTimeTask
import com.memo.common.id.IdGenerator
import com.memo.common.jobs.Jobs
import com.memo.common.jobs.TenantScopedPayload
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantDsl
import com.memo.common.tenant.TenantTransactions
import com.memo.jooq.tables.references.WORKFLOW_INSTANCE
import com.memo.jooq.tables.references.WORKFLOW_TASK
import com.memo.jooq.tables.references.WORKFLOW_TIMER
import com.memo.workflow.TimerKind
import com.memo.workflow.WorkflowTimerFired
import com.memo.workflow.calendar.BusinessCalendar
import com.memo.workflow.calendar.SlaDuration
import com.memo.workflow.definition.Definition
import com.memo.workflow.definition.Sla
import com.memo.workflow.engine.Effect
import com.memo.workflow.engine.Outcome
import com.memo.workflow.engine.TaskKind
import com.memo.workflow.engine.TaskState
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/** The data of a timer's execution: the tenant and the timer, ids only (ADR-0018). */
data class TimerFire(
    override val tenantId: UUID,
    val timerId: UUID,
) : TenantScopedPayload

/** Fires a timer by id; split from [WorkflowTimers] so the scheduler task does not depend on it at construction. */
fun interface TimerFiring {
    fun fire(timerId: UUID)
}

@Configuration(proxyBeanMethods = false)
class WorkflowTimerConfig {
    @Bean
    fun workflowTimerTask(
        jobs: Jobs,
        firing: ObjectProvider<TimerFiring>,
    ): OneTimeTask<TimerFire> = jobs.tenantTask(TASK, TimerFire::class.java) { firing.getObject().fire(it.timerId) }

    companion object {
        const val TASK = "workflow-timer"
    }
}

/**
 * Timers of decision tasks on SLA steps (ADR-0041, design E05 §4.2, §4.3). [plan] runs in the action's transaction: new
 * tasks get their timers (each a `workflow_timer` row and one scheduled execution), a task that moved to someone else
 * passes them on, and closed tasks' timers are cancelled. [fire] does nothing unless the timer is still pending and its
 * task still open; then it publishes [WorkflowTimerFired], or escalates. Inside a tenant transaction, except [fire].
 */
@Component
class WorkflowTimers(
    private val tenantDsl: TenantDsl,
    private val transactions: TenantTransactions,
    private val ids: IdGenerator,
    private val calendars: Calendars,
    private val events: ApplicationEventPublisher,
    private val clock: Clock,
    @param:Qualifier("workflowTimerTask") private val task: OneTimeTask<TimerFire>,
    private val scheduler: ObjectProvider<SchedulerClient>,
) : TimerFiring {
    fun plan(
        instanceId: UUID,
        definition: Definition,
        outcome: Outcome,
        dueOf: (UUID) -> Instant?,
    ) {
        val closed = outcome.effects.filterIsInstance<Effect.TaskClosed>()
        val moving =
            closed
                .filter { it.state == TaskState.REASSIGNED || it.state == TaskState.TRANSFERRED }
                .mapNotNull { c -> outcome.state.findTask(c.taskId)?.let { (step, _) -> step.id to c.taskId } }
                .groupBy({ it.first }, { it.second })
                .mapValues { it.value.toMutableList() }
        val calendar by lazy { calendars.calendar() }
        val passedOn = mutableSetOf<UUID>()
        outcome.effects.filterIsInstance<Effect.TaskCreated>().filter { it.task.kind == TaskKind.DECIDE }.forEach { created ->
            val step =
                outcome.state.round.steps
                    .firstOrNull { it.id == created.stepId } ?: return@forEach
            val sla = definition.stepsById[step.nodeId]?.sla ?: return@forEach
            val from = moving[step.id]?.removeFirstOrNull()
            if (from != null) {
                move(from, created.task.id)
                passedOn += from
            } else {
                dueOf(step.id)?.let { due -> schedule(instanceId, step.id, created.task.id, sla, due) { calendar } }
            }
        }
        cancel(closed.map { it.taskId }.filterNot { it in passedOn })
    }

    /** Fires every pending timer due at the clock's now, oldest first (tests move the clock and call this). */
    fun runDue(limit: Int = RUN_LIMIT): Int {
        val due =
            transactions.readInTenant(TenantContext.current()) {
                tenantDsl
                    .current()
                    .select(WORKFLOW_TIMER.ID)
                    .from(WORKFLOW_TIMER)
                    .where(WORKFLOW_TIMER.STATE.eq(PENDING), WORKFLOW_TIMER.FIRES_AT.le(at(clock.instant())))
                    .orderBy(WORKFLOW_TIMER.FIRES_AT)
                    .limit(limit)
                    .fetch(WORKFLOW_TIMER.ID)
                    .filterNotNull()
            }
        due.forEach(::fire)
        return due.size
    }

    override fun fire(timerId: UUID) {
        transactions.inTenant(TenantContext.current()) {
            val dsl = tenantDsl.current()
            val timer =
                dsl
                    .selectFrom(WORKFLOW_TIMER)
                    .where(WORKFLOW_TIMER.ID.eq(timerId))
                    .forUpdate()
                    .fetchOne()
            if (timer == null || timer.state != PENDING) return@inTenant
            val holder =
                dsl
                    .select(WORKFLOW_TASK.STATE, WORKFLOW_TASK.ASSIGNEE_ID)
                    .from(WORKFLOW_TASK)
                    .where(WORKFLOW_TASK.ID.eq(timer.taskId))
                    .fetchOne()
            val open = holder?.value1() == TaskState.OPEN.name
            dsl
                .update(WORKFLOW_TIMER)
                .set(WORKFLOW_TIMER.STATE, if (open) FIRED else CANCELLED)
                .set(WORKFLOW_TIMER.FIRED_AT, at(clock.instant()))
                .where(WORKFLOW_TIMER.ID.eq(timerId))
                .execute()
            if (open) announce(timer.instanceId, timer.taskId, holder.value2(), TimerKind.valueOf(timer.kind), timer.ordinal ?: 0)
        }
    }

    private fun announce(
        instanceId: UUID,
        taskId: UUID,
        assignee: UUID?,
        kind: TimerKind,
        ordinal: Int,
    ) {
        val subject =
            tenantDsl
                .current()
                .select(WORKFLOW_INSTANCE.SUBJECT_TYPE, WORKFLOW_INSTANCE.SUBJECT_ID)
                .from(WORKFLOW_INSTANCE)
                .where(WORKFLOW_INSTANCE.ID.eq(instanceId))
                .fetchSingle()
        events.publishEvent(
            WorkflowTimerFired(
                tenantDsl.tenant().value,
                instanceId,
                subject.value1()!!,
                subject.value2()!!,
                taskId,
                assignee,
                kind,
                ordinal,
            ),
        )
    }

    /** The timers of [taskId] on an SLA step due at [due]: reminders, the due time and the escalation. */
    private fun schedule(
        instanceId: UUID,
        stepId: UUID,
        taskId: UUID,
        sla: Sla,
        due: Instant,
        calendar: () -> BusinessCalendar,
    ) {
        val now = clock.instant()
        val after = { iso: String -> calendar().due(due, SlaDuration.parse(iso), sla.inWorkingTime) }
        val before =
            sla.remind?.before?.let { iso ->
                val d = SlaDuration.parse(iso)
                due.minusSeconds((d.days * MINUTES_PER_DAY + d.minutes) * SECONDS_PER_MINUTE).takeIf { it.isAfter(now) }
            }
        val times =
            listOfNotNull(
                before?.let { Triple(TimerKind.REMIND_BEFORE, 0, it) },
                Triple(TimerKind.DUE, 0, due),
            ) +
                sla.remind
                    ?.after
                    .orEmpty()
                    .mapIndexedNotNull { i, iso -> after(iso)?.let { Triple(TimerKind.REMIND_AFTER, i + 1, it) } } +
                listOfNotNull(sla.escalate?.let { e -> after(e.after)?.let { Triple(TimerKind.ESCALATE, 0, it) } })
        times.forEach { (kind, ordinal, firesAt) -> insert(instanceId, stepId, taskId, kind, ordinal, firesAt) }
    }

    private fun insert(
        instanceId: UUID,
        stepId: UUID,
        taskId: UUID,
        kind: TimerKind,
        ordinal: Int,
        firesAt: Instant,
    ) {
        val id = ids.next()
        val tenant = tenantDsl.tenant()
        tenantDsl
            .current()
            .insertInto(WORKFLOW_TIMER)
            .set(WORKFLOW_TIMER.ID, id)
            .set(WORKFLOW_TIMER.TENANT_ID, tenant.value)
            .set(WORKFLOW_TIMER.INSTANCE_ID, instanceId)
            .set(WORKFLOW_TIMER.STEP_ID, stepId)
            .set(WORKFLOW_TIMER.TASK_ID, taskId)
            .set(WORKFLOW_TIMER.KIND, kind.name)
            .set(WORKFLOW_TIMER.ORDINAL, ordinal)
            .set(WORKFLOW_TIMER.FIRES_AT, at(firesAt))
            .set(WORKFLOW_TIMER.STATE, PENDING)
            .execute()
        // In the action's transaction (ADR-0018): a rolled-back action schedules nothing.
        scheduler.ifAvailable?.scheduleIfNotExists(
            SchedulableInstance.of(task.instance(id.toString(), TimerFire(tenant.value, id)), firesAt),
        )
    }

    /** A task handed to someone else keeps its timers: they follow the new task. */
    private fun move(
        from: UUID,
        to: UUID,
    ) {
        tenantDsl
            .current()
            .update(WORKFLOW_TIMER)
            .set(WORKFLOW_TIMER.TASK_ID, to)
            .where(WORKFLOW_TIMER.TASK_ID.eq(from), WORKFLOW_TIMER.STATE.eq(PENDING))
            .execute()
    }

    /** Closed tasks' timers are cancelled; their executions find them cancelled and do nothing. */
    private fun cancel(taskIds: List<UUID>) {
        if (taskIds.isEmpty()) return
        tenantDsl
            .current()
            .update(WORKFLOW_TIMER)
            .set(WORKFLOW_TIMER.STATE, CANCELLED)
            .where(WORKFLOW_TIMER.TASK_ID.`in`(taskIds), WORKFLOW_TIMER.STATE.eq(PENDING))
            .execute()
    }

    private fun at(instant: Instant): OffsetDateTime = instant.atOffset(ZoneOffset.UTC)

    private companion object {
        const val PENDING = "PENDING"
        const val FIRED = "FIRED"
        const val CANCELLED = "CANCELLED"
        const val RUN_LIMIT = 500
        const val MINUTES_PER_DAY = 24 * 60L
        const val SECONDS_PER_MINUTE = 60L
    }
}
