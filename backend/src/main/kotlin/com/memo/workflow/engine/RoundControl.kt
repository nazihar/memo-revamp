package com.memo.workflow.engine

import com.memo.workflow.ActionKind
import com.memo.workflow.InstanceStatus
import java.util.UUID

/**
 * Stopping a round early (design E03 §4.4): the initiator's recall before anyone acted (the item comes back to them,
 * as after a return), and an administrator's cancellation (the instance ends). Pure, like [Transition].
 */
internal object RoundControl {
    fun apply(
        state: InstanceState,
        command: Command,
    ): Outcome = if (command is Recall) recall(state, command) else cancel(state, command as Cancel)

    fun recall(
        state: InstanceState,
        command: Recall,
    ): Outcome = stop(state, command.actor, command.remark, ActionKind.RECALL, RoundOutcome.RECALLED, InstanceStatus.RETURNED)

    fun cancel(
        state: InstanceState,
        command: Cancel,
    ): Outcome = stop(state, command.actor, command.remark, ActionKind.CANCEL, RoundOutcome.CANCELLED, InstanceStatus.CANCELLED)

    /** Every open task is cancelled and steps not done are skipped; an open round ends as [roundOutcome]. */
    private fun stop(
        state: InstanceState,
        actor: UUID,
        remark: String?,
        kind: ActionKind,
        roundOutcome: RoundOutcome,
        status: InstanceStatus,
    ): Outcome {
        val (cancelledRound, cancelled) = state.round.cancelOpen()
        val wasOpen = state.round.outcome == RoundOutcome.OPEN
        val steps =
            cancelledRound.steps.map {
                if (it.state == StepState.ACTIVE || it.state == StepState.PENDING) it.copy(state = StepState.SKIPPED) else it
            }
        val round = cancelledRound.copy(steps = steps, outcome = if (wasOpen) roundOutcome else state.round.outcome)
        val effects =
            cancelled +
                listOfNotNull(
                    Effect.RoundEnded(round.number, roundOutcome).takeIf { wasOpen },
                    Effect.InstanceEnded(status).takeIf { status.terminal },
                )
        return Outcome(state.copy(status = status, round = round), record(kind, state, null, actor, remark), effects)
    }
}
