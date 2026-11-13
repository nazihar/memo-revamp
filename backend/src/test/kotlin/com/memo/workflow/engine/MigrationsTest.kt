package com.memo.workflow.engine

import com.memo.workflow.ActionKind
import com.memo.workflow.InstanceStatus
import com.memo.workflow.definition.Assignee
import com.memo.workflow.definition.AssigneeKind
import com.memo.workflow.definition.Definition
import com.memo.workflow.definition.DefinitionCompiler
import com.memo.workflow.definition.ParallelMode
import com.memo.workflow.definition.StepDef
import com.memo.workflow.definition.StepType
import com.memo.workflow.engine.EngineFixtures.named
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Migrating an instance to a newer version of its definition (design E03 §4.7, invariant 13): allowed while it runs or
 * waits for resubmission and every step still to be done exists in the target with the same type. Open tasks stay;
 * only the history records the move.
 */
class MigrationsTest {
    private val initiator = UUID(0, 1)
    private val alice = UUID(0, 2)
    private val bob = UUID(0, 3)
    private val carol = UUID(0, 4)
    private val dave = UUID(0, 5)
    private val admin = UUID(0, 9)

    private val review = StepDef(id = "review", type = StepType.REVIEW, assignee = named(alice), next = "approve")
    private val approve = StepDef(id = "approve", type = StepType.APPROVE, assignee = named(bob), next = "board")
    private val board =
        StepDef(
            id = "board",
            type = StepType.PARALLEL,
            assignees = listOf(named(carol), named(dave)),
            mode = ParallelMode.ALL,
            next = Definition.END,
        )
    private val current = Definition(start = "review", steps = listOf(review, approve, board))

    private val transition = Transition(TransitionContext(EngineFixtures.ids(), { null }, maxApprovers = 5), current)

    private fun target(vararg steps: StepDef) = Definition(start = steps.first().id, steps = steps.toList())

    private fun migrate(
        state: InstanceState,
        target: Definition,
        using: Transition = transition,
    ): Decision = using.apply(state, Migrate(admin, target, 1, 2))

    private fun approveNext(state: InstanceState): InstanceState {
        val task = state.openTasks.first()
        return transition.apply(state, Approve(task.assignee!!, task.id)).accepted().state
    }

    @Test
    fun `an instance moves to a newer version that keeps its open step, and only the history changes`() {
        val state = transition.start(initiator, emptyList()).accepted().state

        val migrated = migrate(state, target(review.copy(name = "Review v2"), approve.copy(next = Definition.END))).accepted()
        assertThat(migrated.state).isEqualTo(state)
        assertThat(migrated.effects).isEmpty()
        assertThat(migrated.action).isEqualTo(ActionRecord(ActionKind.MIGRATE, 1, null, admin, null, mapOf("from" to 1, "to" to 2)))
    }

    @Test
    fun `a version without the open step, or with its type changed, is refused`() {
        val atReview = transition.start(initiator, emptyList()).accepted().state
        assertThat(migrate(atReview, target(approve, board)).refusal()).isEqualTo(Refusal.STEP_MISSING)
        assertThat(migrate(atReview, target(review.copy(type = StepType.APPROVE))).refusal()).isEqualTo(Refusal.STEP_CHANGED)
        assertThat(migrate(atReview, target(review)).refusal()).describedAs("steps not reached yet follow the target").isNull()

        val atApprove = approveNext(atReview)
        assertThat(migrate(atApprove, target(approve)).refusal()).describedAs("steps already done do not matter").isNull()
        assertThat(migrate(atApprove, target(approve.copy(type = StepType.REVIEW))).refusal()).isEqualTo(Refusal.STEP_CHANGED)

        val atBoard = approveNext(atApprove)
        assertThat(migrate(atBoard, target(board)).refusal()).isNull()
        assertThat(migrate(atBoard, target(board.copy(type = StepType.APPROVE))).refusal()).isEqualTo(Refusal.STEP_CHANGED)
    }

    @Test
    fun `route entries, waiting ones included, must stay a route`() {
        val routed = Transition(TransitionContext(EngineFixtures.ids(), { null }, maxApprovers = 5))
        val state = routed.start(initiator, listOf(alice, bob)).accepted().state

        assertThat(migrate(state, DefinitionCompiler.SEQUENTIAL, routed).refusal()).isNull()
        val asApproval = target(StepDef(id = "route", type = StepType.APPROVE, assignee = named(alice), next = Definition.END))
        assertThat(migrate(state, asApproval, routed).refusal()).isEqualTo(Refusal.STEP_CHANGED)
    }

    @Test
    fun `a delegation-of-authority step must stay one`() {
        val doa = StepDef(id = "doa", type = StepType.DOA, next = Definition.END)
        val match = DoaMatch(listOf(DoaGroup(1, listOf(Assignee(AssigneeKind.PERSON, userId = alice)))), emptyMap())
        val authority =
            Transition(TransitionContext(EngineFixtures.ids(), { null }, maxApprovers = 5, doa = { match }), target(doa))
        val state = authority.start(initiator, emptyList()).accepted().state

        assertThat(migrate(state, target(doa), authority).refusal()).isNull()
        assertThat(migrate(state, target(doa.copy(type = StepType.PARALLEL, mode = ParallelMode.ALL)), authority).refusal())
            .isEqualTo(Refusal.STEP_CHANGED)
    }

    @Test
    fun `a returned instance moves to any newer version, a finished one to none`() {
        val atReview = transition.start(initiator, emptyList()).accepted().state
        val returned = transition.apply(atReview, Return(alice, atReview.openTaskOf(alice).id, "rework")).accepted().state
        val unrelated = target(StepDef(id = "other", type = StepType.APPROVE, assignee = named(carol), next = Definition.END))

        assertThat(migrate(returned, unrelated).refusal()).describedAs("its next round starts on the target").isNull()

        val withdrawn = transition.apply(atReview, Withdraw(initiator)).accepted().state
        assertThat(withdrawn.status).isEqualTo(InstanceStatus.WITHDRAWN)
        assertThat(migrate(withdrawn, current).refusal()).isEqualTo(Refusal.WRONG_STATE)
        val cancelled = transition.apply(atReview, Cancel(admin, "stop")).accepted().state
        assertThat(migrate(cancelled, current).refusal()).isEqualTo(Refusal.WRONG_STATE)
    }
}
