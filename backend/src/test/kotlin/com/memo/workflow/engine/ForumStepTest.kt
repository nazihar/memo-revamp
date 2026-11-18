package com.memo.workflow.engine

import com.memo.workflow.ActionKind
import com.memo.workflow.ForumOutcome
import com.memo.workflow.InstanceStatus
import com.memo.workflow.definition.Definition
import com.memo.workflow.definition.StepDef
import com.memo.workflow.definition.StepType
import com.memo.workflow.engine.EngineFixtures.named
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * The waiting forum step (ADR-0058): with `wait`, the walk stops at an active FORUM step with no task until the forum
 * decides; each outcome reaches its documented state; without `wait` the step passes through as before.
 */
class ForumStepTest {
    private val initiator = UUID(0, 1)
    private val reviewer = UUID(0, 2)
    private val director = UUID(0, 3)
    private val secretary = UUID(0, 9)

    private fun definition(wait: Boolean) =
        Definition(
            start = "review",
            steps =
                listOf(
                    StepDef(id = "review", type = StepType.APPROVE, assignee = named(reviewer), next = "committee"),
                    StepDef(
                        id = "committee",
                        type = StepType.ESCALATE_TO_FORUM,
                        forum = "credit-committee",
                        wait = wait,
                        next = "director",
                    ),
                    StepDef(id = "director", type = StepType.APPROVE, assignee = named(director), next = Definition.END),
                ),
        )

    private fun transition(wait: Boolean = true) =
        Transition(TransitionContext(EngineFixtures.ids(), { null }, maxApprovers = 5), definition(wait))

    /** Started and approved by the reviewer: now at the committee step. */
    private fun waiting(transition: Transition = transition()): Outcome {
        val started = transition.start(initiator, emptyList()).accepted().state
        return transition.apply(started, Approve(reviewer, started.openTaskOf(reviewer).id)).accepted()
    }

    private fun forumStep(state: InstanceState): Step = state.round.steps.single { it.kind == StepKind.FORUM }

    @Test
    fun `with wait the walk stops at an active forum step with no task, and says so`() {
        val outcome = waiting()
        val step = forumStep(outcome.state)
        assertThat(step.state).isEqualTo(StepState.ACTIVE)
        assertThat(step.tasks).isEmpty()
        assertThat(step.resolution).containsEntry("forum", "credit-committee")
        assertThat(outcome.state.status).isEqualTo(InstanceStatus.RUNNING)
        assertThat(outcome.state.openTasks).isEmpty()
        val escalated = outcome.effects.filterIsInstance<Effect.EscalateToForum>().single()
        assertThat(escalated.waitingStepId).isEqualTo(step.id)
        assertThat(outcome.followUps.map { it.kind }).containsExactly(ActionKind.ESCALATE_TO_FORUM)
    }

    @Test
    fun `without wait the step passes through to the next approver, as before`() {
        val outcome = waiting(transition(wait = false))
        assertThat(
            outcome.state.round.steps
                .none { it.kind == StepKind.FORUM },
        ).isTrue()
        assertThat(outcome.state.openTaskOf(director)).isNotNull()
        assertThat(
            outcome.effects
                .filterIsInstance<Effect.EscalateToForum>()
                .single()
                .waitingStepId,
        ).isNull()
    }

    @Test
    fun `approved, approved with conditions and noted continue the route`() {
        for (outcome in listOf(ForumOutcome.APPROVED, ForumOutcome.APPROVED_WITH_CONDITIONS, ForumOutcome.NOTED)) {
            val transition = transition()
            val state = waiting(transition).state
            val decided = transition.apply(state, ForumDecided(secretary, forumStep(state).id, outcome, "Agreed")).accepted()
            assertThat(decided.action.kind).isEqualTo(ActionKind.FORUM_DECIDED)
            assertThat(decided.action.details).containsEntry("outcome", outcome.name).containsEntry("forum", "credit-committee")
            assertThat(forumStep(decided.state).state).isEqualTo(StepState.DONE)
            assertThat(decided.state.openTaskOf(director)).isNotNull()
            assertThat(decided.state.status).isEqualTo(InstanceStatus.RUNNING)
        }
    }

    @Test
    fun `rejected ends the item rejected, returned and declined send it back to its initiator`() {
        val transition = transition()
        val state = waiting(transition).state
        val step = forumStep(state).id
        val rejected = transition.apply(state, ForumDecided(secretary, step, ForumOutcome.REJECTED, "Outside appetite")).accepted()
        assertThat(rejected.state.status).isEqualTo(InstanceStatus.REJECTED)
        assertThat(rejected.effects).contains(Effect.InstanceEnded(InstanceStatus.REJECTED))

        val returned = transition.apply(state, ForumDecided(secretary, step, ForumOutcome.RETURNED, "Add the valuation")).accepted()
        assertThat(returned.state.status).isEqualTo(InstanceStatus.RETURNED)
        assertThat(returned.state.round.outcome).isEqualTo(RoundOutcome.RETURNED)

        val declined = transition.apply(state, ForumDeclined(secretary, step, "Not for this committee")).accepted()
        assertThat(declined.action.kind).isEqualTo(ActionKind.FORUM_DECLINED)
        assertThat(declined.state.status).isEqualTo(InstanceStatus.RETURNED)
    }

    @Test
    fun `a forum command needs a waiting forum step, and declining needs a reason`() {
        val transition = transition()
        val state = waiting(transition).state
        val step = forumStep(state).id
        assertThat(
            transition.apply(state, ForumDecided(secretary, UUID(9, 9), ForumOutcome.APPROVED)).refusal(),
        ).isEqualTo(Refusal.STEP_MISSING)
        assertThat(transition.apply(state, ForumDeclined(secretary, step, " ")).refusal()).isEqualTo(Refusal.REMARK_REQUIRED)
        val decided = transition.apply(state, ForumDecided(secretary, step, ForumOutcome.APPROVED)).accepted().state
        assertThat(transition.apply(decided, ForumDecided(secretary, step, ForumOutcome.APPROVED)).refusal()).isEqualTo(Refusal.WRONG_STATE)
        // Before the walk reached it, the reviewer's step is no forum step.
        val started = transition.start(initiator, emptyList()).accepted().state
        val review =
            started.round.steps
                .single()
                .id
        assertThat(
            transition.apply(started, ForumDecided(secretary, review, ForumOutcome.APPROVED)).refusal(),
        ).isEqualTo(Refusal.WRONG_STATE)
    }

    @Test
    fun `the initiator withdraws an item waiting for the forum, and the step is skipped`() {
        val transition = transition()
        val state = waiting(transition).state
        val withdrawn = transition.apply(state, Withdraw(initiator, "No longer needed")).accepted()
        assertThat(withdrawn.state.status).isEqualTo(InstanceStatus.WITHDRAWN)
        assertThat(forumStep(withdrawn.state).state).isEqualTo(StepState.SKIPPED)
    }

    @Test
    fun `no task command can complete a waiting step`() {
        val transition = transition()
        val state = waiting(transition).state
        // There is no task to act on: every task command is refused.
        assertThat(transition.apply(state, Approve(secretary, UUID(7, 7))).refusal()).isEqualTo(Refusal.TASK_NOT_FOUND)
        assertThat(transition.apply(state, Reject(reviewer, UUID(7, 7), "x")).refusal()).isEqualTo(Refusal.TASK_NOT_FOUND)
    }
}
