package com.memo.workflow.internal

import com.memo.common.id.IdGenerator
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantDsl
import com.memo.common.tenant.TenantTransactions
import com.memo.jooq.tables.references.WORKFLOW_TIMER
import com.memo.workflow.TimerKind
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.ZoneOffset
import java.util.UUID

/**
 * For seeders only ([com.memo.workflow.WorkflowTimerSeeding]): brings an instance's timers up to now, as if the engine had
 * run while its due time passed. Its pending timers already due fire as the timer task would fire them
 * ([WorkflowTimers.fire]); then, when asked, each task whose due time has fired and that has no escalation timer gets
 * one at now, fired at once. A subject seeded with a due time long past needs this: [WorkflowTimers] skips the timers
 * after a due time that is already past (ADR-0068), so the escalation would never come.
 */
@Component
class TimerCatchUp(
    private val tenantDsl: TenantDsl,
    private val transactions: TenantTransactions,
    private val timers: WorkflowTimers,
    private val ids: IdGenerator,
    private val clock: Clock,
) {
    /** Catches [instanceId] up (see the class); how many timers fired. */
    fun catchUp(
        instanceId: UUID,
        escalate: Boolean,
    ): Int {
        val now = clock.instant().atOffset(ZoneOffset.UTC)
        val due =
            transactions.readInTenant(TenantContext.current()) {
                tenantDsl
                    .current()
                    .select(WORKFLOW_TIMER.ID)
                    .from(WORKFLOW_TIMER)
                    .where(WORKFLOW_TIMER.INSTANCE_ID.eq(instanceId), WORKFLOW_TIMER.STATE.eq(PENDING), WORKFLOW_TIMER.FIRES_AT.le(now))
                    .orderBy(WORKFLOW_TIMER.FIRES_AT)
                    .fetch(WORKFLOW_TIMER.ID)
                    .filterNotNull()
            }
        due.forEach(timers::fire)
        // The scheduler may have fired a due time first: every task whose due time has fired and that has no escalation.
        val escalations = if (escalate) transactions.inTenant(TenantContext.current()) { escalationsNow(instanceId) } else emptyList()
        escalations.forEach(timers::fire)
        return due.size + escalations.size
    }

    /** An escalation timer at now for each task of [instanceId] whose due time has fired and that has none; their ids. */
    private fun escalationsNow(instanceId: UUID): List<UUID> {
        val dsl = tenantDsl.current()
        val escalated =
            dsl
                .select(WORKFLOW_TIMER.TASK_ID)
                .from(WORKFLOW_TIMER)
                .where(WORKFLOW_TIMER.INSTANCE_ID.eq(instanceId), WORKFLOW_TIMER.KIND.eq(TimerKind.ESCALATE.name))
                .fetchSet(WORKFLOW_TIMER.TASK_ID)
        val fired =
            dsl
                .select(WORKFLOW_TIMER.STEP_ID, WORKFLOW_TIMER.TASK_ID)
                .from(WORKFLOW_TIMER)
                .where(
                    WORKFLOW_TIMER.INSTANCE_ID.eq(instanceId),
                    WORKFLOW_TIMER.KIND.eq(TimerKind.DUE.name),
                    WORKFLOW_TIMER.STATE.eq(FIRED),
                ).fetch()
                .filter { it.value2() !in escalated }
        return fired.map { timer ->
            val id = ids.next()
            dsl
                .insertInto(WORKFLOW_TIMER)
                .set(WORKFLOW_TIMER.ID, id)
                .set(WORKFLOW_TIMER.TENANT_ID, tenantDsl.tenant().value)
                .set(WORKFLOW_TIMER.INSTANCE_ID, instanceId)
                .set(WORKFLOW_TIMER.STEP_ID, timer.value1())
                .set(WORKFLOW_TIMER.TASK_ID, timer.value2())
                .set(WORKFLOW_TIMER.KIND, TimerKind.ESCALATE.name)
                .set(WORKFLOW_TIMER.ORDINAL, 0)
                .set(WORKFLOW_TIMER.FIRES_AT, clock.instant().atOffset(ZoneOffset.UTC))
                .set(WORKFLOW_TIMER.STATE, PENDING)
                .execute()
            id
        }
    }

    private companion object {
        const val PENDING = "PENDING"
        const val FIRED = "FIRED"
    }
}
