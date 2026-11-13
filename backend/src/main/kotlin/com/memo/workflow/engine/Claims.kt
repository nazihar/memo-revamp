package com.memo.workflow.engine

import com.memo.workflow.ActionKind
import com.memo.workflow.InstanceStatus

/**
 * Claim and release on a step shared by several candidates (design E04 §4.2, invariant 20): a candidate claims it, the
 * others' tasks stay open but cannot decide until the claimer (or an administrator) releases it. Pure, like [Transition].
 */
internal object Claims {
    fun claimRefusal(
        state: InstanceState,
        command: Claim,
    ): Refusal? {
        val step = state.findTask(command.taskId)?.first
        return when {
            step == null -> Refusal.TASK_NOT_FOUND
            step.needed == null -> Refusal.NOT_SHARED
            step.claimedBy != null -> Refusal.ALREADY_CLAIMED
            else -> null
        }
    }

    fun releaseRefusal(
        state: InstanceState,
        command: Release,
    ): Refusal? {
        val step = state.findTask(command.taskId)?.first
        return when {
            state.status != InstanceStatus.RUNNING -> Refusal.WRONG_STATE
            step == null -> Refusal.TASK_NOT_FOUND
            step.claimedBy == null -> Refusal.NOT_CLAIMED
            else -> null
        }
    }

    fun apply(
        state: InstanceState,
        command: TaskCommand,
    ): Outcome {
        val step = checkNotNull(state.findTask(command.taskId)).first
        val claim = command is Claim
        val round = state.round.replaceStep(step.id) { it.copy(claimedBy = if (claim) command.actor else null) }
        val kind = if (claim) ActionKind.CLAIM else ActionKind.RELEASE
        val details = mapOf("step" to step.nodeId, "by" to step.claimedBy?.toString())
        return Outcome(
            state.copy(round = round),
            ActionRecord(
                kind,
                state.round.number,
                command.taskId,
                command.actor,
                null,
                if (claim) mapOf("step" to step.nodeId) else details,
            ),
            emptyList(),
        )
    }
}
