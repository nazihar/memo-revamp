package com.memo.workflow

import com.memo.common.context.Actor
import com.memo.common.context.ActorContext
import com.memo.common.web.Cursors
import com.memo.workflow.internal.InstanceStore
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
    val assigneeId: UUID,
    val round: Int,
    val blocked: BlockedReason?,
    val createdAt: Instant,
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
) {
    /** Where the subject's instance is now; null if the subject was never submitted. */
    fun viewOf(
        subjectType: String,
        subjectId: UUID,
    ): WorkflowView? = runner.viewOf(subjectType, subjectId)

    /** The subject a task is about, so the subject's module can check visibility before acting (404 for strangers). */
    fun subjectOfTask(taskId: UUID): SubjectRef? = instances.subjectOfTask(taskId)

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
