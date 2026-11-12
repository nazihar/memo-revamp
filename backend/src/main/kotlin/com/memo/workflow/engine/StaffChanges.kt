package com.memo.workflow.engine

import com.memo.workflow.ActionKind
import com.memo.workflow.BlockedReason
import com.memo.workflow.InstanceStatus

/**
 * Staff changes while an item is in flight (design E04 §4.6, ADR-0040): a deactivated holder's task waits for an
 * administrator, and a step whose position changed holder resolves again. Both are recorded as reassignments by whoever
 * changed the organisation. Pure, like [Transition].
 */
internal class StaffChanges(
    private val rounds: Rounds,
) {
    fun apply(
        state: InstanceState,
        command: Command,
    ): Outcome = if (command is Block) block(state, command) else reresolve(state, command as Reresolve)

    private fun block(
        state: InstanceState,
        command: Block,
    ): Outcome {
        val (step, task) = state.guardedTask(command.taskId)
        val replacement = task.copy(id = rounds.newTask(checkNotNull(task.assignee)).id, blocked = BlockedReason.INACTIVE)
        val round =
            state.round.replaceStep(step.id) { current ->
                val closed = current.tasks.map { if (it.id == task.id) it.copy(state = TaskState.REASSIGNED) else it }
                current.copy(tasks = closed + replacement)
            }
        val effects = listOf(Effect.TaskClosed(task.id, TaskState.REASSIGNED, null), Effect.TaskCreated(step.id, replacement))
        val action = record(ActionKind.REASSIGN, state, task.id, command.actor, null).copy(details = mapOf("reason" to INACTIVE))
        return Outcome(state.copy(round = round), action, effects)
    }

    private fun reresolve(
        state: InstanceState,
        command: Reresolve,
    ): Outcome {
        val step = state.round.step(command.stepId)
        val open = step.tasks.filter { it.state == TaskState.OPEN && it.kind == TaskKind.DECIDE }
        val replaced =
            state.round.replaceStep(step.id) { current ->
                current.copy(
                    state = StepState.SKIPPED,
                    tasks = current.tasks.map { if (it.state == TaskState.OPEN) it.copy(state = TaskState.REASSIGNED) else it },
                    claimedBy = null,
                )
            }
        val closed = step.tasks.filter { it.state == TaskState.OPEN }.map { Effect.TaskClosed(it.id, TaskState.REASSIGNED, null) }
        val walk = rounds.reactivate(replaced, step.copy(assignee = null), state.initiator)
        val details = mapOf("step" to step.nodeId, "reason" to HOLDER_CHANGED, "from" to open.mapNotNull { it.assignee?.toString() })
        val action = record(ActionKind.REASSIGN, state, null, command.actor, null).copy(details = details)
        return Outcome(state.copy(round = walk.round), action, closed + walk.effects, walk.followUps)
    }

    companion object {
        const val INACTIVE = "INACTIVE"
        const val HOLDER_CHANGED = "HOLDER_CHANGED"

        fun refusal(
            state: InstanceState,
            command: Command,
        ): Refusal? {
            val running = state.status == InstanceStatus.RUNNING
            return if (command is Block) {
                val task = state.findTask(command.taskId)?.second
                when {
                    !running -> Refusal.WRONG_STATE
                    task == null -> Refusal.TASK_NOT_FOUND
                    task.state != TaskState.OPEN || task.assignee == null -> Refusal.TASK_NOT_OPEN
                    task.blocked != null -> Refusal.ALREADY_BLOCKED
                    else -> null
                }
            } else {
                val step = state.round.steps.firstOrNull { it.id == (command as Reresolve).stepId }
                val open = step?.tasks?.any { it.state == TaskState.OPEN && it.kind == TaskKind.DECIDE } == true
                // A route entry naming a person stays theirs; one naming a position or a group resolves again.
                val named =
                    step?.routeIndex?.let {
                        state.round.route
                            .getOrNull(it)
                            ?.userId
                    } != null
                val resolvable = step?.state == StepState.ACTIVE && !named
                if (running && resolvable && open) null else Refusal.NOTHING_TO_RESOLVE
            }
        }
    }
}
