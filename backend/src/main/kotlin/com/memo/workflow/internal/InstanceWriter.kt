package com.memo.workflow.internal

import com.memo.common.id.IdGenerator
import com.memo.common.tenant.TenantDsl
import com.memo.common.web.RefusedException
import com.memo.jooq.tables.references.WORKFLOW_ACTION
import com.memo.jooq.tables.references.WORKFLOW_INSTANCE
import com.memo.jooq.tables.references.WORKFLOW_ROUND
import com.memo.jooq.tables.references.WORKFLOW_STEP
import com.memo.jooq.tables.references.WORKFLOW_TASK
import com.memo.workflow.ActionKind
import com.memo.workflow.BlockedReason
import com.memo.workflow.InstanceStatus
import com.memo.workflow.engine.ActionRecord
import com.memo.workflow.engine.Effect
import com.memo.workflow.engine.InstanceState
import com.memo.workflow.engine.Outcome
import com.memo.workflow.engine.Round
import com.memo.workflow.engine.RoundOutcome
import com.memo.workflow.engine.Step
import com.memo.workflow.engine.StepState
import com.memo.workflow.engine.Task
import com.memo.workflow.engine.TaskState
import org.jooq.JSONB
import org.jooq.impl.DSL
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Writes an engine outcome (ADR-0035) under the instance lock taken by [InstanceStore.lock]: every write is a guarded
 * update, and a guard that matches no row is a conflict (409), never a silent no-op. Inside a tenant transaction.
 */
@Component
class InstanceWriter(
    private val tenantDsl: TenantDsl,
    private val ids: IdGenerator,
    private val json: JsonMapper,
) {
    /**
     * Writes an accepted [outcome] of [before] (null for a new instance): new round and steps, step changes, task changes,
     * round end, the instance row and the action. Returns the action id and the instance's new version.
     */
    fun write(
        instanceId: UUID,
        before: InstanceState?,
        expectedVersion: Long,
        outcome: Outcome,
        snapshot: JSONB,
        actingFor: UUID?,
        now: OffsetDateTime,
    ): Pair<UUID, Long> {
        val after = outcome.state
        val sameRound = before != null && before.round.number == after.round.number
        // Steps activated during this action come first: their tasks refer to them.
        if (sameRound) insertSteps(instanceId, after.round, after.round.steps.filter { s -> before.round.steps.none { it.id == s.id } })
        outcome.effects.forEach { effect ->
            when (effect) {
                is Effect.RoundStarted -> insertRound(instanceId, after.round, outcome.action.actor, snapshot, now)
                is Effect.TaskCreated -> insertTask(instanceId, effect.stepId, effect.task, now)
                is Effect.TaskClosed -> closeTask(effect, now)
                is Effect.RoundEnded -> endRound(instanceId, effect, now)
                is Effect.TaskWaiting -> markWaiting(effect)
                is Effect.InstanceEnded, is Effect.Notify, is Effect.EscalateToForum -> Unit
            }
        }
        if (sameRound) updateSteps(before.round, after.round)
        val version = if (before == null) expectedVersion else updateInstance(instanceId, expectedVersion, after, now)
        val actionId = appendAction(instanceId, outcome.action, snapshot, actingFor, now)
        outcome.followUps.forEach { appendAction(instanceId, it, snapshot, null, now) }
        return actionId to version
    }

    /** Inserts [steps] of [round]; [knownRoundId] when the caller has just written the round, else it is looked up. */
    private fun insertSteps(
        instanceId: UUID,
        round: Round,
        steps: List<Step>,
        knownRoundId: UUID? = null,
    ) {
        if (steps.isEmpty()) return
        val roundId =
            knownRoundId ?: tenantDsl
                .current()
                .select(WORKFLOW_ROUND.ID)
                .from(WORKFLOW_ROUND)
                .where(WORKFLOW_ROUND.INSTANCE_ID.eq(instanceId), WORKFLOW_ROUND.NUMBER.eq(round.number))
                .fetchSingle(WORKFLOW_ROUND.ID)!!
        val tenant = tenantDsl.tenant().value
        val insert =
            tenantDsl.current().insertInto(
                WORKFLOW_STEP,
                WORKFLOW_STEP.ID,
                WORKFLOW_STEP.TENANT_ID,
                WORKFLOW_STEP.INSTANCE_ID,
                WORKFLOW_STEP.ROUND_ID,
                WORKFLOW_STEP.ORDINAL,
                WORKFLOW_STEP.TYPE,
                WORKFLOW_STEP.ASSIGNEE_ID,
                WORKFLOW_STEP.STATE,
                WORKFLOW_STEP.NODE_ID,
                WORKFLOW_STEP.ROUTE_INDEX,
            )
        steps
            .fold(insert) { query, step ->
                query.values(
                    step.id,
                    tenant,
                    instanceId,
                    roundId,
                    step.ordinal,
                    step.kind.name,
                    step.assignee,
                    step.state.name,
                    step.nodeId,
                    step.routeIndex,
                )
            }.execute()
    }

    private fun insertRound(
        instanceId: UUID,
        round: Round,
        startedBy: UUID,
        snapshot: JSONB,
        now: OffsetDateTime,
    ) {
        val dsl = tenantDsl.current()
        val tenant = tenantDsl.tenant().value
        val roundId = ids.next()
        dsl
            .insertInto(WORKFLOW_ROUND)
            .set(WORKFLOW_ROUND.ID, roundId)
            .set(WORKFLOW_ROUND.TENANT_ID, tenant)
            .set(WORKFLOW_ROUND.INSTANCE_ID, instanceId)
            .set(WORKFLOW_ROUND.NUMBER, round.number)
            .set(WORKFLOW_ROUND.OUTCOME, RoundOutcome.OPEN.name)
            .set(WORKFLOW_ROUND.STARTED_BY, startedBy)
            .set(WORKFLOW_ROUND.STARTED_AT, now)
            .set(WORKFLOW_ROUND.START_SNAPSHOT, snapshot)
            .set(WORKFLOW_ROUND.ROUTE, round.route.toTypedArray<UUID?>())
            .execute()
        insertSteps(instanceId, round, round.steps, roundId)
    }

    private fun insertTask(
        instanceId: UUID,
        stepId: UUID,
        task: Task,
        now: OffsetDateTime,
    ) {
        tenantDsl
            .current()
            .insertInto(WORKFLOW_TASK)
            .set(WORKFLOW_TASK.ID, task.id)
            .set(WORKFLOW_TASK.TENANT_ID, tenantDsl.tenant().value)
            .set(WORKFLOW_TASK.INSTANCE_ID, instanceId)
            .set(WORKFLOW_TASK.STEP_ID, stepId)
            .set(WORKFLOW_TASK.ASSIGNEE_ID, task.assignee)
            .set(WORKFLOW_TASK.STATE, task.state.name)
            .set(WORKFLOW_TASK.BLOCKED_REASON, task.blocked?.name)
            .set(WORKFLOW_TASK.KIND, task.kind.name)
            .set(WORKFLOW_TASK.ON_BEHALF_OF, task.onBehalfOf)
            .set(WORKFLOW_TASK.WAITING, task.waiting)
            .set(WORKFLOW_TASK.PARENT_TASK_ID, task.parentTaskId)
            .set(WORKFLOW_TASK.CREATED_AT, now)
            .execute()
    }

    /** Ask and answer: the decision task waits for an answer, or not; guarded on the task still being open. */
    private fun markWaiting(effect: Effect.TaskWaiting) {
        val updated =
            tenantDsl
                .current()
                .update(WORKFLOW_TASK)
                .set(WORKFLOW_TASK.WAITING, effect.waiting)
                .set(WORKFLOW_TASK.VERSION, WORKFLOW_TASK.VERSION.plus(1))
                .where(WORKFLOW_TASK.ID.eq(effect.taskId))
                .and(WORKFLOW_TASK.STATE.eq(TaskState.OPEN.name))
                .execute()
        conflictIf(updated == 0)
    }

    private fun closeTask(
        effect: Effect.TaskClosed,
        now: OffsetDateTime,
    ) {
        val updated =
            tenantDsl
                .current()
                .update(WORKFLOW_TASK)
                .set(WORKFLOW_TASK.STATE, effect.state.name)
                .set(WORKFLOW_TASK.OUTCOME, effect.outcome?.name)
                .set(WORKFLOW_TASK.COMPLETED_AT, now)
                .set(WORKFLOW_TASK.VERSION, WORKFLOW_TASK.VERSION.plus(1))
                .where(WORKFLOW_TASK.ID.eq(effect.taskId))
                .and(WORKFLOW_TASK.STATE.eq(TaskState.OPEN.name))
                .execute()
        conflictIf(updated == 0)
    }

    private fun endRound(
        instanceId: UUID,
        effect: Effect.RoundEnded,
        now: OffsetDateTime,
    ) {
        val updated =
            tenantDsl
                .current()
                .update(WORKFLOW_ROUND)
                .set(WORKFLOW_ROUND.OUTCOME, effect.outcome.name)
                .set(WORKFLOW_ROUND.ENDED_AT, now)
                .where(WORKFLOW_ROUND.INSTANCE_ID.eq(instanceId))
                .and(WORKFLOW_ROUND.NUMBER.eq(effect.number))
                .and(WORKFLOW_ROUND.OUTCOME.eq(RoundOutcome.OPEN.name))
                .execute()
        conflictIf(updated == 0)
    }

    private fun updateSteps(
        before: Round,
        after: Round,
    ) {
        val previous = before.steps.associateBy { it.id }
        after.steps
            .filter { step -> previous[step.id]?.let { it.state != step.state || it.assignee != step.assignee } ?: false }
            .forEach { step ->
                tenantDsl
                    .current()
                    .update(WORKFLOW_STEP)
                    .set(WORKFLOW_STEP.STATE, step.state.name)
                    .set(WORKFLOW_STEP.ASSIGNEE_ID, step.assignee)
                    .where(WORKFLOW_STEP.ID.eq(step.id))
                    .execute()
            }
    }

    private fun updateInstance(
        instanceId: UUID,
        expectedVersion: Long,
        after: InstanceState,
        now: OffsetDateTime,
    ): Long {
        val updated =
            tenantDsl
                .current()
                .update(WORKFLOW_INSTANCE)
                .set(WORKFLOW_INSTANCE.STATUS, after.status.name)
                .set(WORKFLOW_INSTANCE.CURRENT_ROUND, after.round.number)
                .set(WORKFLOW_INSTANCE.CURRENT_STEP_ID, after.activeStep?.id)
                .set(WORKFLOW_INSTANCE.CURRENT_ASSIGNEES, after.currentAssignees.toTypedArray<UUID?>())
                .set(WORKFLOW_INSTANCE.CURRENT_STEP_NAME, after.activeStep?.name)
                .set(WORKFLOW_INSTANCE.UPDATED_AT, now)
                .set(WORKFLOW_INSTANCE.VERSION, expectedVersion + 1)
                .where(WORKFLOW_INSTANCE.ID.eq(instanceId))
                .and(WORKFLOW_INSTANCE.VERSION.eq(expectedVersion))
                .execute()
        conflictIf(updated == 0)
        return expectedVersion + 1
    }

    private fun appendAction(
        instanceId: UUID,
        action: ActionRecord,
        snapshot: JSONB,
        actingFor: UUID?,
        now: OffsetDateTime,
    ): UUID {
        val dsl = tenantDsl.current()
        val id = ids.next()
        val nextSeq =
            dsl
                .select(DSL.coalesce(DSL.max(WORKFLOW_ACTION.SEQ), 0L).plus(1))
                .from(WORKFLOW_ACTION)
                .where(WORKFLOW_ACTION.INSTANCE_ID.eq(instanceId))
                .fetchSingle()
                .value1()
        dsl
            .insertInto(WORKFLOW_ACTION)
            .set(WORKFLOW_ACTION.ID, id)
            .set(WORKFLOW_ACTION.TENANT_ID, tenantDsl.tenant().value)
            .set(WORKFLOW_ACTION.INSTANCE_ID, instanceId)
            .set(WORKFLOW_ACTION.SEQ, nextSeq)
            .set(WORKFLOW_ACTION.ROUND, action.round)
            .set(WORKFLOW_ACTION.TASK_ID, action.taskId)
            .set(WORKFLOW_ACTION.KIND, action.kind.name)
            .set(WORKFLOW_ACTION.ACTOR_ID, action.actor)
            .set(WORKFLOW_ACTION.ACTING_FOR_ID, action.actingFor ?: actingFor)
            .set(WORKFLOW_ACTION.REMARK, action.remark)
            .set(WORKFLOW_ACTION.SUBJECT_SNAPSHOT, snapshot)
            .set(WORKFLOW_ACTION.DETAILS, JSONB.valueOf(json.writeValueAsString(action.details)))
            .set(WORKFLOW_ACTION.OCCURRED_AT, now)
            .execute()
        return id
    }

    private fun conflictIf(condition: Boolean) {
        if (condition) throw RefusedException("CONFLICT", "The workflow changed meanwhile; reload and try again")
    }
}
