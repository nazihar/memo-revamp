package com.memo.workflow

import com.memo.common.context.Actor
import com.memo.common.context.ActorContext
import com.memo.common.web.Cursors
import com.memo.workflow.internal.InstanceStore
import com.memo.workflow.internal.TaskDescriptions
import com.memo.workflow.internal.TaskQueryStore
import com.memo.workflow.internal.WorkflowRunner
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.UUID

/** An open task as inboxes and the administrators' list show it; the subject's module adds the title. */
data class TaskItem(
    val taskId: UUID,
    val instanceId: UUID,
    val subject: SubjectRef,
    /** Null while nobody is found for the step (design E04 §4.1). */
    val assigneeId: UUID?,
    val round: Int,
    val blocked: BlockedReason?,
    val createdAt: Instant,
    /** What the holder is asked for: a decision, an opinion or an answer (design E03 §4.5). */
    val role: TaskRole = TaskRole.DECIDE,
    /** The instance's active step, by name. */
    val stepName: String? = null,
    /** Whom the holder acts for: a delegator, or the principal of a substitution (design E04 §4.5). */
    val onBehalfOf: UUID? = null,
    /** Offered to several candidates: the first decision completes the step (design E04 §4.2). */
    val shared: Boolean = false,
    /** The candidate who claimed the shared step, if anyone has. */
    val claimedBy: UUID? = null,
)

/** A page of tasks, newest first; [nextCursor] fetches the next one (null at the end). */
data class TaskPage(
    val items: List<TaskItem>,
    val nextCursor: String?,
)

/** One action of an instance's history (ADR-0035): who did what in which round, bound to the subject snapshot. */
data class ActionEntry(
    val seq: Long,
    val round: Int,
    val kind: ActionKind,
    val taskId: UUID?,
    val actorId: UUID,
    val actingForId: UUID?,
    val remark: String?,
    /** What the action applied to, as the subject described it (for memos: document version and SHA-256). */
    val snapshot: Map<String, Any?>,
    val occurredAt: Instant,
    /**
     * What the action was based on (design E03 §3, at most 4 KiB): a branch's condition inputs and result, a
     * notification's message key, a forum, a send-back target, the people handed to (ids), the versions of a migration.
     */
    val details: Map<String, Any?> = emptyMap(),
    /** The name of the definition step the action concerns (its task's step, or the step it names), if it has one. */
    val stepName: String? = null,
)

/**
 * Reads of the workflow (design E01 §5, §10): where a subject is, the signed-in user's open tasks, every open task for
 * administrators (the caller checks `workflow.tasks.reassign`), and an instance's history. Inside a tenant transaction.
 * Keyset pages on `(created_at desc, id)`, so a page costs the same however deep it is.
 */
@Service
class WorkflowQueries(
    private val runner: WorkflowRunner,
    private val instances: InstanceStore,
    private val tasks: TaskQueryStore,
    private val descriptions: TaskDescriptions,
) {
    /** Where the subject's instance is now; null if the subject was never submitted. */
    fun viewOf(
        subjectType: String,
        subjectId: UUID,
    ): WorkflowView? = runner.viewOf(subjectType, subjectId)

    /** The subject a task is about, so the subject's module can check visibility before acting (404 for strangers). */
    fun subjectOfTask(taskId: UUID): SubjectRef? = instances.subjectOfTask(taskId)

    /** The subject an instance routes. */
    fun subjectOfInstance(instanceId: UUID): SubjectRef? = instances.subjectOfInstance(instanceId)

    /** The signed-in user's open tasks, newest first. */
    fun myTasks(
        cursor: String?,
        limit: Int,
    ): TaskPage {
        val user = (ActorContext.current().actor as? Actor.User)?.userId ?: return TaskPage(emptyList(), null)
        return page(limit) { tasks.open(assignee = user, blockedOnly = false, after = cursor?.let(Cursors::decode), limit = it) }
    }

    /** Every open task of the tenant, or only the blocked ones, optionally of one assignee. */
    fun openTasks(
        blockedOnly: Boolean,
        assignee: UUID?,
        cursor: String?,
        limit: Int,
    ): TaskPage = page(limit) { tasks.open(assignee, blockedOnly, cursor?.let(Cursors::decode), it) }

    /** Who holds each running subject now (design E01 §10: one query for a page of the sent box). */
    fun holders(
        subjectType: String,
        subjectIds: Collection<UUID>,
    ): Map<UUID, List<UUID>> = tasks.holders(subjectType, subjectIds)

    /** Every action of the instance, in order. */
    fun history(instanceId: UUID): List<ActionEntry> = tasks.history(instanceId)

    /** The open task [user] holds on the subject (a decision first), with what they can do with it; null if none. */
    fun myTask(
        subjectType: String,
        subjectId: UUID,
        user: UUID,
    ): MyTask? = descriptions.myTask(subjectType, subjectId, user)

    /** The open task [taskId] as its holder sees it; null if it is not open. */
    fun task(taskId: UUID): MyTask? = descriptions.task(taskId)

    private fun page(
        limit: Int,
        fetch: (Int) -> List<TaskItem>,
    ): TaskPage {
        val size = limit.coerceIn(1, MAX_LIMIT)
        val rows = fetch(size + 1)
        val items = rows.take(size)
        return TaskPage(items, if (rows.size > size) items.last().let { Cursors.encode(it.createdAt, it.taskId) } else null)
    }

    private companion object {
        const val MAX_LIMIT = 100
    }
}

/** What a task asks of its holder (design E03 §4.4): a decision, an opinion, or an answer to a question. */
enum class TaskRole { DECIDE, OPINION, CLARIFY }

/** An earlier step of the round the holder may send the item back to. */
data class StepRef(
    val stepId: UUID,
    val name: String?,
    val holderId: UUID,
)

/** An open task as its holder sees it: what kind it is and what they can do with it now (design E03 §4.4, §12). */
data class MyTask(
    val id: UUID,
    val role: TaskRole,
    val blocked: BlockedReason?,
    /** A decision waiting for the answer to a question; it cannot be decided meanwhile. */
    val waiting: Boolean,
    /** Delegated to the holder: they act for someone else and cannot hand it on again. */
    val delegated: Boolean,
    val stepName: String?,
    /** APPROVE, or RECOMMEND on a review step. */
    val positive: ActionKind,
    /** The actions the step offers (decisions only). */
    val actions: List<ActionKind>,
    val sendBackTargets: List<StepRef>,
    /** The step is offered to several candidates and may be claimed (design E04 §4.2). */
    val shared: Boolean = false,
    /** Who claimed the shared step; while someone else has, the holder cannot decide. */
    val claimedBy: UUID? = null,
)
