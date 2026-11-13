package com.memo.workflow.engine

import com.memo.workflow.ActionKind
import com.memo.workflow.BlockedReason
import com.memo.workflow.InstanceStatus

/**
 * An overdue task escalates (ADR-0041, design E05 §4.3), as the workflow itself: [Escalate.move] hands the task to
 * [Escalate.to] (or, when nobody was found, to a blocked task an administrator assigns), keeping the step's due time;
 * otherwise the holder keeps it and [Escalate.targets] are told. Recorded as `ESCALATE`. Pure, like [Transition].
 */
internal class Escalations(
    private val context: TransitionContext,
    private val rounds: Rounds,
) {
    fun apply(
        state: InstanceState,
        command: Escalate,
    ): Outcome {
        val (step, task) = state.guardedTask(command.taskId)
        val details = mapOf("action" to command.action, "to" to (command.to ?: command.targets.firstOrNull())?.toString())
        val action = record(ActionKind.ESCALATE, state, task.id, command.actor, null).copy(details = details)
        if (!command.move) return Outcome(state, action, listOf(Effect.Escalated(task.id, command.targets)))
        val replacement =
            command.to?.let(rounds::newTask)
                ?: Task(context.newId(), null, TaskState.OPEN, BlockedReason.UNRESOLVED)
        val round =
            state.round.replaceStep(step.id) { current ->
                val closed = current.tasks.map { if (it.id == task.id) it.copy(state = TaskState.REASSIGNED) else it }
                current.copy(assignee = command.to, tasks = closed + replacement)
            }
        val effects = listOf(Effect.TaskClosed(task.id, TaskState.REASSIGNED, null), Effect.TaskCreated(step.id, replacement))
        return Outcome(state.copy(round = round), action, effects)
    }

    companion object {
        fun refusal(
            state: InstanceState,
            command: Escalate,
        ): Refusal? {
            val task = state.findTask(command.taskId)?.second
            return when {
                state.status != InstanceStatus.RUNNING -> Refusal.WRONG_STATE
                task == null -> Refusal.TASK_NOT_FOUND
                task.state != TaskState.OPEN || task.kind != TaskKind.DECIDE -> Refusal.TASK_NOT_OPEN
                else -> null
            }
        }
    }
}
