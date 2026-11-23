package com.memo.workflow

import com.memo.workflow.internal.TaskQueryStore
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.UUID

/** A subject's newest run: its instance, its status, and when its current step is due (null without a due time). */
data class SubjectRun(
    val instanceId: UUID,
    val status: InstanceStatus,
    val dueAt: Instant?,
)

/**
 * Where many subjects' workflows are, in one query (design E07 §9 (c): the actions' daily safety net): for each, what
 * [WorkflowQueries.viewOf] and [WorkflowQueries.dueOf] answer one subject at a time. Inside a tenant transaction.
 */
@Service
class WorkflowRuns(
    private val tasks: TaskQueryStore,
) {
    /** Each of [subjectIds]' newest run with its status and its current step's due time; subjects never started are absent. */
    fun of(
        subjectType: String,
        subjectIds: Collection<UUID>,
    ): Map<UUID, SubjectRun> = tasks.runs(subjectType, subjectIds)
}
