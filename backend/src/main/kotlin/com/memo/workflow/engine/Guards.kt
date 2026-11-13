package com.memo.workflow.engine

import com.memo.workflow.InstanceStatus
import com.memo.workflow.definition.Definition
import com.memo.workflow.definition.StepAction
import java.util.UUID

/** Remark rules shared by every action: mandatory where the action says so, at most [MAX_LENGTH] characters. */
internal object Remarks {
    const val MAX_LENGTH = 4_000

    fun refusal(
        remark: String?,
        required: Boolean,
    ): Refusal? {
        val text = clean(remark)
        return when {
            required && text == null -> Refusal.REMARK_REQUIRED
            text != null && text.length > MAX_LENGTH -> Refusal.REMARK_TOO_LONG
            else -> null
        }
    }

    fun clean(remark: String?): String? = remark?.trim()?.takeIf { it.isNotEmpty() }
}

/**
 * The shape of an initiator-defined route (invariant 11); clearance and account state are the subject's checks. A
 * definition without a route step ([routed] false) takes no route at all.
 */
internal object Routes {
    fun refusal(
        initiator: UUID,
        route: List<UUID>,
        maxApprovers: Int,
        routed: Boolean = true,
    ): Refusal? =
        when {
            !routed -> if (route.isEmpty()) null else Refusal.ROUTE_NOT_USED
            route.isEmpty() -> Refusal.ROUTE_EMPTY
            route.size > maxApprovers -> Refusal.ROUTE_TOO_LONG
            initiator in route -> Refusal.INITIATOR_AS_APPROVER
            route.toSet().size != route.size -> Refusal.ROUTE_DUPLICATE
            else -> null
        }
}

/** Whether a command may run against a state (design E01 §3, E03 §4.4); null means it may. */
internal object Guards {
    /** Actions whose remark is always required, whatever the definition says (design E03 §2). */
    private val ALWAYS_REMARK =
        setOf(StepAction.RETURN, StepAction.REJECT, StepAction.SEND_BACK, StepAction.FORWARD, StepAction.ASK, StepAction.REFER)

    fun refusal(
        state: InstanceState,
        command: Command,
        context: TransitionContext,
        definition: Definition,
        routed: Boolean = true,
    ): Refusal? =
        when (command) {
            is Approve, is Return, is Reject -> {
                decisionOf(state, command, definition)
            }

            is SendBack, is Forward, is Delegate, is Ask, is Refer, is Answer, is GiveOpinion, is Recuse -> {
                HandOnGuards.refusal(state, command, context, definition)
            }

            is Reassign -> {
                reassign(state, command, context)
            }

            is Withdraw, is Resubmit, is Recall, is Cancel -> {
                instance(state, command, context, routed)
            }

            is Claim -> {
                taskAction(state, command.actor, command.taskId) ?: Claims.claimRefusal(state, command)
            }

            is Release -> {
                (if (command.byAdministrator) null else taskAction(state, command.actor, command.taskId))
                    ?: Claims.releaseRefusal(state, command)
            }

            is Migrate -> {
                Migrations.refusal(state, command.target)
            }
        }

    /** Commands on the instance rather than a task: withdraw, resubmit, recall, cancel. */
    private fun instance(
        state: InstanceState,
        command: Command,
        context: TransitionContext,
        routed: Boolean,
    ): Refusal? =
        when (command) {
            is Withdraw -> {
                initiatorIn(state, command.actor, InstanceStatus.RUNNING, InstanceStatus.RETURNED)
                    ?: Remarks.refusal(command.remark, required = false)
            }

            is Resubmit -> {
                initiatorIn(state, command.actor, InstanceStatus.RETURNED)
                    ?: Routes.refusal(state.initiator, command.route, context.maxApprovers, routed)
                    ?: Remarks.refusal(command.remark, required = false)
            }

            is Recall -> {
                initiatorIn(state, command.actor, InstanceStatus.RUNNING) ?: recallable(state)
                    ?: Remarks.refusal(command.remark, required = false)
            }

            else -> {
                val stoppable = state.status == InstanceStatus.RUNNING || state.status == InstanceStatus.RETURNED
                (if (stoppable) null else Refusal.WRONG_STATE) ?: Remarks.refusal((command as Cancel).remark, required = true)
            }
        }

    /** Recall only before anyone acted in this round: every task is an untouched open decision (design E03 §4.4). */
    private fun recallable(state: InstanceState): Refusal? {
        val tasks = state.round.steps.flatMap { it.tasks }
        val untouched = tasks.all { it.state == TaskState.OPEN && it.kind == TaskKind.DECIDE && !it.waiting }
        return if (untouched) null else Refusal.NOT_RECALLABLE
    }

    private fun decisionOf(
        state: InstanceState,
        command: TaskCommand,
        definition: Definition,
    ): Refusal? =
        when (command) {
            is Return -> decision(state, command.actor, command.taskId, StepAction.RETURN, command.remark, definition)
            is Reject -> decision(state, command.actor, command.taskId, StepAction.REJECT, command.remark, definition)
            else -> decision(state, command.actor, command.taskId, null, (command as Approve).remark, definition)
        }

    /**
     * The holder deciding on their open decision task: an action the step offers ([action] null: the step's positive
     * action), not while waiting for an answer, with the remark the action or the definition requires. A delegated task
     * cannot be handed on again.
     */
    internal fun decision(
        state: InstanceState,
        actor: UUID,
        taskId: UUID,
        action: StepAction?,
        remark: String?,
        definition: Definition,
    ): Refusal? {
        val holderRefusal = taskAction(state, actor, taskId)
        if (holderRefusal != null) return holderRefusal
        val (step, task) = state.findTask(taskId)!!
        val node = definition.stepsById[step.nodeId]
        val wanted = action ?: if (step.kind == StepKind.REVIEW) StepAction.RECOMMEND else StepAction.APPROVE
        val handOnAgain = task.onBehalfOf != null && (wanted == StepAction.FORWARD || wanted == StepAction.DELEGATE)
        return when {
            task.kind != TaskKind.DECIDE -> Refusal.ACTION_NOT_ALLOWED
            task.waiting -> Refusal.WAITING_FOR_ANSWER
            node == null || wanted !in node.offeredActions || handOnAgain -> Refusal.ACTION_NOT_ALLOWED
            else -> Remarks.refusal(remark, required = wanted in ALWAYS_REMARK || wanted in node.remarks.orEmpty())
        }
    }

    internal fun taskAction(
        state: InstanceState,
        actor: UUID,
        taskId: UUID,
    ): Refusal? {
        val found = state.findTask(taskId)
        val task = found?.second
        val claimer = found?.first?.claimedBy
        return when {
            state.status != InstanceStatus.RUNNING -> Refusal.WRONG_STATE
            task == null -> Refusal.TASK_NOT_FOUND
            task.state != TaskState.OPEN -> Refusal.TASK_NOT_OPEN
            task.assignee != actor -> Refusal.NOT_ASSIGNEE
            task.blocked != null -> Refusal.TASK_BLOCKED
            task.kind == TaskKind.DECIDE && claimer != null && claimer != actor -> Refusal.CLAIMED_BY_OTHER
            else -> null
        }
    }

    /** Answering a question or giving an opinion: the consultation task's holder, with the answer or opinion. */
    internal fun consultation(
        state: InstanceState,
        actor: UUID,
        taskId: UUID,
        kind: TaskKind,
        remark: String?,
    ): Refusal? =
        taskAction(state, actor, taskId)
            ?: if (state.findTask(taskId)!!.second.kind != kind) Refusal.NOT_A_CONSULTATION else Remarks.refusal(remark, required = true)

    private fun initiatorIn(
        state: InstanceState,
        actor: UUID,
        vararg statuses: InstanceStatus,
    ): Refusal? =
        when {
            actor != state.initiator -> Refusal.NOT_INITIATOR
            state.status !in statuses -> Refusal.WRONG_STATE
            else -> null
        }

    /** One person holds at most one step per round, so nobody approves twice in a round (E04 widens this into SoD rules). */
    private fun reassign(
        state: InstanceState,
        command: Reassign,
        context: TransitionContext,
    ): Refusal? {
        val found = state.findTask(command.taskId)
        val others =
            state.round.steps
                .filter { it.id != found?.first?.id }
                .map { it.assignee }
        return when {
            state.status != InstanceStatus.RUNNING -> Refusal.WRONG_STATE
            found == null -> Refusal.TASK_NOT_FOUND
            found.second.state != TaskState.OPEN -> Refusal.TASK_NOT_OPEN
            command.newAssignee == found.second.assignee -> Refusal.SAME_ASSIGNEE
            command.newAssignee == state.initiator -> Refusal.INITIATOR_AS_APPROVER
            command.newAssignee in others -> Refusal.ASSIGNEE_IN_ROUND
            context.eligibility(command.newAssignee) != null -> Refusal.ASSIGNEE_NOT_ELIGIBLE
            else -> Remarks.refusal(command.reason, required = true)
        }
    }
}
