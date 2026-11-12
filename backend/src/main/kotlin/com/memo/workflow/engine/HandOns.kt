package com.memo.workflow.engine

import com.memo.workflow.ActionKind
import java.util.UUID

/**
 * What a holder can do besides deciding (design E03 §4.4): send the item back to an earlier step, hand the task on
 * (forward moves responsibility, delegate keeps it), and consult others (ask and answer, refer and opinion). Pure, like
 * [Transition], which has already checked every command with [Guards].
 */
internal class HandOns(
    private val rounds: Rounds,
) {
    /** One of the hand-on or consultation commands, already checked by [HandOnGuards]. */
    fun apply(
        state: InstanceState,
        command: TaskCommand,
    ): Outcome =
        when (command) {
            is SendBack -> sendBack(state, command)
            is Forward -> forward(state, command)
            is Delegate -> delegate(state, command)
            is Ask -> ask(state, command)
            is Answer -> answer(state, command)
            is Refer -> refer(state, command)
            is Recuse -> recuse(state, command)
            else -> opinion(state, command as GiveOpinion)
        }

    /**
     * The holder stands aside (SOD-4, design E04 §4.4): their task closes. Other candidates of a shared step carry on;
     * otherwise the step resolves again without them, or waits for an administrator.
     */
    fun recuse(
        state: InstanceState,
        command: Recuse,
    ): Outcome {
        val (step, task) = state.guardedTask(command.taskId)
        val closed =
            state.round.replaceStep(step.id) { current ->
                current.copy(
                    tasks = current.tasks.map { if (it.id == task.id) it.copy(state = TaskState.CANCELLED) else it },
                    claimedBy = current.claimedBy.takeUnless { it == task.assignee },
                )
            }
        val closing = Effect.TaskClosed(task.id, TaskState.CANCELLED, null)
        val action = record(ActionKind.RECUSE, state, task.id, command.actor, command.remark)
        if (closed.step(step.id).tasks.any { it.state == TaskState.OPEN && it.kind == TaskKind.DECIDE }) {
            return Outcome(state.copy(round = closed), action, listOf(closing))
        }
        val open = closed.step(step.id).tasks.filter { it.state == TaskState.OPEN }
        val skipped =
            closed.replaceStep(step.id) { current ->
                current.copy(
                    state = StepState.SKIPPED,
                    tasks = current.tasks.map { if (it.state == TaskState.OPEN) it.copy(state = TaskState.CANCELLED) else it },
                )
            }
        val walk = rounds.reactivate(skipped, step, state.initiator, setOf(command.actor))
        val cancelled = open.map { Effect.TaskClosed(it.id, TaskState.CANCELLED, null) }
        return Outcome(state.copy(round = walk.round), action, listOf(closing) + cancelled + walk.effects, walk.followUps)
    }

    /** The task ends; open tasks of the round are cancelled; the target step runs again, and what followed it after that. */
    fun sendBack(
        state: InstanceState,
        command: SendBack,
    ): Outcome {
        val (step, task) = state.guardedTask(command.taskId)
        val target = state.round.step(command.targetStepId)
        val (cancelledRound, cancelled) = state.round.closeTask(step.id, task.id, ActionKind.SEND_BACK, StepState.DONE).cancelOpen()
        val walk = rounds.reactivate(cancelledRound.skipPending(), target, state.initiator)
        val effects = listOf(Effect.TaskClosed(task.id, TaskState.COMPLETED, ActionKind.SEND_BACK)) + cancelled + walk.effects
        val details = mapOf("toStep" to target.nodeId, "toStepId" to target.id.toString())
        val action = record(ActionKind.SEND_BACK, state, task.id, command.actor, command.remark).copy(details = details)
        return Outcome(state.copy(round = walk.round), action, effects)
    }

    /** [Forward.to] takes over the task and becomes the step's holder (a parallel step keeps its list of holders). */
    fun forward(
        state: InstanceState,
        command: Forward,
    ): Outcome = handOn(state, command.taskId, command.actor, command.to, command.remark, ActionKind.FORWARD)

    /** [Delegate.to] takes over the task to act for its holder, who stays responsible for the step. */
    fun delegate(
        state: InstanceState,
        command: Delegate,
    ): Outcome = handOn(state, command.taskId, command.actor, command.to, command.remark, ActionKind.DELEGATE)

    /** [Ask.to] gets a clarification task; the holder's task waits for the answer. */
    fun ask(
        state: InstanceState,
        command: Ask,
    ): Outcome {
        val (step, task) = state.guardedTask(command.taskId)
        val question = rounds.newTask(command.to).copy(kind = TaskKind.CLARIFY, parentTaskId = task.id)
        val round =
            state.round.replaceStep(step.id) { current ->
                current.copy(tasks = current.tasks.map { if (it.id == task.id) it.copy(waiting = true) else it } + question)
            }
        val effects = listOf(Effect.TaskWaiting(task.id, true), Effect.TaskCreated(step.id, question))
        val action =
            record(ActionKind.ASK, state, task.id, command.actor, command.remark).copy(
                details =
                    mapOf("to" to command.to.toString()),
            )
        return Outcome(state.copy(round = round), action, effects)
    }

    /** The answer ends the clarification; the task that asked can be decided again. */
    fun answer(
        state: InstanceState,
        command: Answer,
    ): Outcome {
        val (step, question) = state.guardedTask(command.taskId)
        val asker = checkNotNull(question.parentTaskId)
        val round =
            state.round.replaceStep(step.id) { current ->
                current.copy(
                    tasks =
                        current.tasks.map {
                            when (it.id) {
                                question.id -> it.copy(state = TaskState.COMPLETED, outcome = ActionKind.ANSWER)
                                asker -> it.copy(waiting = false)
                                else -> it
                            }
                        },
                )
            }
        val effects = listOf(Effect.TaskClosed(question.id, TaskState.COMPLETED, ActionKind.ANSWER), Effect.TaskWaiting(asker, false))
        return Outcome(state.copy(round = round), record(ActionKind.ANSWER, state, question.id, command.actor, command.remark), effects)
    }

    /** Each person referred to gets an opinion task; the holder keeps the task and decides when ready. */
    fun refer(
        state: InstanceState,
        command: Refer,
    ): Outcome {
        val (step, task) = state.guardedTask(command.taskId)
        val opinions = command.to.map { rounds.newTask(it).copy(kind = TaskKind.OPINION, parentTaskId = task.id) }
        val round = state.round.replaceStep(step.id) { it.copy(tasks = it.tasks + opinions) }
        val details = mapOf("to" to command.to.map { it.toString() })
        val action = record(ActionKind.REFER, state, task.id, command.actor, command.remark).copy(details = details)
        return Outcome(state.copy(round = round), action, opinions.map { Effect.TaskCreated(step.id, it) })
    }

    /** The opinion is given; it stays in the history for the holder (and everyone who may read the item). */
    fun opinion(
        state: InstanceState,
        command: GiveOpinion,
    ): Outcome {
        val (step, opinion) = state.guardedTask(command.taskId)
        val round =
            state.round.replaceStep(step.id) { current ->
                current.copy(
                    tasks =
                        current.tasks.map {
                            if (it.id ==
                                opinion.id
                            ) {
                                it.copy(state = TaskState.COMPLETED, outcome = ActionKind.OPINION)
                            } else {
                                it
                            }
                        },
                )
            }
        val effects = listOf(Effect.TaskClosed(opinion.id, TaskState.COMPLETED, ActionKind.OPINION))
        return Outcome(state.copy(round = round), record(ActionKind.OPINION, state, opinion.id, command.actor, command.remark), effects)
    }

    private fun handOn(
        state: InstanceState,
        taskId: UUID,
        actor: UUID,
        to: UUID,
        remark: String?,
        kind: ActionKind,
    ): Outcome {
        val (step, task) = state.guardedTask(taskId)
        val delegated = kind == ActionKind.DELEGATE
        val replacement = rounds.newTask(to).let { if (delegated) it.copy(onBehalfOf = task.assignee) else it }
        val round =
            state.round.replaceStep(step.id) { current ->
                current.copy(
                    assignee = if (delegated || current.shared) current.assignee else to,
                    tasks = current.tasks.map { if (it.id == task.id) it.copy(state = TaskState.TRANSFERRED) else it } + replacement,
                )
            }
        val effects = listOf(Effect.TaskClosed(task.id, TaskState.TRANSFERRED, null), Effect.TaskCreated(step.id, replacement))
        val action = record(kind, state, task.id, actor, remark).copy(details = mapOf("to" to to.toString()))
        return Outcome(state.copy(round = round), action, effects)
    }
}
