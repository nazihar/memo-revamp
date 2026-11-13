package com.memo.workflow.engine

import com.memo.workflow.ActionKind
import com.memo.workflow.BlockedReason
import com.memo.workflow.InstanceStatus
import com.memo.workflow.definition.Definition
import java.util.UUID

/** Something a person asks of a running instance. The service has already checked permissions such as reassignment. */
sealed interface Command {
    val actor: UUID
}

/** The initiator pulls the item back before anyone has acted in this round (design E03 §4.4). */
data class Recall(
    override val actor: UUID,
    val remark: String? = null,
) : Command

/** An administrator stops the instance with a reason; the caller has checked the permission (design E03 §4.4). */
data class Cancel(
    override val actor: UUID,
    val remark: String?,
) : Command

/**
 * Moves the instance to [target], a newer published version of its definition (design E03 §4.7); [from] and [to] are
 * the version numbers, for the history. The caller has checked the permission and that [target] is newer.
 */
data class Migrate(
    override val actor: UUID,
    val target: Definition,
    val from: Int,
    val to: Int,
) : Command

/** A command on one task. */
sealed interface TaskCommand : Command {
    val taskId: UUID
}

data class Approve(
    override val actor: UUID,
    override val taskId: UUID,
    val remark: String? = null,
) : TaskCommand

data class Return(
    override val actor: UUID,
    override val taskId: UUID,
    val remark: String?,
) : TaskCommand

data class Reject(
    override val actor: UUID,
    override val taskId: UUID,
    val remark: String?,
) : TaskCommand

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
    override val taskId: UUID,
    val newAssignee: UUID,
    val reason: String?,
) : TaskCommand

/** Sends the item back to an earlier step of this round, which runs again (design E03 §4.4). */
data class SendBack(
    override val actor: UUID,
    override val taskId: UUID,
    val targetStepId: UUID,
    val remark: String?,
) : TaskCommand

/** Hands the task to [to], who then holds the step (responsibility moves). */
data class Forward(
    override val actor: UUID,
    override val taskId: UUID,
    val to: UUID,
    val remark: String?,
) : TaskCommand

/** Hands the task to [to] to act for the holder (responsibility stays; the delegate's actions are recorded acting for them). */
data class Delegate(
    override val actor: UUID,
    override val taskId: UUID,
    val to: UUID,
    val remark: String?,
) : TaskCommand

/** Asks [to] a question; the holder waits for the answer. */
data class Ask(
    override val actor: UUID,
    override val taskId: UUID,
    val to: UUID,
    val remark: String?,
) : TaskCommand

/** Answers a clarification task. */
data class Answer(
    override val actor: UUID,
    override val taskId: UUID,
    val remark: String?,
) : TaskCommand

/** Asks [to] for their opinions; the holder keeps the task and decides when ready. */
data class Refer(
    override val actor: UUID,
    override val taskId: UUID,
    val to: List<UUID>,
    val remark: String?,
) : TaskCommand

/** Gives the opinion an opinion task asked for. */
data class GiveOpinion(
    override val actor: UUID,
    override val taskId: UUID,
    val remark: String?,
) : TaskCommand

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
    ACTION_NOT_ALLOWED,
    NOT_AN_EARLIER_STEP,
    STEP_MISSING,
    STEP_CHANGED,
    WAITING_FOR_ANSWER,
    ALREADY_ON_STEP,
    TOO_MANY_REFERRALS,
    NOT_A_CONSULTATION,
    NOT_RECALLABLE,
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

    /** A decision task starts or stops waiting for an answer (ask / answer). */
    data class TaskWaiting(
        val taskId: UUID,
        val waiting: Boolean,
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
    /** Set when a delegate acted: the holder they acted for (design E03 §4.4). */
    val actingFor: UUID? = null,
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
    /**
     * Who approved which definition step in the previous round on the same subject snapshot, for the `skip-unchanged`
     * resubmission policy (design E03 §4.6): their approval carries over instead of a new task.
     */
    val carried: Set<Carried> = emptySet(),
)

/** An approval of [nodeId] by [person] that can carry over into the next round. */
data class Carried(
    val nodeId: String,
    val person: UUID,
)
