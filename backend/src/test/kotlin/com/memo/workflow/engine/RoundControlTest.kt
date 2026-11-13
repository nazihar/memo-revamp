package com.memo.workflow.engine

import com.memo.workflow.ActionKind
import com.memo.workflow.InstanceStatus
import com.memo.workflow.definition.Definition
import com.memo.workflow.definition.ParallelMode
import com.memo.workflow.definition.StepDef
import com.memo.workflow.definition.StepType
import com.memo.workflow.engine.EngineFixtures.named
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Stopping a round early (design E03 §4.4, test plan T6): the initiator's recall before anyone acted, which brings the
 * item back as after a return, and an administrator's cancellation, which ends the instance.
 */
class RoundControlTest {
    private val initiator = UUID(0, 1)
    private val alice = UUID(0, 2)
    private val bob = UUID(0, 3)
    private val admin = UUID(0, 9)

    private val transition = Transition(TransitionContext(EngineFixtures.ids(), { null }, maxApprovers = 5))

    private fun started(): InstanceState = transition.start(initiator, listOf(alice, bob)).accepted().state

    @Test
    fun `the initiator recalls the item before anyone acts, and may resubmit it`() {
        val state = started()
        val task = state.openTaskOf(alice).id

        assertThat(transition.apply(state, Recall(alice)).refusal()).isEqualTo(Refusal.NOT_INITIATOR)
        assertThat(transition.apply(state, Recall(initiator, "x".repeat(Remarks.MAX_LENGTH + 1))).refusal())
            .isEqualTo(Refusal.REMARK_TOO_LONG)
        val recalled = transition.apply(state, Recall(initiator, " wrong annex ")).accepted()

        assertThat(recalled.state.status).isEqualTo(InstanceStatus.RETURNED)
        assertThat(recalled.state.round.outcome).isEqualTo(RoundOutcome.RECALLED)
        assertThat(recalled.state.openTasks).isEmpty()
        assertThat(
            recalled.state.round.steps
                .map { it.state },
        ).containsOnly(StepState.SKIPPED)
        assertThat(recalled.effects).containsExactly(
            Effect.TaskClosed(task, TaskState.CANCELLED, null),
            Effect.RoundEnded(1, RoundOutcome.RECALLED),
        )
        assertThat(recalled.action).isEqualTo(ActionRecord(ActionKind.RECALL, 1, null, initiator, "wrong annex"))

        assertThat(transition.apply(recalled.state, Recall(initiator)).refusal()).isEqualTo(Refusal.WRONG_STATE)
        val resubmitted = transition.apply(recalled.state, Resubmit(initiator, listOf(alice, bob))).accepted()
        assertThat(resubmitted.state.round.number).isEqualTo(2)
        assertThat(resubmitted.state.currentAssignees).containsExactly(alice)
    }

    @Test
    fun `once someone has acted in the round the item cannot be recalled`() {
        val state = started()
        val approved = transition.apply(state, Approve(alice, state.openTaskOf(alice).id)).accepted().state
        assertThat(transition.apply(approved, Recall(initiator)).refusal()).isEqualTo(Refusal.NOT_RECALLABLE)

        val board =
            Transition(
                TransitionContext(EngineFixtures.ids(), { null }, maxApprovers = 5),
                Definition(
                    start = "board",
                    steps =
                        listOf(
                            StepDef(
                                id = "board",
                                type = StepType.PARALLEL,
                                assignees = listOf(named(alice), named(bob)),
                                mode = ParallelMode.ALL,
                                next = Definition.END,
                            ),
                        ),
                ),
            )
        val parallel = board.start(initiator, emptyList()).accepted().state
        assertThat(board.apply(parallel, Recall(initiator)).refusal()).isNull()
        val one = board.apply(parallel, Approve(alice, parallel.openTaskOf(alice).id)).accepted().state
        assertThat(one.status).isEqualTo(InstanceStatus.RUNNING)
        assertThat(board.apply(one, Recall(initiator)).refusal()).isEqualTo(Refusal.NOT_RECALLABLE)
    }

    @Test
    fun `an administrator cancels a running item with a reason, ending it`() {
        val state = started()
        val task = state.openTaskOf(alice).id

        assertThat(transition.apply(state, Cancel(admin, " ")).refusal()).isEqualTo(Refusal.REMARK_REQUIRED)
        val cancelled = transition.apply(state, Cancel(admin, "duplicate of another memo")).accepted()

        assertThat(cancelled.state.status).isEqualTo(InstanceStatus.CANCELLED)
        assertThat(cancelled.state.round.outcome).isEqualTo(RoundOutcome.CANCELLED)
        assertThat(
            cancelled.state.round.steps
                .map { it.state },
        ).containsOnly(StepState.SKIPPED)
        assertThat(cancelled.state.openTasks).isEmpty()
        assertThat(cancelled.effects).containsExactly(
            Effect.TaskClosed(task, TaskState.CANCELLED, null),
            Effect.RoundEnded(1, RoundOutcome.CANCELLED),
            Effect.InstanceEnded(InstanceStatus.CANCELLED),
        )
        assertThat(cancelled.action).isEqualTo(ActionRecord(ActionKind.CANCEL, 1, null, admin, "duplicate of another memo"))

        assertThat(transition.apply(cancelled.state, Cancel(admin, "again")).refusal()).isEqualTo(Refusal.WRONG_STATE)
        assertThat(transition.apply(cancelled.state, Approve(alice, task)).refusal()).isEqualTo(Refusal.WRONG_STATE)
    }

    @Test
    fun `a returned item can be cancelled, keeping its ended round, but a finished one cannot`() {
        val state = started()
        val atBob = transition.apply(state, Approve(alice, state.openTaskOf(alice).id)).accepted().state
        val returned = transition.apply(atBob, Return(bob, atBob.openTaskOf(bob).id, "rework")).accepted().state

        val cancelled = transition.apply(returned, Cancel(admin, "abandoned")).accepted()
        assertThat(cancelled.state.status).isEqualTo(InstanceStatus.CANCELLED)
        assertThat(cancelled.state.round.outcome).isEqualTo(RoundOutcome.RETURNED)
        assertThat(
            cancelled.state.round.steps
                .map { it.state },
        ).containsExactly(StepState.DONE, StepState.DONE)
        assertThat(cancelled.effects).containsExactly(Effect.InstanceEnded(InstanceStatus.CANCELLED))

        val approved = transition.apply(atBob, Approve(bob, atBob.openTaskOf(bob).id)).accepted().state
        assertThat(transition.apply(approved, Cancel(admin, "too late")).refusal()).isEqualTo(Refusal.WRONG_STATE)
        val withdrawn = transition.apply(state, Withdraw(initiator)).accepted().state
        assertThat(transition.apply(withdrawn, Cancel(admin, "too late")).refusal()).isEqualTo(Refusal.WRONG_STATE)
    }
}
