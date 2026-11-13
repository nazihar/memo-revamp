package com.memo.workflow.engine

import com.memo.workflow.ActionKind
import com.memo.workflow.BlockedReason
import com.memo.workflow.InstanceStatus
import com.memo.workflow.RouteEntry
import com.memo.workflow.definition.Definition
import com.memo.workflow.definition.ForwardWithin
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
    /** A route of positions and groups as well as people (design E04 §4.7); [route] stands for people only when null. */
    val entries: List<RouteEntry>? = null,
) : Command {
    val routeEntries: List<RouteEntry> get() = entries ?: route.map(RouteEntry::person)
}

data class Reassign(
    override val actor: UUID,
    override val taskId: UUID,
    val newAssignee: UUID,
    val reason: String?,
    /** The new assignee acts for this person, who keeps the step (a substitution applied to open tasks, design E04 §4.5). */
    val onBehalfOf: UUID? = null,
) : TaskCommand

/**
 * The task's holder can no longer act (deactivated, design E04 §4.6): the task is replaced by one blocked for
 * reassignment, so the administrators are told as when a task is created blocked (E01).
 */
data class Block(
    override val actor: UUID,
    override val taskId: UUID,
) : TaskCommand

/**
 * The person a step resolved to changed (a position's holder changed, design E04 §4.6): its open decision tasks are
 * closed and the step resolves again. [actor] is whoever changed the organisation.
 */
data class Reresolve(
    override val actor: UUID,
    val stepId: UUID,
) : Command

/** A candidate takes a step shared by several people (design E04 §4.2): the others cannot decide it until it is released. */
data class Claim(
    override val actor: UUID,
    override val taskId: UUID,
) : TaskCommand

/**
 * Gives a claimed step back to all its candidates: by the claimer, or by an administrator ([byAdministrator]; the caller
 * has checked `workflow.tasks.reassign`).
 */
data class Release(
    override val actor: UUID,
    override val taskId: UUID,
    val byAdministrator: Boolean = false,
) : TaskCommand

/**
 * The holder declares an interest in the item and stands aside (SOD-4, design E04 §4.4): their task closes and the step
 * goes to whoever else it resolves to, never to them. [remark] (the reason) is required.
 */
data class Recuse(
    override val actor: UUID,
    override val taskId: UUID,
    val remark: String?,
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

    /** A route entry that is not one person, one position, or a group of two or more of them (design E04 §4.7). */
    ROUTE_INVALID,

    /** Forwarded outside the step's limit: the holder's unit or legal entity (design E04 §4.7). */
    FORWARD_OUTSIDE_LIMIT,
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
    NOT_SHARED,
    ALREADY_BLOCKED,
    NOTHING_TO_RESOLVE,
    ALREADY_CLAIMED,
    NOT_CLAIMED,
    CLAIMED_BY_OTHER,
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
    /** Who a definition's assignees are now (design E04 §4.1). */
    val resolver: Resolver = Resolver.NAMED,
    /** SOD-2: nobody approves twice in one round (tenant setting, design E04 §4.4). */
    val oncePerRound: Boolean = true,
    /** SOD-4: the people with a declared interest in the subject, read when a step is resolved. */
    val interested: () -> Set<UUID> = { emptySet() },
    /**
     * Who acts for a person while their substitution is in force (design E04 §4.5); the runner leaves out substitutes
     * who may not act on this item (SOD-3). Their tasks go to the substitute, acting for the person.
     */
    val substituteOf: (UUID) -> UUID? = { null },
    /** The delegation of authority for the instance's pinned version (design E04 §4.3), asked when a `doa` step activates. */
    val doa: () -> DoaMatch = { DoaMatch.NONE },
    /** Whether [to] is within [limit] of the holder (design E04 §4.7), for steps that limit forwarding. */
    val within: (holder: UUID, to: UUID, limit: ForwardWithin) -> Boolean = { _, _, _ -> true },
)

/** An approval of [nodeId] by [person] that can carry over into the next round. */
data class Carried(
    val nodeId: String,
    val person: UUID,
)
