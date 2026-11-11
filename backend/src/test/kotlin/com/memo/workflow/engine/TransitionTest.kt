package com.memo.workflow.engine

import com.memo.workflow.ActionKind
import com.memo.workflow.BlockedReason
import com.memo.workflow.InstanceStatus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/** Each allowed transition of design E01 §3, and each forbidden one, on the pure engine. */
class TransitionTest {
    private val initiator = person(0)
    private val first = person(1)
    private val second = person(2)
    private val third = person(3)
    private val admin = person(9)
    private val ineligible = mutableMapOf<UUID, BlockedReason>()
    private var nextId = 1_000L
    private val transition =
        Transition(TransitionContext({ person(nextId++) }, { ineligible[it] }, maxApprovers = 5))

    @Test
    fun `submit opens round 1 with a task for the first approver only`() {
        val outcome = accepted(transition.start(initiator, listOf(first, second), " please review "))

        assertThat(outcome.state.status).isEqualTo(InstanceStatus.RUNNING)
        assertThat(outcome.state.round.number).isEqualTo(1)
        assertThat(outcome.state.currentAssignees).containsExactly(first)
        assertThat(
            outcome.state.round.steps
                .map { it.state },
        ).containsExactly(StepState.ACTIVE, StepState.PENDING)
        assertThat(
            outcome.state.round.steps
                .map { it.ordinal },
        ).containsExactly(1, 2)
        assertThat(outcome.action).isEqualTo(ActionRecord(ActionKind.SUBMIT, 1, null, initiator, "please review"))
        assertThat(outcome.effects.first()).isEqualTo(Effect.RoundStarted(1))
    }

    @Test
    fun `invalid routes are refused`() {
        assertThat(transition.start(initiator, emptyList())).isEqualTo(Decision.Refused(Refusal.ROUTE_EMPTY))
        assertThat(transition.start(initiator, listOf(first, first))).isEqualTo(Decision.Refused(Refusal.ROUTE_DUPLICATE))
        assertThat(transition.start(initiator, listOf(first, initiator)))
            .isEqualTo(Decision.Refused(Refusal.INITIATOR_AS_APPROVER))
        assertThat(transition.start(initiator, (1..6).map(::person))).isEqualTo(Decision.Refused(Refusal.ROUTE_TOO_LONG))
    }

    @Test
    fun `approve moves to the next approver, and the last approval approves the instance`() {
        var state = started(first, second)

        state = accepted(transition.apply(state, Approve(first, openTask(state)))).state
        assertThat(state.currentAssignees).containsExactly(second)
        assertThat(state.status).isEqualTo(InstanceStatus.RUNNING)

        val last = accepted(transition.apply(state, Approve(second, openTask(state), "fine")))
        assertThat(last.state.status).isEqualTo(InstanceStatus.APPROVED)
        assertThat(last.state.round.outcome).isEqualTo(RoundOutcome.APPROVED)
        assertThat(last.state.openTasks).isEmpty()
        assertThat(last.effects.last()).isEqualTo(Effect.InstanceEnded(InstanceStatus.APPROVED))
    }

    @Test
    fun `only the assignee of the open task acts, and never on a closed or unknown task`() {
        val state = started(first, second)
        val task = openTask(state)

        assertThat(transition.apply(state, Approve(second, task))).isEqualTo(Decision.Refused(Refusal.NOT_ASSIGNEE))
        assertThat(transition.apply(state, Approve(initiator, task))).isEqualTo(Decision.Refused(Refusal.NOT_ASSIGNEE))
        assertThat(transition.apply(state, Approve(first, person(77)))).isEqualTo(Decision.Refused(Refusal.TASK_NOT_FOUND))

        val after = accepted(transition.apply(state, Approve(first, task))).state
        assertThat(transition.apply(after, Approve(first, task))).isEqualTo(Decision.Refused(Refusal.TASK_NOT_OPEN))
    }

    @Test
    fun `return needs a remark, ends the round and waits for the initiator`() {
        val state = started(first, second)

        assertThat(transition.apply(state, Return(first, openTask(state), "  "))).isEqualTo(Decision.Refused(Refusal.REMARK_REQUIRED))
        val outcome = accepted(transition.apply(state, Return(first, openTask(state), "fix the figures")))
        val returned = outcome.state

        assertThat(outcome.effects.filterIsInstance<Effect.InstanceEnded>()).describedAs("a return does not end the instance").isEmpty()
        assertThat(returned.status).isEqualTo(InstanceStatus.RETURNED)
        assertThat(returned.round.outcome).isEqualTo(RoundOutcome.RETURNED)
        assertThat(returned.round.steps.map { it.state }).containsExactly(StepState.DONE, StepState.SKIPPED)
        assertThat(returned.openTasks).isEmpty()
    }

    @Test
    fun `resubmit starts the next round from the first approver, and earlier approvals do not count`() {
        var state = started(first, second)
        state = accepted(transition.apply(state, Approve(first, openTask(state)))).state
        state = accepted(transition.apply(state, Return(second, openTask(state), "missing annex"))).state

        assertThat(transition.apply(state, Resubmit(first, listOf(first, second)))).isEqualTo(Decision.Refused(Refusal.NOT_INITIATOR))
        val resubmitted = accepted(transition.apply(state, Resubmit(initiator, listOf(first, second), "annex added")))

        assertThat(resubmitted.state.round.number).isEqualTo(2)
        assertThat(resubmitted.state.currentAssignees).containsExactly(first)
        assertThat(
            resubmitted.state.round.steps
                .flatMap { it.tasks }
                .none { it.outcome == ActionKind.APPROVE },
        ).isTrue()
        assertThat(resubmitted.action.round).isEqualTo(2)

        var round2 = resubmitted.state
        round2 = accepted(transition.apply(round2, Approve(first, openTask(round2)))).state
        assertThat(round2.status).isEqualTo(InstanceStatus.RUNNING)
    }

    @Test
    fun `resubmit is only possible after a return`() {
        val state = started(first)

        assertThat(transition.apply(state, Resubmit(initiator, listOf(first)))).isEqualTo(Decision.Refused(Refusal.WRONG_STATE))
    }

    @Test
    fun `reject is final`() {
        val state = started(first, second)
        val rejected = accepted(transition.apply(state, Reject(first, openTask(state), "out of policy"))).state

        assertThat(rejected.status).isEqualTo(InstanceStatus.REJECTED)
        assertThat(transition.apply(rejected, Withdraw(initiator))).isEqualTo(Decision.Refused(Refusal.WRONG_STATE))
        assertThat(transition.apply(rejected, Resubmit(initiator, listOf(first)))).isEqualTo(Decision.Refused(Refusal.WRONG_STATE))
    }

    @Test
    fun `the initiator withdraws while in review or returned, cancelling open tasks`() {
        val state = started(first, second)

        assertThat(transition.apply(state, Withdraw(first))).isEqualTo(Decision.Refused(Refusal.NOT_INITIATOR))
        val withdrawn = accepted(transition.apply(state, Withdraw(initiator, "no longer needed")))
        assertThat(withdrawn.state.status).isEqualTo(InstanceStatus.WITHDRAWN)
        assertThat(withdrawn.state.openTasks).isEmpty()
        assertThat(withdrawn.effects).containsExactly(
            Effect.TaskClosed(openTask(state), TaskState.CANCELLED, null),
            Effect.RoundEnded(1, RoundOutcome.WITHDRAWN),
            Effect.InstanceEnded(InstanceStatus.WITHDRAWN),
        )
        assertThat(
            withdrawn.state.round.steps
                .map { it.state },
        ).containsExactly(StepState.SKIPPED, StepState.SKIPPED)

        val returned = accepted(transition.apply(state, Return(first, openTask(state), "rework"))).state
        val afterReturn = accepted(transition.apply(returned, Withdraw(initiator)))
        val withdrawnAfterReturn = afterReturn.state
        assertThat(withdrawnAfterReturn.round.outcome).isEqualTo(RoundOutcome.RETURNED)
        assertThat(
            afterReturn.effects,
        ).describedAs("the round had already ended").containsExactly(Effect.InstanceEnded(InstanceStatus.WITHDRAWN))
        assertThat(transition.apply(withdrawnAfterReturn, Withdraw(initiator))).isEqualTo(Decision.Refused(Refusal.WRONG_STATE))
    }

    @Test
    fun `a task for an ineligible approver is created blocked, and only reassignment moves it on`() {
        ineligible[second] = BlockedReason.INACTIVE
        var state = started(first, second)
        state = accepted(transition.apply(state, Approve(first, openTask(state)))).state
        val blocked = state.openTasks.single()

        assertThat(blocked.blocked).isEqualTo(BlockedReason.INACTIVE)
        assertThat(transition.apply(state, Approve(second, blocked.id))).isEqualTo(Decision.Refused(Refusal.TASK_BLOCKED))

        val reassigned = accepted(transition.apply(state, Reassign(admin, blocked.id, third, "on leave")))
        assertThat(reassigned.state.currentAssignees).containsExactly(third)
        assertThat(
            reassigned.state.openTasks
                .single()
                .blocked,
        ).isNull()
        assertThat(reassigned.effects.first()).isEqualTo(Effect.TaskClosed(blocked.id, TaskState.REASSIGNED, null))
        assertThat(reassigned.action.kind).isEqualTo(ActionKind.REASSIGN)
        assertThat(transition.apply(reassigned.state, Approve(second, blocked.id))).isEqualTo(Decision.Refused(Refusal.TASK_NOT_OPEN))
    }

    @Test
    fun `reassignment keeps one approval per person per round and needs a reason`() {
        ineligible[person(8)] = BlockedReason.NO_CLEARANCE
        val state = started(first, second)
        val task = openTask(state)

        assertThat(transition.apply(state, Reassign(admin, task, first, "x"))).isEqualTo(Decision.Refused(Refusal.SAME_ASSIGNEE))
        assertThat(transition.apply(state, Reassign(admin, task, second, "x"))).isEqualTo(Decision.Refused(Refusal.ASSIGNEE_IN_ROUND))
        assertThat(transition.apply(state, Reassign(admin, task, initiator, "x")))
            .isEqualTo(Decision.Refused(Refusal.INITIATOR_AS_APPROVER))
        assertThat(transition.apply(state, Reassign(admin, task, person(8), "x")))
            .isEqualTo(Decision.Refused(Refusal.ASSIGNEE_NOT_ELIGIBLE))
        assertThat(transition.apply(state, Reassign(admin, task, third, null))).isEqualTo(Decision.Refused(Refusal.REMARK_REQUIRED))
    }

    @Test
    fun `remarks longer than the limit are refused and blank remarks are not stored`() {
        val state = started(first)

        assertThat(transition.apply(state, Approve(first, openTask(state), "x".repeat(Remarks.MAX_LENGTH + 1))))
            .isEqualTo(Decision.Refused(Refusal.REMARK_TOO_LONG))
        assertThat(transition.apply(state, Approve(first, openTask(state), "x".repeat(Remarks.MAX_LENGTH))))
            .isInstanceOf(Decision.Accepted::class.java)
        assertThat(accepted(transition.apply(state, Approve(first, openTask(state), "   "))).action.remark).isNull()
    }

    private fun started(vararg route: UUID): InstanceState = accepted(transition.start(initiator, route.toList())).state

    private fun openTask(state: InstanceState): UUID = state.openTasks.single().id

    private fun accepted(decision: Decision): Outcome {
        assertThat(decision).isInstanceOf(Decision.Accepted::class.java)
        return (decision as Decision.Accepted).outcome
    }

    private fun person(n: Long): UUID = UUID(0, n)

    private fun person(n: Int): UUID = person(n.toLong())
}
