package com.memo.workflow.internal

import com.memo.workflow.InstanceStatus
import com.memo.workflow.WorkflowSubject
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * A subject's due time changed (ADR-0068, behind [com.memo.workflow.Workflows.redue]): its instance's open tasks on
 * steps due from the subject are re-planned in the caller's transaction, under the subject's and the instance's locks,
 * taken in the order an action takes them. No action is recorded (the subject audits why).
 */
@Component
class SubjectDues(
    private val instances: InstanceStore,
    private val subjects: List<WorkflowSubject>,
    private val timers: WorkflowTimers,
) {
    /** The tasks re-planned: 0 when the subject has no running instance. */
    fun redue(
        subjectType: String,
        subjectId: UUID,
    ): Int {
        val subject =
            subjects.singleOrNull { it.type == subjectType } ?: throw IllegalArgumentException("no workflow subject of type '$subjectType'")
        subject.lock(subjectId)
        val loaded =
            instances
                .instanceOfSubject(subjectType, subjectId)
                ?.let { instances.load(it, lock = true) }
                ?.takeIf { it.state.status == InstanceStatus.RUNNING }
        return loaded?.let { timers.replan(it.id, it.definition, it.state.round.steps) { node -> subject.dueAt(subjectId, node) } } ?: 0
    }
}
