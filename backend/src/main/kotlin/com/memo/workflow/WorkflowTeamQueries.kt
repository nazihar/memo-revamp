package com.memo.workflow

import com.memo.common.web.Cursors
import com.memo.workflow.internal.Calendars
import com.memo.workflow.internal.InstanceStore
import com.memo.workflow.internal.TaskQueryStore
import org.springframework.stereotype.Service
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** An instance that reached a final decision: when it was submitted and decided (design E05 §6, cycle time). */
data class DecidedInstance(
    val subjectId: UUID,
    val submittedAt: Instant,
    val decidedAt: Instant,
    val status: InstanceStatus,
)

/**
 * Questions about the work of several people (design E05 §5, §6): a team's open tasks, decided instances in a period,
 * ages in working days and who holds a task. Inside a tenant transaction; the caller has decided who may ask.
 */
@Service
class WorkflowTeamQueries(
    private val tasks: TaskQueryStore,
    private val instances: InstanceStore,
    private val calendars: Calendars,
    private val queries: WorkflowQueries,
) {
    /** Open tasks held by any of [assignees], newest first or by due time; nothing for an empty team. */
    fun openTasksOf(
        assignees: Collection<UUID>,
        cursor: String?,
        limit: Int,
        byDue: Boolean = false,
        overdueAt: Instant? = null,
    ): TaskPage =
        if (assignees.isEmpty()) {
            TaskPage(emptyList(), null)
        } else {
            queries.page(limit, byDue) { tasks.open(assignees, false, cursor?.let(Cursors::decode), it, overdueAt, byDue) }
        }

    /** Every open task of [assignees], at most [cap] (for counts). */
    fun allOpenTasksOf(
        assignees: Collection<UUID>,
        cap: Int = MAX_COUNTED,
    ): List<TaskItem> = if (assignees.isEmpty()) emptyList() else tasks.open(assignees, false, null, cap)

    fun decided(
        subjectType: String,
        from: Instant,
        to: Instant,
    ): List<DecidedInstance> = instances.decided(subjectType, from, to)

    /** Working days after [from] up to and including [to], by the tenant's business calendar. */
    fun workingDays(
        from: LocalDate,
        to: LocalDate,
    ): Int = calendars.workingDays(from, to)

    fun holderOf(taskId: UUID): UUID? = tasks.holderOf(taskId)

    companion object {
        const val MAX_COUNTED = 10_000
    }
}
