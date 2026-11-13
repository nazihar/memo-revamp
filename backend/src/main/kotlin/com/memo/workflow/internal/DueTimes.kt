package com.memo.workflow.internal

import com.memo.common.tenant.TenantDsl
import com.memo.jooq.tables.references.WORKFLOW_TASK
import com.memo.workflow.calendar.SlaDuration
import com.memo.workflow.definition.Definition
import com.memo.workflow.engine.Outcome
import org.jooq.impl.DSL
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

/**
 * When the steps an action activates are due (ADR-0041, design E05 §4.2): from each step's SLA, counted from [now] in
 * the tenant's working time (or calendar time), with the calendar read once per action and only when a step has an
 * SLA. The writer keeps a step's existing due time when its task moves to someone else. Inside a tenant transaction.
 */
@Component
class DueTimes(
    private val calendars: Calendars,
    private val tenantDsl: TenantDsl,
) {
    fun of(
        definition: Definition,
        outcome: Outcome,
        now: Instant,
    ): (UUID) -> Instant? {
        val calendar by lazy { calendars.calendar() }
        val dues = mutableMapOf<UUID, Instant?>()
        return { stepId ->
            dues.getOrPut(stepId) {
                val step =
                    outcome.state.round.steps
                        .firstOrNull { it.id == stepId }
                val sla = step?.let { definition.stepsById[it.nodeId]?.sla }
                sla?.let { stepDue(stepId) ?: calendar.due(now, SlaDuration.parse(it.due), it.inWorkingTime) }
            }
        }
    }

    /** The due time the step's tasks already have: a task that moves keeps it. */
    private fun stepDue(stepId: UUID): Instant? =
        tenantDsl
            .current()
            .select(DSL.min(WORKFLOW_TASK.DUE_AT))
            .from(WORKFLOW_TASK)
            .where(WORKFLOW_TASK.STEP_ID.eq(stepId))
            .fetchOne(0, OffsetDateTime::class.java)
            ?.toInstant()
}
