package com.memo.workflow.engine

import com.memo.workflow.ActionKind
import com.memo.workflow.BlockedReason
import com.memo.workflow.InstanceStatus
import java.util.UUID

/** How a round ended; OPEN while it runs (ADR-0035). */
enum class RoundOutcome { OPEN, RETURNED, APPROVED, REJECTED, WITHDRAWN }

enum class StepState { PENDING, ACTIVE, DONE, SKIPPED }

enum class TaskState { OPEN, COMPLETED, CANCELLED, REASSIGNED }

/** A piece of work for one person; [outcome] is APPROVE, RETURN or REJECT once COMPLETED. */
data class Task(
    val id: UUID,
    val assignee: UUID,
    val state: TaskState,
    val blocked: BlockedReason? = null,
    val outcome: ActionKind? = null,
)

/** One approver's place in a round. [assignee] is who holds the step now (it changes on reassignment). */
data class Step(
    val id: UUID,
    val ordinal: Int,
    val assignee: UUID,
    val state: StepState,
    val tasks: List<Task> = emptyList(),
)

data class Round(
    val number: Int,
    val outcome: RoundOutcome,
    val steps: List<Step>,
) {
    fun replaceStep(
        stepId: UUID,
        transform: (Step) -> Step,
    ): Round = copy(steps = steps.map { if (it.id == stepId) transform(it) else it })
}

/**
 * What the engine needs to decide the next transition (ADR-0034): the initiator, the status and the latest round.
 * Earlier rounds are history only; nothing in them can count toward the current one, so they are not loaded.
 */
data class InstanceState(
    val initiator: UUID,
    val status: InstanceStatus,
    val round: Round,
) {
    val openTasks: List<Task> get() = round.steps.flatMap { step -> step.tasks.filter { it.state == TaskState.OPEN } }

    /** Who holds the instance now, for the denormalised inbox columns (ADR-0035). */
    val currentAssignees: List<UUID> get() = openTasks.map { it.assignee }

    val activeStep: Step? get() = round.steps.firstOrNull { it.state == StepState.ACTIVE }

    fun findTask(taskId: UUID): Pair<Step, Task>? =
        round.steps.firstNotNullOfOrNull { step -> step.tasks.firstOrNull { it.id == taskId }?.let { step to it } }
}
