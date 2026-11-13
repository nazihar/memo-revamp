package com.memo.workflow.engine

import com.memo.workflow.ActionKind
import com.memo.workflow.BlockedReason
import com.memo.workflow.InstanceStatus
import java.util.UUID

/** Something a person asks of a running instance. The service has already checked permissions such as reassignment. */
sealed interface Command {
    val actor: UUID
}

data class Approve(
    override val actor: UUID,
    val taskId: UUID,
    val remark: String? = null,
) : Command

data class Return(
    override val actor: UUID,
    val taskId: UUID,
    val remark: String?,
) : Command

data class Reject(
    override val actor: UUID,
    val taskId: UUID,
    val remark: String?,
) : Command

data class Withdraw(
    override val actor: UUID,
    val remark: String? = null,
) : Command

/** Starts the next round from the first approver of [route] (the only resubmission policy in E01). */
data class Resubmit(
    override val actor: UUID,
    val route: List<UUID>,
    val remark: String? = null,
) : Command

data class Reassign(
    override val actor: UUID,
    val taskId: UUID,
    val newAssignee: UUID,
    val reason: String?,
) : Command

/** Why the engine refused a command; the service maps each to a Problem Details type. */
enum class Refusal {
    NOT_INITIATOR,
    NOT_ASSIGNEE,
    WRONG_STATE,
    TASK_NOT_FOUND,
    TASK_NOT_OPEN,
    TASK_BLOCKED,
    REMARK_REQUIRED,
    REMARK_TOO_LONG,
    ROUTE_EMPTY,
    ROUTE_TOO_LONG,
    ROUTE_DUPLICATE,
    ROUTE_NOT_USED,
    INITIATOR_AS_APPROVER,
    SAME_ASSIGNEE,
    ASSIGNEE_IN_ROUND,
    ASSIGNEE_NOT_ELIGIBLE,
}

/** What the service must write, in order. A task created with `blocked` set also means "notify for reassignment". */
sealed interface Effect {
    data class RoundStarted(
        val number: Int,
    ) : Effect

    data class TaskCreated(
        val stepId: UUID,
        val task: Task,
    ) : Effect

    data class TaskClosed(
        val taskId: UUID,
        val state: TaskState,
        val outcome: ActionKind?,
    ) : Effect

    data class RoundEnded(
        val number: Int,
        val outcome: RoundOutcome,
    ) : Effect

    data class InstanceEnded(
        val status: InstanceStatus,
    ) : Effect

    /** A notify step was passed: tell [recipients] (ids) with the definition's [message] key. */
    data class Notify(
        val nodeId: String,
        val recipients: List<UUID>,
        val message: String,
    ) : Effect

    /** An escalate-to-forum step was passed (Phase 4 acts on it). */
    data class EscalateToForum(
        val nodeId: String,
        val forum: String,
    ) : Effect
}

/** The `workflow_action` row of an accepted command; the service adds `seq`, time, acting-for and the subject snapshot. */
data class ActionRecord(
    val kind: ActionKind,
    val round: Int,
    val taskId: UUID?,
    val actor: UUID,
    val remark: String?,
    /** What an automatic action was based on (a branch's inputs and result, a notification's recipients). */
    val details: Map<String, Any?> = emptyMap(),
)

/** [followUps] are the automatic actions the command led to (branches taken, notifications), written after [action]. */
data class Outcome(
    val state: InstanceState,
    val action: ActionRecord,
    val effects: List<Effect>,
    val followUps: List<ActionRecord> = emptyList(),
)

sealed interface Decision {
    data class Accepted(
        val outcome: Outcome,
    ) : Decision

    data class Refused(
        val reason: Refusal,
    ) : Decision
}

/**
 * What the engine asks of the outside world, answered before the transition runs: new ids, and whether a person may
 * hold a task now (active account and clearance for the subject; null means yes).
 */
class TransitionContext(
    val newId: () -> UUID,
    val eligibility: (UUID) -> BlockedReason?,
    val maxApprovers: Int,
    /** The subject's attribute values, read only when a branch is evaluated. */
    val attributes: () -> Map<String, Any?> = { emptyMap() },
)
