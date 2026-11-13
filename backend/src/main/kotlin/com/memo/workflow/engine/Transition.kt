package com.memo.workflow.engine

import com.memo.workflow.ActionKind
import com.memo.workflow.InstanceStatus
import com.memo.workflow.definition.Definition
import com.memo.workflow.definition.DefinitionCompiler
import com.memo.workflow.definition.ResubmitPolicy
import com.memo.workflow.definition.StepType
import java.util.UUID

/**
 * The workflow's transition function (ADR-0034, ADR-0035): given a state and a command, the next state, the action row
 * and the effects to write, or a refusal. Pure: no I/O, no clock; ids and eligibility come from [context]. The instance
 * follows its compiled [definition] (E03); the default is E01's built-in sequential approval of the initiator's route.
 */
class Transition(
    private val context: TransitionContext,
    private val definition: Definition = DefinitionCompiler.SEQUENTIAL,
) {
    private val rounds = Rounds(context, definition)
    private val handOns = HandOns(rounds)

    /** Whether the definition takes the initiator's route; without a route step the route must be empty. */
    private val routed = definition.steps.any { it.type == StepType.ROUTE }

    /** Round 1 of a new instance. */
    fun start(
        initiator: UUID,
        route: List<UUID>,
        remark: String? = null,
    ): Decision {
        val refusal = Routes.refusal(initiator, route, context.maxApprovers, routed) ?: Remarks.refusal(remark, required = false)
        if (refusal != null) return Decision.Refused(refusal)
        val walk = rounds.open(1, route, People(initiator, initiator))
        val action = ActionRecord(ActionKind.SUBMIT, 1, null, initiator, Remarks.clean(remark))
        return Decision.Accepted(opened(initiator, walk, action))
    }

    /** A new round's outcome: started, its first tasks, and approved at once when the walk reached the end. */
    private fun opened(
        initiator: UUID,
        walk: Walk,
        action: ActionRecord,
    ): Outcome {
        val started = listOf(Effect.RoundStarted(walk.round.number)) + walk.effects
        val state = InstanceState(initiator, InstanceStatus.RUNNING, walk.round)
        return if (walk.finished) {
            val done = state.copy(status = InstanceStatus.APPROVED, round = walk.round.copy(outcome = RoundOutcome.APPROVED))
            val ended = listOf(Effect.RoundEnded(walk.round.number, RoundOutcome.APPROVED), Effect.InstanceEnded(InstanceStatus.APPROVED))
            Outcome(done, action, started + ended, walk.followUps)
        } else {
            Outcome(state, action, started, walk.followUps)
        }
    }

    fun apply(
        state: InstanceState,
        command: Command,
    ): Decision {
        val refusal = Guards.refusal(state, command, context, definition, routed)
        return if (refusal != null) Decision.Refused(refusal) else Decision.Accepted(actingFor(state, command, accept(state, command)))
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
            is SendBack, is Forward, is Delegate, is Ask, is Answer, is Refer, is GiveOpinion, is Recuse -> handOns.apply(state, command)
            is Recall, is Cancel -> RoundControl.apply(state, command)
            is Migrate -> Migrations.apply(state, command)
            is Claim, is Release -> Claims.apply(state, command)
        }

    /** A delegate's action is recorded as acting for the holder who delegated the task (design E03 §4.4, invariant 17). */
    private fun actingFor(
        state: InstanceState,
        command: Command,
        outcome: Outcome,
    ): Outcome {
        val taskId = (command as? TaskCommand)?.taskId
        val holder = taskId?.let { state.findTask(it)?.second?.onBehalfOf }
        return if (holder == null) outcome else outcome.copy(action = outcome.action.copy(actingFor = holder))
    }

    /** The positive action: Approve, or Recommend on a review step; then on to the next step that needs a person. */
    private fun approve(
        state: InstanceState,
        command: Approve,
    ): Outcome {
        val (step, task) = state.guardedTask(command.taskId)
        val kind = if (step.kind == StepKind.REVIEW) ActionKind.RECOMMEND else ActionKind.APPROVE
        val action = record(kind, state, task.id, command.actor, command.remark)
        val closedTask = Effect.TaskClosed(task.id, TaskState.COMPLETED, kind)
        val parallel = step.kind == StepKind.PARALLEL
        val decided = state.round.closeTask(step.id, task.id, kind, if (parallel) StepState.ACTIVE else StepState.DONE)
        // A parallel step waits until it has its approvals (design E03 §4.3); then its other tasks are no longer needed.
        val waiting = parallel && Parallel.verdict(decided.step(step.id), definition.step(step.nodeId)) == Verdict.WAITING
        return if (waiting) {
            Outcome(state.copy(round = decided), action, listOf(closedTask))
        } else {
            val (settled, cancelled) = decided.settle(step.id)
            advanced(state, settled, step, action, listOf(closedTask) + cancelled, command.actor)
        }
    }

    /** After a step completed: on to the next step that needs a person, or the end (the round and instance approved). */
    private fun advanced(
        state: InstanceState,
        closed: Round,
        step: Step,
        action: ActionRecord,
        closing: List<Effect>,
        actor: UUID,
    ): Outcome {
        val walk = rounds.advance(closed, step, People(state.initiator, actor))
        val effects = closing + walk.effects
        return if (walk.finished) {
            val ended = listOf(Effect.RoundEnded(closed.number, RoundOutcome.APPROVED), Effect.InstanceEnded(InstanceStatus.APPROVED))
            val done = state.copy(status = InstanceStatus.APPROVED, round = walk.round.copy(outcome = RoundOutcome.APPROVED))
            Outcome(done, action, effects + ended, walk.followUps)
        } else {
            Outcome(state.copy(round = walk.round), action, effects, walk.followUps)
        }
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
        val action = record(kind, state, taskId, actor, remark)
        val closedTask = Effect.TaskClosed(taskId, TaskState.COMPLETED, kind)
        // In a parallel step a rejection is one "no"; it rejects only when the step can no longer succeed (design E03 §4.3).
        val parallelNo = kind == ActionKind.REJECT && step.kind == StepKind.PARALLEL
        val decided = state.round.closeTask(step.id, taskId, kind, if (parallelNo) StepState.ACTIVE else StepState.DONE)
        if (parallelNo && Parallel.verdict(decided.step(step.id), definition.step(step.nodeId)) == Verdict.WAITING) {
            return Outcome(state.copy(round = decided), action, listOf(closedTask))
        }
        val (roundOutcome, status) =
            if (kind == ActionKind.RETURN) {
                RoundOutcome.RETURNED to InstanceStatus.RETURNED
            } else {
                RoundOutcome.REJECTED to InstanceStatus.REJECTED
            }
        val (cancelledRound, cancelled) = decided.cancelOpen()
        val round =
            cancelledRound
                .replaceStep(step.id) { it.copy(state = StepState.DONE) }
                .skipPending()
                .copy(outcome = roundOutcome)
        val effects =
            listOf(closedTask) + cancelled +
                listOfNotNull(
                    Effect.RoundEnded(round.number, roundOutcome),
                    Effect.InstanceEnded(status).takeIf { status.terminal },
                )
        return Outcome(state.copy(status = status, round = round), action, effects)
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
        val people = People(state.initiator, command.actor)
        val number = state.round.number + 1
        // `resume` starts again where the item was returned; otherwise from the start (design E03 §4.6).
        val returning = state.round.steps.firstOrNull { step -> step.tasks.any { it.outcome == ActionKind.RETURN } }
        val walk =
            if (definition.resubmitPolicy == ResubmitPolicy.RESUME && returning != null) {
                rounds.resume(number, command.route, people, returning)
            } else {
                rounds.open(number, command.route, people)
            }
        val action = ActionRecord(ActionKind.RESUBMIT, walk.round.number, null, command.actor, Remarks.clean(command.remark))
        return opened(state.initiator, walk, action)
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

internal fun record(
    kind: ActionKind,
    state: InstanceState,
    taskId: UUID?,
    actor: UUID,
    remark: String?,
): ActionRecord = ActionRecord(kind, state.round.number, taskId, actor, Remarks.clean(remark))

internal fun Round.closeTask(
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

internal fun Round.skipPending(): Round =
    copy(steps = steps.map { if (it.state == StepState.PENDING) it.copy(state = StepState.SKIPPED) else it })

internal fun Round.step(id: UUID): Step = steps.first { it.id == id }

/** The step is done: its tasks still open (other parallel holders, consultations) are cancelled. */
internal fun Round.settle(stepId: UUID): Pair<Round, List<Effect>> {
    val open = step(stepId).tasks.filter { it.state == TaskState.OPEN }
    val round =
        replaceStep(stepId) { step ->
            step.copy(
                state = StepState.DONE,
                tasks = step.tasks.map { if (it.state == TaskState.OPEN) it.copy(state = TaskState.CANCELLED) else it },
            )
        }
    return round to open.map { Effect.TaskClosed(it.id, TaskState.CANCELLED, null) }
}

/** Every task still open in the round is cancelled (the round ends). */
internal fun Round.cancelOpen(): Pair<Round, List<Effect>> {
    val open = steps.flatMap { step -> step.tasks.filter { it.state == TaskState.OPEN } }
    val round =
        copy(
            steps =
                steps.map { step ->
                    step.copy(
                        tasks =
                            step.tasks.map {
                                if (it.state ==
                                    TaskState.OPEN
                                ) {
                                    it.copy(state = TaskState.CANCELLED)
                                } else {
                                    it
                                }
                            },
                    )
                },
        )
    return round to open.map { Effect.TaskClosed(it.id, TaskState.CANCELLED, null) }
}

/** The task a command names; [Guards] has already refused commands whose task is missing. */
internal fun InstanceState.guardedTask(taskId: UUID): Pair<Step, Task> =
    checkNotNull(findTask(taskId)) { "task $taskId passed the guards but is missing" }
