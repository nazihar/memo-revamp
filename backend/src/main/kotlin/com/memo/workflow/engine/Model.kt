package com.memo.workflow.engine

import com.memo.workflow.ActionKind
import com.memo.workflow.BlockedReason
import com.memo.workflow.InstanceStatus
import java.util.UUID

/** How a round ended; OPEN while it runs (ADR-0035). */
enum class RoundOutcome { OPEN, RETURNED, APPROVED, REJECTED, WITHDRAWN, RECALLED, CANCELLED }

enum class StepState { PENDING, ACTIVE, DONE, SKIPPED }

/** What a step asks of its holder (`workflow_step.type`): route entries and approve steps approve, review steps recommend. */
enum class StepKind { APPROVE, REVIEW, PARALLEL }

/** TRANSFERRED: handed on by its holder (forward, delegate, design E03 §4.4); REASSIGNED: moved by an administrator. */
enum class TaskState { OPEN, COMPLETED, CANCELLED, REASSIGNED, TRANSFERRED }

/** A step's decision, or a consultation attached to a decision task (design E03 §4.4). */
enum class TaskKind { DECIDE, OPINION, CLARIFY }

/**
 * A piece of work for one person; [outcome] is set once COMPLETED. A delegated task carries [onBehalfOf] (the holder who
 * delegated it); a decision task [waiting] for an answer cannot be decided; consultations name their [parentTaskId]. A
 * task without [assignee] is a step nobody could be found for, blocked `UNRESOLVED` until an administrator assigns it
 * (design E04 §4.1, invariant 19).
 */
data class Task(
    val id: UUID,
    val assignee: UUID?,
    val state: TaskState,
    val blocked: BlockedReason? = null,
    val outcome: ActionKind? = null,
    val kind: TaskKind = TaskKind.DECIDE,
    val onBehalfOf: UUID? = null,
    val waiting: Boolean = false,
    val parentTaskId: UUID? = null,
)

/**
 * One activation of a definition step in a round (design E03 §3). [assignee] is who holds it now (it changes on
 * reassignment; null while nobody is found); [nodeId] is the definition step it activates; route entries also carry their
 * [routeIndex]. A step offered to several candidates needs [needed] of their decisions (design E04 §4.1); [resolution]
 * records who the definition's assignee resolved to and why.
 */
data class Step(
    val id: UUID,
    val ordinal: Int,
    val assignee: UUID?,
    val state: StepState,
    val tasks: List<Task> = emptyList(),
    val nodeId: String = ROUTE_NODE,
    val kind: StepKind = StepKind.APPROVE,
    val routeIndex: Int? = null,
    val name: String? = null,
    val needed: Int? = null,
    val resolution: Map<String, Any?>? = null,
) {
    /** Decided by several holders: a parallel step, or one resolved to several candidates. */
    val shared: Boolean get() = kind == StepKind.PARALLEL || needed != null

    companion object {
        /** The route step of the built-in sequential definition, which every E01 step activates. */
        const val ROUTE_NODE = "route"
    }
}

data class Round(
    val number: Int,
    val outcome: RoundOutcome,
    val steps: List<Step>,
    /** The initiator's route for this round (the approvers of the definition's route step). */
    val route: List<UUID> = emptyList(),
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

    /** Open decision tasks: what moves the instance on. */
    val openDecisions: List<Task> get() = openTasks.filter { it.kind == TaskKind.DECIDE }

    /**
     * Who holds the instance now, for the denormalised inbox columns (ADR-0035, design E03 §4.5): the holders of decision
     * tasks not waiting for an answer, and whoever was asked. People asked for an opinion are consulted, not holders.
     */
    val currentAssignees: List<UUID>
        get() = openTasks.filter { (it.kind == TaskKind.DECIDE && !it.waiting) || it.kind == TaskKind.CLARIFY }.mapNotNull { it.assignee }

    val activeStep: Step? get() = round.steps.firstOrNull { it.state == StepState.ACTIVE }

    fun findTask(taskId: UUID): Pair<Step, Task>? =
        round.steps.firstNotNullOfOrNull { step -> step.tasks.firstOrNull { it.id == taskId }?.let { step to it } }
}
