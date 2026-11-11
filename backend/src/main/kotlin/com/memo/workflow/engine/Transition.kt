package com.memo.workflow.engine

import com.memo.workflow.ActionKind
import com.memo.workflow.InstanceStatus
import java.util.UUID

/**
 * The workflow's transition function (ADR-0034, ADR-0035): given a state and a command, the next state, the action row
 * and the effects to write, or a refusal. Pure: no I/O, no clock; ids and eligibility come from [context]. E01 knows one
 * definition, the sequential approval of an initiator-defined route; E03 widens the step types on the same model.
 */
class Transition(
    private val context: TransitionContext,
) {
    private val rounds = Rounds(context)

    /** Round 1 of a new instance. */
    fun start(
        initiator: UUID,
        route: List<UUID>,
        remark: String? = null,
    ): Decision {
        val refusal = Routes.refusal(initiator, route, context.maxApprovers) ?: Remarks.refusal(remark, required = false)
        if (refusal != null) return Decision.Refused(refusal)
        val round = rounds.open(1, route)
        val action = ActionRecord(ActionKind.SUBMIT, round.number, null, initiator, Remarks.clean(remark))
        return Decision.Accepted(Outcome(InstanceState(initiator, InstanceStatus.RUNNING, round), action, rounds.started(round)))
    }

    fun apply(
        state: InstanceState,
        command: Command,
    ): Decision {
        val refusal = Guards.refusal(state, command, context)
        return if (refusal != null) Decision.Refused(refusal) else Decision.Accepted(accept(state, command))
    }

    private fun accept(
        state: InstanceState,
        command: Command,
    ): Outcome =
        when (command) {
            is Approve -> approve(state, command)
            is Return -> decideRound(state, command.taskId, command.actor, command.remark, ActionKind.RETURN)
            is Reject -> decideRound(state, command.taskId, command.actor, command.remark, ActionKind.REJECT)
            is Withdraw -> withdraw(state, command)
            is Resubmit -> resubmit(state, command)
            is Reassign -> reassign(state, command)
        }

    private fun approve(
        state: InstanceState,
        command: Approve,
    ): Outcome {
        val (step, task) = state.guardedTask(command.taskId)
        val closed = state.round.closeTask(step.id, task.id, ActionKind.APPROVE, StepState.DONE)
        val next = closed.steps.indexOfFirst { it.state == StepState.PENDING }
        val effects = mutableListOf<Effect>(Effect.TaskClosed(task.id, TaskState.COMPLETED, ActionKind.APPROVE))
        val nextState =
            if (next >= 0) {
                val round = closed.copy(steps = rounds.activate(closed.steps, next))
                effects += Effect.TaskCreated(round.steps[next].id, round.steps[next].tasks.last())
                state.copy(round = round)
            } else {
                effects += Effect.RoundEnded(closed.number, RoundOutcome.APPROVED)
                effects += Effect.InstanceEnded(InstanceStatus.APPROVED)
                state.copy(status = InstanceStatus.APPROVED, round = closed.copy(outcome = RoundOutcome.APPROVED))
            }
        return Outcome(nextState, record(ActionKind.APPROVE, state, task.id, command.actor, command.remark), effects)
    }

    /** Return or reject: the round ends here; steps not reached are skipped. Only a rejection ends the instance. */
    private fun decideRound(
        state: InstanceState,
        taskId: UUID,
        actor: UUID,
        remark: String?,
        kind: ActionKind,
    ): Outcome {
        val (step, _) = state.guardedTask(taskId)
        val (roundOutcome, status) =
            if (kind == ActionKind.RETURN) {
                RoundOutcome.RETURNED to InstanceStatus.RETURNED
            } else {
                RoundOutcome.REJECTED to InstanceStatus.REJECTED
            }
        val round =
            state.round
                .closeTask(step.id, taskId, kind, StepState.DONE)
                .skipPending()
                .copy(outcome = roundOutcome)
        val effects =
            listOfNotNull(
                Effect.TaskClosed(taskId, TaskState.COMPLETED, kind),
                Effect.RoundEnded(round.number, roundOutcome),
                Effect.InstanceEnded(status).takeIf { status.terminal },
            )
        return Outcome(state.copy(status = status, round = round), record(kind, state, taskId, actor, remark), effects)
    }

    private fun withdraw(
        state: InstanceState,
        command: Withdraw,
    ): Outcome {
        val cancelled = state.openTasks.map { Effect.TaskClosed(it.id, TaskState.CANCELLED, null) }
        val steps =
            state.round.steps.map { step ->
                step.copy(
                    state = if (step.state == StepState.ACTIVE || step.state == StepState.PENDING) StepState.SKIPPED else step.state,
                    tasks = step.tasks.map { if (it.state == TaskState.OPEN) it.copy(state = TaskState.CANCELLED) else it },
                )
            }
        val wasOpen = state.round.outcome == RoundOutcome.OPEN
        val round = state.round.copy(steps = steps, outcome = if (wasOpen) RoundOutcome.WITHDRAWN else state.round.outcome)
        val effects =
            cancelled +
                listOfNotNull(Effect.RoundEnded(round.number, RoundOutcome.WITHDRAWN).takeIf { wasOpen }) +
                Effect.InstanceEnded(InstanceStatus.WITHDRAWN)
        val action = record(ActionKind.WITHDRAW, state, null, command.actor, command.remark)
        return Outcome(state.copy(status = InstanceStatus.WITHDRAWN, round = round), action, effects)
    }

    private fun resubmit(
        state: InstanceState,
        command: Resubmit,
    ): Outcome {
        val round = rounds.open(state.round.number + 1, command.route)
        val next = InstanceState(state.initiator, InstanceStatus.RUNNING, round)
        return Outcome(next, record(ActionKind.RESUBMIT, next, null, command.actor, command.remark), rounds.started(round))
    }

    private fun reassign(
        state: InstanceState,
        command: Reassign,
    ): Outcome {
        val (step, task) = state.guardedTask(command.taskId)
        val replacement = rounds.newTask(command.newAssignee)
        val round =
            state.round.replaceStep(step.id) { current ->
                current.copy(
                    assignee = command.newAssignee,
                    tasks = current.tasks.map { if (it.id == task.id) it.copy(state = TaskState.REASSIGNED) else it } + replacement,
                )
            }
        val effects =
            listOf(Effect.TaskClosed(task.id, TaskState.REASSIGNED, null), Effect.TaskCreated(step.id, replacement))
        return Outcome(state.copy(round = round), record(ActionKind.REASSIGN, state, task.id, command.actor, command.reason), effects)
    }
}

private fun record(
    kind: ActionKind,
    state: InstanceState,
    taskId: UUID?,
    actor: UUID,
    remark: String?,
): ActionRecord = ActionRecord(kind, state.round.number, taskId, actor, Remarks.clean(remark))

private fun Round.closeTask(
    stepId: UUID,
    taskId: UUID,
    outcome: ActionKind,
    stepState: StepState,
): Round =
    replaceStep(stepId) { step ->
        step.copy(
            state = stepState,
            tasks = step.tasks.map { if (it.id == taskId) it.copy(state = TaskState.COMPLETED, outcome = outcome) else it },
        )
    }

private fun Round.skipPending(): Round =
    copy(steps = steps.map { if (it.state == StepState.PENDING) it.copy(state = StepState.SKIPPED) else it })

/** The task a command names; [Guards] has already refused commands whose task is missing. */
private fun InstanceState.guardedTask(taskId: UUID): Pair<Step, Task> =
    checkNotNull(findTask(taskId)) { "task $taskId passed the guards but is missing" }
