package com.memo.workflow.engine

import com.memo.workflow.ActionKind
import com.memo.workflow.ForumOutcome
import com.memo.workflow.InstanceStatus
import java.util.UUID

/**
 * An item waiting at a forum step (ADR-0058): the forum's decision continues the route, rejects the item or returns it to
 * its initiator; a declined item is returned with the reason. Pure, like [Transition].
 */
internal class ForumSteps(
    private val rounds: Rounds,
) {
    fun apply(
        state: InstanceState,
        command: Command,
    ): Outcome =
        when (command) {
            is ForumDecided -> decided(state, command)
            is ForumDeclined -> ended(state, command.stepId, command.actor, command.remark, ActionKind.FORUM_DECLINED, null)
            else -> error("not a forum command: $command")
        }

    private fun decided(
        state: InstanceState,
        command: ForumDecided,
    ): Outcome =
        when (command.outcome) {
            ForumOutcome.REJECTED, ForumOutcome.RETURNED -> {
                ended(state, command.stepId, command.actor, command.remark, ActionKind.FORUM_DECIDED, command.outcome, command.details)
            }

            else -> {
                continued(state, command)
            }
        }

    /** The step is done; the walk goes on to the next step that needs a person, or the end. */
    private fun continued(
        state: InstanceState,
        command: ForumDecided,
    ): Outcome {
        val step = state.round.step(command.stepId)
        val action = forumRecord(state, ActionKind.FORUM_DECIDED, command.actor, command.remark, step, command.outcome, command.details)
        val closed = state.round.replaceStep(step.id) { it.copy(state = StepState.DONE) }
        val walk = rounds.advance(closed, step, People(state.initiator, command.actor))
        return if (walk.finished) {
            val ended = listOf(Effect.RoundEnded(closed.number, RoundOutcome.APPROVED), Effect.InstanceEnded(InstanceStatus.APPROVED))
            val done = state.copy(status = InstanceStatus.APPROVED, round = walk.round.copy(outcome = RoundOutcome.APPROVED))
            Outcome(done, action, walk.effects + ended, walk.followUps)
        } else {
            Outcome(state.copy(round = walk.round), action, walk.effects, walk.followUps)
        }
    }

    /** Rejected ends the instance; returned and declined send the item back to its initiator. Steps not reached are skipped. */
    private fun ended(
        state: InstanceState,
        stepId: UUID,
        actor: UUID,
        remark: String?,
        kind: ActionKind,
        outcome: ForumOutcome?,
        details: Map<String, Any?> = emptyMap(),
    ): Outcome {
        val step = state.round.step(stepId)
        val action = forumRecord(state, kind, actor, remark, step, outcome, details)
        val rejected = outcome == ForumOutcome.REJECTED
        val roundOutcome = if (rejected) RoundOutcome.REJECTED else RoundOutcome.RETURNED
        val status = if (rejected) InstanceStatus.REJECTED else InstanceStatus.RETURNED
        val round =
            state.round
                .replaceStep(step.id) { it.copy(state = StepState.DONE) }
                .skipPending()
                .copy(outcome = roundOutcome)
        val effects =
            listOfNotNull(
                Effect.RoundEnded(round.number, roundOutcome),
                Effect.InstanceEnded(status).takeIf { status.terminal },
            )
        return Outcome(state.copy(status = status, round = round), action, effects)
    }

    private fun forumRecord(
        state: InstanceState,
        kind: ActionKind,
        actor: UUID,
        remark: String?,
        step: Step,
        outcome: ForumOutcome?,
        extra: Map<String, Any?> = emptyMap(),
    ): ActionRecord =
        record(kind, state, null, actor, remark).copy(
            details =
                extra.filterValues { it != null } +
                    mapOfNotNull("step" to step.nodeId, "forum" to step.resolution?.get("forum"), "outcome" to outcome?.name),
        )

    companion object {
        /** Whether [command] may run: the instance runs, the step waits for a forum, and the remark fits. */
        fun refusal(
            state: InstanceState,
            command: Command,
        ): Refusal? {
            val stepId = (command as? ForumDecided)?.stepId ?: (command as ForumDeclined).stepId
            val step = state.round.steps.firstOrNull { it.id == stepId }
            val remark = (command as? ForumDecided)?.remark ?: (command as? ForumDeclined)?.remark
            return when {
                step == null -> Refusal.STEP_MISSING
                !waiting(state, step) -> Refusal.WRONG_STATE
                else -> Remarks.refusal(remark, required = command is ForumDeclined)
            }
        }
    }
}

private fun waiting(
    state: InstanceState,
    step: Step,
): Boolean = state.status == InstanceStatus.RUNNING && step.kind == StepKind.FORUM && step.state == StepState.ACTIVE

private fun mapOfNotNull(vararg pairs: Pair<String, Any?>): Map<String, Any?> = pairs.filter { it.second != null }.toMap()
