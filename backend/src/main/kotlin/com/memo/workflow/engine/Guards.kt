package com.memo.workflow.engine

import com.memo.workflow.InstanceStatus
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

/** The shape of an initiator-defined route (invariant 11); clearance and account state are the subject's checks. */
internal object Routes {
    fun refusal(
        initiator: UUID,
        route: List<UUID>,
        maxApprovers: Int,
    ): Refusal? =
        when {
            route.isEmpty() -> Refusal.ROUTE_EMPTY
            route.size > maxApprovers -> Refusal.ROUTE_TOO_LONG
            initiator in route -> Refusal.INITIATOR_AS_APPROVER
            route.toSet().size != route.size -> Refusal.ROUTE_DUPLICATE
            else -> null
        }
}

/** Whether a command may run against a state (design E01 §3); null means it may. */
internal object Guards {
    fun refusal(
        state: InstanceState,
        command: Command,
        context: TransitionContext,
    ): Refusal? =
        when (command) {
            is Approve -> {
                taskAction(state, command.actor, command.taskId) ?: Remarks.refusal(command.remark, required = false)
            }

            is Return -> {
                taskAction(state, command.actor, command.taskId) ?: Remarks.refusal(command.remark, required = true)
            }

            is Reject -> {
                taskAction(state, command.actor, command.taskId) ?: Remarks.refusal(command.remark, required = true)
            }

            is Withdraw -> {
                initiatorIn(state, command.actor, InstanceStatus.RUNNING, InstanceStatus.RETURNED)
                    ?: Remarks.refusal(command.remark, required = false)
            }

            is Resubmit -> {
                initiatorIn(state, command.actor, InstanceStatus.RETURNED)
                    ?: Routes.refusal(state.initiator, command.route, context.maxApprovers)
                    ?: Remarks.refusal(command.remark, required = false)
            }

            is Reassign -> {
                reassign(state, command, context)
            }
        }

    private fun taskAction(
        state: InstanceState,
        actor: UUID,
        taskId: UUID,
    ): Refusal? {
        val task = state.findTask(taskId)?.second
        return when {
            state.status != InstanceStatus.RUNNING -> Refusal.WRONG_STATE
            task == null -> Refusal.TASK_NOT_FOUND
            task.state != TaskState.OPEN -> Refusal.TASK_NOT_OPEN
            task.assignee != actor -> Refusal.NOT_ASSIGNEE
            task.blocked != null -> Refusal.TASK_BLOCKED
            else -> null
        }
    }

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
