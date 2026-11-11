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
import com.memo.workflow.SubjectRef
import com.memo.workflow.definition.Definition
import com.memo.workflow.definition.DefinitionCompiler
import com.memo.workflow.engine.ActionRecord
import com.memo.workflow.engine.Effect
import com.memo.workflow.engine.InstanceState
import com.memo.workflow.engine.Outcome
import com.memo.workflow.engine.Round
import com.memo.workflow.engine.RoundOutcome
import com.memo.workflow.engine.Step
import com.memo.workflow.engine.StepKind
import com.memo.workflow.engine.StepState
import com.memo.workflow.engine.Task
import com.memo.workflow.engine.TaskState
import org.jooq.JSONB
import org.jooq.impl.DSL
import org.springframework.stereotype.Component
import java.time.OffsetDateTime
import java.util.UUID

/** An instance as loaded for a transition: its row data, the engine state of its latest round and its pinned definition. */
data class LoadedInstance(
    val id: UUID,
    val subjectType: String,
    val subjectId: UUID,
    val version: Long,
    val state: InstanceState,
    val definition: Definition = DefinitionCompiler.SEQUENTIAL,
)

/**
 * Reads instances for the engine (ADR-0035). [load] takes the instance row `FOR UPDATE`, so actions on one instance are
 * serialised and `seq` stays gapless; [InstanceWriter] then writes the outcome. Inside a tenant transaction.
 */
@Component
class InstanceStore(
    private val tenantDsl: TenantDsl,
    private val definitions: CompiledDefinitions,
) {
    fun instanceOfTask(taskId: UUID): UUID? =
        tenantDsl
            .current()
            .select(WORKFLOW_TASK.INSTANCE_ID)
            .from(WORKFLOW_TASK)
            .where(WORKFLOW_TASK.ID.eq(taskId))
            .fetchOne(WORKFLOW_TASK.INSTANCE_ID)

    /** The subject of the task's instance. */
    fun subjectOfTask(taskId: UUID): SubjectRef? =
        tenantDsl
            .current()
            .select(WORKFLOW_INSTANCE.SUBJECT_TYPE, WORKFLOW_INSTANCE.SUBJECT_ID)
            .from(WORKFLOW_TASK)
            .join(WORKFLOW_INSTANCE)
            .on(WORKFLOW_INSTANCE.ID.eq(WORKFLOW_TASK.INSTANCE_ID))
            .where(WORKFLOW_TASK.ID.eq(taskId))
            .fetchOne { SubjectRef(it.value1()!!, it.value2()!!) }

    fun subjectOfInstance(instanceId: UUID): SubjectRef? =
        tenantDsl
            .current()
            .select(WORKFLOW_INSTANCE.SUBJECT_TYPE, WORKFLOW_INSTANCE.SUBJECT_ID)
            .from(WORKFLOW_INSTANCE)
            .where(WORKFLOW_INSTANCE.ID.eq(instanceId))
            .fetchOne { SubjectRef(it.value1()!!, it.value2()!!) }

    fun instanceOfSubject(
        subjectType: String,
        subjectId: UUID,
    ): UUID? =
        tenantDsl
            .current()
            .select(WORKFLOW_INSTANCE.ID)
            .from(WORKFLOW_INSTANCE)
            .where(WORKFLOW_INSTANCE.SUBJECT_TYPE.eq(subjectType))
            .and(WORKFLOW_INSTANCE.SUBJECT_ID.eq(subjectId))
            .fetchOne(WORKFLOW_INSTANCE.ID)

    /** The instance and its current round; [lock] takes the row `FOR UPDATE` (actions), reads go without. */
    fun load(
        instanceId: UUID,
        lock: Boolean,
    ): LoadedInstance? {
        val dsl = tenantDsl.current()
        val select = dsl.selectFrom(WORKFLOW_INSTANCE).where(WORKFLOW_INSTANCE.ID.eq(instanceId))
        val instance = (if (lock) select.forUpdate() else select).fetchOne() ?: return null
        val round =
            dsl
                .selectFrom(WORKFLOW_ROUND)
                .where(WORKFLOW_ROUND.INSTANCE_ID.eq(instanceId))
                .and(WORKFLOW_ROUND.NUMBER.eq(instance.currentRound))
                .fetchSingle()
        val steps =
            dsl
                .selectFrom(WORKFLOW_STEP)
                .where(WORKFLOW_STEP.ROUND_ID.eq(round.id))
                .orderBy(WORKFLOW_STEP.ORDINAL)
                .fetch()
        val tasks =
            dsl
                .selectFrom(WORKFLOW_TASK)
                .where(WORKFLOW_TASK.STEP_ID.`in`(steps.map { it.id }))
                .orderBy(WORKFLOW_TASK.CREATED_AT, WORKFLOW_TASK.ID)
                .fetch()
                .groupBy { it.stepId }
        val definition = definitions.get(instance.definitionVersionId)
        val engineSteps =
            steps.map { step ->
                Step(
                    id = step.id,
                    ordinal = step.ordinal,
                    assignee = step.assigneeId,
                    state = StepState.valueOf(step.state),
                    nodeId = step.nodeId,
                    kind = StepKind.valueOf(step.type),
                    routeIndex = step.routeIndex,
                    name = definition.stepsById[step.nodeId]?.name,
                    tasks =
                        tasks[step.id].orEmpty().map { task ->
                            Task(
                                id = task.id,
                                assignee = task.assigneeId,
                                state = TaskState.valueOf(task.state),
                                blocked = task.blockedReason?.let(BlockedReason::valueOf),
                                outcome = task.outcome?.let(ActionKind::valueOf),
                            )
                        },
                )
            }
        val state =
            InstanceState(
                initiator = instance.initiatorId,
                status = InstanceStatus.valueOf(instance.status),
                round = Round(round.number, RoundOutcome.valueOf(round.outcome), engineSteps, round.route.orEmpty().filterNotNull()),
            )
        return LoadedInstance(instance.id, instance.subjectType, instance.subjectId, instance.version!!, state, definition)
    }

    /** The row of a new instance, before its first round is written by [write]. */
    fun insert(
        id: UUID,
        subjectType: String,
        subjectId: UUID,
        definitionVersionId: UUID,
        state: InstanceState,
        now: OffsetDateTime,
    ) {
        tenantDsl
            .current()
            .insertInto(WORKFLOW_INSTANCE)
            .set(WORKFLOW_INSTANCE.ID, id)
            .set(WORKFLOW_INSTANCE.TENANT_ID, tenantDsl.tenant().value)
            .set(WORKFLOW_INSTANCE.SUBJECT_TYPE, subjectType)
            .set(WORKFLOW_INSTANCE.SUBJECT_ID, subjectId)
            .set(WORKFLOW_INSTANCE.DEFINITION_VERSION_ID, definitionVersionId)
            .set(WORKFLOW_INSTANCE.INITIATOR_ID, state.initiator)
            .set(WORKFLOW_INSTANCE.STATUS, state.status.name)
            .set(WORKFLOW_INSTANCE.CURRENT_ROUND, state.round.number)
            .set(WORKFLOW_INSTANCE.CURRENT_STEP_ID, state.activeStep?.id)
            .set(WORKFLOW_INSTANCE.CURRENT_ASSIGNEES, state.currentAssignees.toTypedArray<UUID?>())
            .set(WORKFLOW_INSTANCE.CURRENT_STEP_NAME, state.activeStep?.name)
            .set(WORKFLOW_INSTANCE.CREATED_AT, now)
            .set(WORKFLOW_INSTANCE.UPDATED_AT, now)
            .execute()
    }
}
