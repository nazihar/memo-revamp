package com.memo.workflow

import com.memo.common.context.Actor
import com.memo.common.context.ActorContext
import com.memo.workflow.engine.Refusal
import com.memo.workflow.engine.Reresolve
import com.memo.workflow.engine.StaffChanges
import com.memo.workflow.engine.StepState
import com.memo.workflow.engine.TaskKind
import com.memo.workflow.engine.TaskState
import com.memo.workflow.internal.InstanceStore
import com.memo.workflow.internal.WorkflowRunner
import com.memo.workflow.internal.instanceOfSubject
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * The subject named someone else for a step (ADR-0068 amendment: an action's new owner or contributors), beside
 * [Workflows]: each active step of the item's running instance with an open decision resolves
 * again from its assignee, the slot read anew, as a position's holder change does (ADR-0040). Recorded as reassignments
 * (`SUBJECT_CHANGED`) by the signed-in user; the subject's lock is taken before the instance's. Inside the caller's tenant
 * transaction; the subject audits why.
 */
@Service
class WorkflowResolutions(
    private val instances: InstanceStore,
    private val subjects: List<WorkflowSubject>,
    private val runner: WorkflowRunner,
) {
    /** The steps resolved again: 0 without a running instance or an open decision. */
    fun reresolve(
        subjectType: String,
        subjectId: UUID,
    ): Int {
        val actor = checkNotNull(ActorContext.current().actor as? Actor.User) { "workflow actions need a signed-in user" }
        val subject = requireNotNull(subjects.singleOrNull { it.type == subjectType }) { "no workflow subject of type '$subjectType'" }
        subject.lock(subjectId)
        val loaded =
            instances
                .instanceOfSubject(subjectType, subjectId)
                ?.let { instances.load(it, lock = false) }
                ?.takeIf { it.state.status == InstanceStatus.RUNNING } ?: return 0
        val steps =
            loaded.state.round.steps.filter { step ->
                step.state == StepState.ACTIVE && step.tasks.any { it.state == TaskState.OPEN && it.kind == TaskKind.DECIDE }
            }
        return steps.count { step ->
            try {
                runner.staffChange(loaded.id, actor) { Reresolve(it, step.id, StaffChanges.SUBJECT_CHANGED) }
                true
            } catch (refused: WorkflowRefusedException) {
                // A step that names its people itself (a named route entry) keeps them.
                if (refused.reason != Refusal.NOTHING_TO_RESOLVE.name) throw refused
                false
            }
        }
    }
}
