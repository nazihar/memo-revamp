package com.memo.workflow.engine

import com.memo.workflow.definition.Definition
import com.memo.workflow.definition.ForwardWithin
import com.memo.workflow.definition.StepAction
import java.util.UUID

/** The checks of hand-ons and consultations (design E03 §4.4): the holder's decision rules, then the target. */
internal object HandOnGuards {
    const val MAX_REFERRALS = 10
    const val MAX_OPEN_OPINIONS = 30

    fun refusal(
        state: InstanceState,
        command: TaskCommand,
        context: TransitionContext,
        definition: Definition,
    ): Refusal? =
        when (command) {
            is Recuse -> {
                recusal(state, command)
            }

            is SendBack -> {
                Guards.decision(state, command.actor, command.taskId, StepAction.SEND_BACK, command.remark, definition)
                    ?: sendBackTarget(state, command)
            }

            is Forward -> {
                Guards.decision(state, command.actor, command.taskId, StepAction.FORWARD, command.remark, definition)
                    ?: forwarded(state, command, context, definition)
            }

            is Delegate -> {
                Guards.decision(state, command.actor, command.taskId, StepAction.DELEGATE, command.remark, definition)
                    ?: handOver(state, command.taskId, command.to, context)
            }

            is Ask -> {
                Guards.decision(state, command.actor, command.taskId, StepAction.ASK, command.remark, definition)
                    ?: consulted(command.actor, listOf(command.to), context)
            }

            is Refer -> {
                Guards.decision(state, command.actor, command.taskId, StepAction.REFER, command.remark, definition)
                    ?: referral(state, command, context)
            }

            is Answer -> {
                Guards.consultation(state, command.actor, command.taskId, TaskKind.CLARIFY, command.remark)
            }

            else -> {
                Guards.consultation(state, command.actor, command.taskId, TaskKind.OPINION, (command as GiveOpinion).remark)
            }
        }

    /** Only an earlier step of this round that was completed can be sent back to. */
    private fun sendBackTarget(
        state: InstanceState,
        command: SendBack,
    ): Refusal? {
        val current = state.findTask(command.taskId)!!.first
        val target = state.round.steps.firstOrNull { it.id == command.targetStepId }
        val earlier = target != null && target.state == StepState.DONE && target.ordinal < current.ordinal
        return if (earlier) null else Refusal.NOT_AN_EARLIER_STEP
    }

    private fun forwarded(
        state: InstanceState,
        command: Forward,
        context: TransitionContext,
        definition: Definition,
    ): Refusal? = handOver(state, command.taskId, command.to, context) ?: forwardLimit(state, command, context, definition)

    /** A step may keep forwarding within the holder's unit or legal entity (design E04 §4.7). */
    private fun forwardLimit(
        state: InstanceState,
        command: Forward,
        context: TransitionContext,
        definition: Definition,
    ): Refusal? {
        val (step, task) = state.findTask(command.taskId)!!
        val limit = definition.stepsById[step.nodeId]?.forward ?: ForwardWithin.ANYONE
        val holder = task.onBehalfOf ?: task.assignee
        val outside = limit != ForwardWithin.ANYONE && (holder == null || !context.within(holder, command.to, limit))
        return Refusal.FORWARD_OUTSIDE_LIMIT.takeIf { outside }
    }

    /** Forward and delegate: someone eligible, not the initiator, not the holder, and not already deciding on this step. */
    private fun handOver(
        state: InstanceState,
        taskId: UUID,
        to: UUID,
        context: TransitionContext,
    ): Refusal? {
        val (step, task) = state.findTask(taskId)!!
        val onStep = step.tasks.filter { it.state == TaskState.OPEN && it.kind == TaskKind.DECIDE }.map { it.assignee }
        return when {
            to == task.assignee -> Refusal.SAME_ASSIGNEE
            to == state.initiator -> Refusal.INITIATOR_AS_APPROVER
            to in onStep -> Refusal.ALREADY_ON_STEP
            context.eligibility(to) != null -> Refusal.ASSIGNEE_NOT_ELIGIBLE
            else -> null
        }
    }

    /** People consulted (asked or referred to) must be eligible, listed once, and not the one asking. */
    private fun consulted(
        actor: UUID,
        people: List<UUID>,
        context: TransitionContext,
    ): Refusal? =
        when {
            people.isEmpty() || people.toSet().size != people.size -> Refusal.ROUTE_DUPLICATE
            actor in people -> Refusal.SAME_ASSIGNEE
            people.any { context.eligibility(it) != null } -> Refusal.ASSIGNEE_NOT_ELIGIBLE
            else -> null
        }

    private fun referral(
        state: InstanceState,
        command: Refer,
        context: TransitionContext,
    ): Refusal? {
        val step = state.findTask(command.taskId)!!.first
        val open = step.tasks.count { it.state == TaskState.OPEN && it.kind == TaskKind.OPINION }
        val tooMany = command.to.size > MAX_REFERRALS || open + command.to.size > MAX_OPEN_OPINIONS
        return if (tooMany) Refusal.TOO_MANY_REFERRALS else consulted(command.actor, command.to, context)
    }

    /** Standing aside (SOD-4): the decision task's holder, with a reason. */
    private fun recusal(
        state: InstanceState,
        command: Recuse,
    ): Refusal? =
        Guards.taskAction(state, command.actor, command.taskId)
            ?: Refusal.ACTION_NOT_ALLOWED.takeIf { state.findTask(command.taskId)?.second?.kind != TaskKind.DECIDE }
            ?: Remarks.refusal(command.remark, required = true)
}
