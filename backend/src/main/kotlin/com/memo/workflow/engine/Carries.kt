package com.memo.workflow.engine

import com.memo.workflow.ActionKind
import java.util.UUID

/**
 * The `skip-unchanged` resubmission policy (design E03 §4.6): approvals of the previous round on the same subject
 * snapshot carry over ([TransitionContext.carried]); each one passed is recorded as a CARRY by the person who approved.
 */
internal class Carries(
    private val carried: Set<Carried>,
) {
    fun carries(
        nodeId: String,
        person: UUID,
    ): Boolean = Carried(nodeId, person) in carried

    /**
     * The first route entry from [start] whose approver does not carry over, or null when every remaining one does; and a
     * CARRY record for each entry passed over.
     */
    fun uncarried(
        round: Round,
        nodeId: String,
        start: Int,
    ): Pair<Int?, List<ActionRecord>> {
        val passed = (start until round.route.size).takeWhile { carries(nodeId, round.route[it]) }
        val first = (start + passed.size).takeIf { it < round.route.size }
        return first to passed.map { record(nodeId, round.route[it], round.number) }
    }

    fun record(
        nodeId: String,
        person: UUID,
        round: Int,
    ): ActionRecord = automatic(ActionKind.CARRY, round, person, mapOf("step" to nodeId))

    /** Pending route steps whose approval carried over are skipped, so the round does not show them as still to come. */
    fun skip(
        round: Round,
        nodeId: String,
        records: List<ActionRecord>,
    ): Round {
        val people = records.map { it.actor }.toSet()
        return round.copy(
            steps =
                round.steps.map {
                    val passedOver = it.nodeId == nodeId && it.state == StepState.PENDING && it.assignee in people
                    if (passedOver) it.copy(state = StepState.SKIPPED) else it
                },
        )
    }
}
