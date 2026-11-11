package com.memo.workflow.engine

import java.util.UUID

/** Builds rounds and tasks for [Transition]: steps copied from the route, the first one active, eligibility per task. */
internal class Rounds(
    private val context: TransitionContext,
) {
    fun open(
        number: Int,
        route: List<UUID>,
    ): Round {
        val steps = route.mapIndexed { index, approver -> Step(context.newId(), index + 1, approver, StepState.PENDING) }
        return Round(number, RoundOutcome.OPEN, activate(steps, 0))
    }

    fun activate(
        steps: List<Step>,
        index: Int,
    ): List<Step> =
        steps.mapIndexed { i, step ->
            if (i == index) step.copy(state = StepState.ACTIVE, tasks = step.tasks + newTask(step.assignee)) else step
        }

    fun newTask(assignee: UUID): Task = Task(context.newId(), assignee, TaskState.OPEN, context.eligibility(assignee))

    fun started(round: Round): List<Effect> =
        listOf(Effect.RoundStarted(round.number)) +
            round.steps.filter { it.state == StepState.ACTIVE }.map { Effect.TaskCreated(it.id, it.tasks.last()) }
}
