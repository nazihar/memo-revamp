package com.memo.workflow.internal

import com.memo.common.tenant.TenantDsl
import com.memo.jooq.tables.references.WORKFLOW_ACTION
import com.memo.jooq.tables.references.WORKFLOW_INSTANCE
import com.memo.jooq.tables.references.WORKFLOW_STEP
import com.memo.jooq.tables.references.WORKFLOW_TASK
import com.memo.workflow.ActionEntry
import com.memo.workflow.ActionKind
import com.memo.workflow.BlockedReason
import com.memo.workflow.InstanceStatus
import com.memo.workflow.SubjectRef
import com.memo.workflow.SubjectRun
import com.memo.workflow.TaskItem
import com.memo.workflow.TaskRole
import com.memo.workflow.engine.StepKind
import org.jooq.Condition
import org.jooq.impl.DSL
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/** Read queries of tasks and actions (design E01 §10): one query per page, served by the inbox and open-task indexes. */
@Component
class TaskQueryStore(
    private val tenantDsl: TenantDsl,
    private val definitions: CompiledDefinitions,
    private val json: JsonMapper,
) {
    /**
     * Open tasks of [assignees] (all when null) after the keyset position [after]: newest first, or with [byDue] by due time
     * (overdue first, then soonest, then those without a due time; the position's time is then the due time or [NO_DUE]).
     */
    fun open(
        assignees: Collection<UUID>?,
        blockedOnly: Boolean,
        after: Pair<Instant, UUID>?,
        limit: Int,
        overdueAt: Instant? = null,
        byDue: Boolean = false,
    ): List<TaskItem> {
        val t = WORKFLOW_TASK
        val i = WORKFLOW_INSTANCE
        val s = WORKFLOW_STEP
        val conditions =
            listOfNotNull(
                t.STATE.eq(OPEN),
                assignees?.let { t.ASSIGNEE_ID.`in`(it) },
                t.BLOCKED_REASON.isNotNull.takeIf { blockedOnly },
                overdueAt?.let { t.DUE_AT.lt(it.atOffset(ZoneOffset.UTC)) },
                after?.let { after(it, byDue) },
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
                t.DUE_AT,
            ).from(t)
            .join(i)
            .on(i.ID.eq(t.INSTANCE_ID))
            .join(s)
            .on(s.ID.eq(t.STEP_ID))
            .where(conditions)
            .orderBy(if (byDue) listOf(due().asc(), t.ID.asc()) else listOf(t.CREATED_AT.desc(), t.ID.desc()))
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
                    dueAt = r.value15()?.toInstant(),
                )
            }
    }

    /** When the instance's current step is due: the earliest due time of its open decisions, if any has one. */
    fun dueOf(instanceId: UUID): Instant? =
        tenantDsl
            .current()
            .select(DSL.min(WORKFLOW_TASK.DUE_AT))
            .from(WORKFLOW_TASK)
            .where(WORKFLOW_TASK.INSTANCE_ID.eq(instanceId), WORKFLOW_TASK.STATE.eq(OPEN), WORKFLOW_TASK.KIND.eq(DECIDE))
            .fetchOne(0, OffsetDateTime::class.java)
            ?.toInstant()

    /**
     * Each of [subjectIds]' newest run: its instance, status and [dueOf], in one query (the instance by the subject index,
     * the due time by the task index); subjects never started are absent.
     */
    fun runs(
        subjectType: String,
        subjectIds: Collection<UUID>,
    ): Map<UUID, SubjectRun> {
        if (subjectIds.isEmpty()) return emptyMap()
        val i = WORKFLOW_INSTANCE
        val t = WORKFLOW_TASK
        val due =
            DSL
                .field(
                    DSL
                        .select(DSL.min(t.DUE_AT))
                        .from(t)
                        .where(t.TENANT_ID.eq(i.TENANT_ID), t.INSTANCE_ID.eq(i.ID), t.STATE.eq(OPEN), t.KIND.eq(DECIDE)),
                ).`as`("due")
        return tenantDsl
            .current()
            .selectDistinct(i.SUBJECT_ID, i.ID, i.STATUS, due)
            .on(i.SUBJECT_ID)
            .from(i)
            .where(i.SUBJECT_TYPE.eq(subjectType), i.SUBJECT_ID.`in`(subjectIds))
            .orderBy(i.SUBJECT_ID, i.RUN.desc())
            .fetch { r ->
                checkNotNull(r.value1()) to
                    SubjectRun(checkNotNull(r.value2()), InstanceStatus.valueOf(checkNotNull(r.value3())), r.value4()?.toInstant())
            }.toMap()
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
            // The newest run last, so an item that ran again shows its current holders.
            .orderBy(WORKFLOW_INSTANCE.RUN)
            .fetch()
            .associate { it.value1()!! to it.value2().orEmpty().filterNotNull() }
    }

    /** The forum key each of [subjectIds] waits on (ADR-0058): its instance's active step is a waiting FORUM step. */
    fun waitingForums(
        subjectType: String,
        subjectIds: Collection<UUID>,
    ): Map<UUID, String> {
        if (subjectIds.isEmpty()) return emptyMap()
        val forum = DSL.field("{0} ->> {1}", String::class.java, WORKFLOW_STEP.RESOLUTION, DSL.inline("forum"))
        return tenantDsl
            .current()
            .select(WORKFLOW_INSTANCE.SUBJECT_ID, forum)
            .from(WORKFLOW_INSTANCE)
            .join(WORKFLOW_STEP)
            .on(WORKFLOW_STEP.ID.eq(WORKFLOW_INSTANCE.CURRENT_STEP_ID))
            .where(
                WORKFLOW_INSTANCE.SUBJECT_TYPE.eq(subjectType),
                WORKFLOW_INSTANCE.SUBJECT_ID.`in`(subjectIds),
                WORKFLOW_STEP.TYPE.eq(StepKind.FORUM.name),
                WORKFLOW_STEP.STATE.eq("ACTIVE"),
            ).fetch()
            .mapNotNull { r -> r.value2()?.let { r.value1()!! to it } }
            .toMap()
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

    /** Who holds the open task [taskId]; null when it is not open or has no holder. */
    fun holderOf(taskId: UUID): UUID? =
        tenantDsl
            .current()
            .select(WORKFLOW_TASK.ASSIGNEE_ID)
            .from(WORKFLOW_TASK)
            .where(WORKFLOW_TASK.ID.eq(taskId), WORKFLOW_TASK.STATE.eq(OPEN))
            .fetchOne(WORKFLOW_TASK.ASSIGNEE_ID)

    /** The keyset position [after] in the order of [open]. */
    private fun after(
        after: Pair<Instant, UUID>,
        byDue: Boolean,
    ): Condition {
        val (at, id) = after
        val t = WORKFLOW_TASK
        return if (byDue) {
            DSL
                .row(
                    due(),
                    t.ID,
                ).gt(at.atOffset(ZoneOffset.UTC), id)
        } else {
            DSL.row(t.CREATED_AT, t.ID).lt(at.atOffset(ZoneOffset.UTC), id)
        }
    }

    /** The due time, with tasks that have none after every due time. */
    private fun due() = DSL.coalesce(WORKFLOW_TASK.DUE_AT, NO_DUE.atOffset(ZoneOffset.UTC))

    companion object {
        private const val OPEN = "OPEN"
        private const val DECIDE = "DECIDE"

        /** The due time of a task without one, in the due order (and in its cursors). */
        val NO_DUE: Instant = Instant.parse("9999-12-31T00:00:00Z")
    }
}
