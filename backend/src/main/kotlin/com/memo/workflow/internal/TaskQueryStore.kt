package com.memo.workflow.internal

import com.memo.common.tenant.TenantDsl
import com.memo.jooq.tables.references.WORKFLOW_ACTION
import com.memo.jooq.tables.references.WORKFLOW_INSTANCE
import com.memo.jooq.tables.references.WORKFLOW_STEP
import com.memo.jooq.tables.references.WORKFLOW_TASK
import com.memo.workflow.ActionEntry
import com.memo.workflow.ActionKind
import com.memo.workflow.BlockedReason
import com.memo.workflow.SubjectRef
import com.memo.workflow.TaskItem
import com.memo.workflow.TaskRole
import com.memo.workflow.engine.StepKind
import org.jooq.impl.DSL
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/** Read queries of tasks and actions (design E01 §10): one query per page, served by the inbox and open-task indexes. */
@Component
class TaskQueryStore(
    private val tenantDsl: TenantDsl,
    private val definitions: CompiledDefinitions,
    private val json: JsonMapper,
) {
    /** Open tasks newest first, of [assignee] (all when null), after the keyset position [after]. */
    fun open(
        assignee: UUID?,
        blockedOnly: Boolean,
        after: Pair<Instant, UUID>?,
        limit: Int,
    ): List<TaskItem> {
        val t = WORKFLOW_TASK
        val i = WORKFLOW_INSTANCE
        val s = WORKFLOW_STEP
        val conditions =
            listOfNotNull(
                t.STATE.eq(OPEN),
                assignee?.let { t.ASSIGNEE_ID.eq(it) },
                t.BLOCKED_REASON.isNotNull.takeIf { blockedOnly },
                after?.let { (at, id) -> DSL.row(t.CREATED_AT, t.ID).lt(at.atOffset(ZoneOffset.UTC), id) },
            )
        return tenantDsl
            .current()
            .select(
                t.ID,
                t.INSTANCE_ID,
                i.SUBJECT_TYPE,
                i.SUBJECT_ID,
                t.ASSIGNEE_ID,
                i.CURRENT_ROUND,
                t.BLOCKED_REASON,
                t.CREATED_AT,
                t.KIND,
                i.CURRENT_STEP_NAME,
                t.ON_BEHALF_OF,
                s.NEEDED,
                s.CLAIMED_BY,
                s.TYPE,
            ).from(t)
            .join(i)
            .on(i.ID.eq(t.INSTANCE_ID))
            .join(s)
            .on(s.ID.eq(t.STEP_ID))
            .where(conditions)
            .orderBy(t.CREATED_AT.desc(), t.ID.desc())
            .limit(limit)
            .fetch { r ->
                TaskItem(
                    taskId = r.value1()!!,
                    instanceId = r.value2()!!,
                    subject = SubjectRef(r.value3()!!, r.value4()!!),
                    assigneeId = r.value5(),
                    round = r.value6()!!,
                    blocked = r.value7()?.let(BlockedReason::valueOf),
                    createdAt = r.value8()!!.toInstant(),
                    role = TaskRole.valueOf(r.value9()!!),
                    stepName = r.value10(),
                    onBehalfOf = r.value11(),
                    // A step resolved to several candidates; parallel steps need each of them and are not "shared".
                    shared = r.value12() != null && r.value14() != StepKind.PARALLEL.name,
                    claimedBy = r.value13(),
                )
            }
    }

    fun holders(
        subjectType: String,
        subjectIds: Collection<UUID>,
    ): Map<UUID, List<UUID>> {
        if (subjectIds.isEmpty()) return emptyMap()
        return tenantDsl
            .current()
            .select(WORKFLOW_INSTANCE.SUBJECT_ID, WORKFLOW_INSTANCE.CURRENT_ASSIGNEES)
            .from(WORKFLOW_INSTANCE)
            .where(WORKFLOW_INSTANCE.SUBJECT_TYPE.eq(subjectType), WORKFLOW_INSTANCE.SUBJECT_ID.`in`(subjectIds))
            .fetch()
            .associate { it.value1()!! to it.value2().orEmpty().filterNotNull() }
    }

    /**
     * Every action of the instance in order, with its details and the name of the step it concerns: its task's step, or
     * the step its details name (a branch, a notification, a send-back target), named as the pinned version names it.
     */
    fun history(instanceId: UUID): List<ActionEntry> {
        val dsl = tenantDsl.current()
        val pinned =
            dsl
                .select(WORKFLOW_INSTANCE.DEFINITION_VERSION_ID)
                .from(WORKFLOW_INSTANCE)
                .where(WORKFLOW_INSTANCE.ID.eq(instanceId))
                .fetchOne(WORKFLOW_INSTANCE.DEFINITION_VERSION_ID) ?: return emptyList()
        val steps = definitions.get(pinned).stepsById
        return dsl
            .select(WORKFLOW_ACTION.asterisk(), WORKFLOW_STEP.NODE_ID)
            .from(WORKFLOW_ACTION)
            .leftJoin(WORKFLOW_TASK)
            .on(WORKFLOW_TASK.ID.eq(WORKFLOW_ACTION.TASK_ID))
            .leftJoin(WORKFLOW_STEP)
            .on(WORKFLOW_STEP.ID.eq(WORKFLOW_TASK.STEP_ID))
            .where(WORKFLOW_ACTION.INSTANCE_ID.eq(instanceId))
            .orderBy(WORKFLOW_ACTION.SEQ)
            .fetch { row ->
                val r = row.into(WORKFLOW_ACTION)
                val details = r.details?.let { json.readValue(it.data(), Map::class.java) }.orEmpty()
                val node = row.get(WORKFLOW_STEP.NODE_ID) ?: (details["toStep"] ?: details["step"]) as? String
                @Suppress("UNCHECKED_CAST")
                ActionEntry(
                    seq = r.seq,
                    round = r.round,
                    kind = ActionKind.valueOf(r.kind),
                    taskId = r.taskId,
                    actorId = r.actorId,
                    actingForId = r.actingForId,
                    remark = r.remark,
                    snapshot = json.readValue(r.subjectSnapshot!!.data(), Map::class.java) as Map<String, Any?>,
                    occurredAt = r.occurredAt.toInstant(),
                    details = details as Map<String, Any?>,
                    stepName = node?.let { steps[it]?.name ?: it },
                )
            }
    }

    private companion object {
        const val OPEN = "OPEN"
    }
}
