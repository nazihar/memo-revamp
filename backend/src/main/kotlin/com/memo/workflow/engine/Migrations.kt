package com.memo.workflow.engine

import com.memo.workflow.ActionKind
import com.memo.workflow.InstanceStatus
import com.memo.workflow.definition.Definition
import com.memo.workflow.definition.StepType

/**
 * Moving a running instance to a newer version of its definition (design E03 §4.7, invariant 13). Allowed while the
 * instance runs or waits for resubmission, when every step of the round that is still to be done exists in the target
 * with the same type. Open tasks stay; the path after them follows the target. Pure, like [Transition].
 */
internal object Migrations {
    /** Why [state] cannot move to [target], or null when it can. */
    fun refusal(
        state: InstanceState,
        target: Definition,
    ): Refusal? {
        // A returned instance has nothing open: its next round starts on the target.
        val open =
            state.round.steps.filter {
                state.status == InstanceStatus.RUNNING && (it.state == StepState.ACTIVE || it.state == StepState.PENDING)
            }
        val nodes = open.map { target.stepsById[it.nodeId] }
        return when {
            state.status != InstanceStatus.RUNNING && state.status != InstanceStatus.RETURNED -> Refusal.WRONG_STATE
            nodes.any { it == null } -> Refusal.STEP_MISSING
            open.zip(nodes).any { (step, node) -> node?.type != typeOf(step) } -> Refusal.STEP_CHANGED
            else -> null
        }
    }

    /** The instance stays as it is; the history records the move from one version to the other. */
    fun apply(
        state: InstanceState,
        command: Migrate,
    ): Outcome =
        Outcome(
            state,
            ActionRecord(
                ActionKind.MIGRATE,
                state.round.number,
                null,
                command.actor,
                null,
                mapOf("from" to command.from, "to" to command.to),
            ),
            emptyList(),
        )

    /** The definition step type an activated step came from: a route entry from a `route` step, the rest by kind. */
    private fun typeOf(step: Step): StepType =
        when {
            step.routeIndex != null -> StepType.ROUTE
            step.doaGroup != null -> StepType.DOA
            step.kind == StepKind.REVIEW -> StepType.REVIEW
            step.kind == StepKind.PARALLEL -> StepType.PARALLEL
            else -> StepType.APPROVE
        }
}
