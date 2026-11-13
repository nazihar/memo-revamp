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
 * Claim and release (design E04 §4.2, invariant 20): a candidate of a step offered to several people claims it, and
 * only the claimer's task decides it until the claimer or an administrator releases it.
 */
class ClaimsTest {
    private val initiator = UUID(0, 1)
    private val alice = UUID(0, 2)
    private val bob = UUID(0, 3)
    private val carol = UUID(0, 4)
    private val admin = UUID(0, 9)

    private fun transition(step: StepDef) =
        Transition(TransitionContext(EngineFixtures.ids(), { null }, maxApprovers = 5), Definition(start = step.id, steps = listOf(step)))

    private val desk =
        transition(
            StepDef(
                id = "desk",
                type = StepType.APPROVE,
                assignees = listOf(named(alice), named(bob), named(carol)),
                next = Definition.END,
            ),
        )

    private val started = desk.start(initiator, emptyList()).accepted().state

    @Test
    fun `a candidate claims a shared step, and only the claimer decides it until it is released`() {
        val bobs = started.openTaskOf(bob).id

        val claim = desk.apply(started, Claim(bob, bobs)).accepted()
        assertThat(claim.action).isEqualTo(ActionRecord(ActionKind.CLAIM, 1, bobs, bob, null, mapOf("step" to "desk")))
        assertThat(claim.effects).isEmpty()
        assertThat(
            claim.state.round.steps
                .single()
                .claimedBy,
        ).isEqualTo(bob)
        assertThat(claim.state.openTasks).describedAs("the others' tasks stay open").hasSize(3)
        assertThat(claim.state.currentAssignees).containsExactly(bob)

        val claimed = claim.state
        assertThat(desk.apply(claimed, Approve(alice, claimed.openTaskOf(alice).id)).refusal()).isEqualTo(Refusal.CLAIMED_BY_OTHER)
        assertThat(desk.apply(claimed, Claim(alice, claimed.openTaskOf(alice).id)).refusal()).isEqualTo(Refusal.CLAIMED_BY_OTHER)
        assertThat(desk.apply(claimed, Claim(bob, bobs)).refusal()).isEqualTo(Refusal.ALREADY_CLAIMED)

        val release = desk.apply(claimed, Release(bob, bobs)).accepted()
        assertThat(release.action).isEqualTo(
            ActionRecord(ActionKind.RELEASE, 1, bobs, bob, null, mapOf("step" to "desk", "by" to bob.toString())),
        )
        assertThat(release.effects).isEmpty()
        assertThat(
            release.state.round.steps
                .single()
                .claimedBy,
        ).isNull()
        assertThat(release.state.currentAssignees).containsExactlyInAnyOrder(alice, bob, carol)
        assertThat(desk.apply(release.state, Release(bob, bobs)).refusal()).isEqualTo(Refusal.NOT_CLAIMED)

        val decided = desk.apply(claimed, Approve(bob, bobs)).accepted()
        assertThat(decided.state.status).isEqualTo(InstanceStatus.APPROVED)
    }

    @Test
    fun `only the claimer or an administrator releases a claim`() {
        val bobs = started.openTaskOf(bob).id
        val claimed = desk.apply(started, Claim(bob, bobs)).accepted().state

        assertThat(desk.apply(claimed, Release(alice, claimed.openTaskOf(alice).id)).refusal()).isEqualTo(Refusal.CLAIMED_BY_OTHER)
        assertThat(
            desk.apply(claimed, Release(admin, bobs)).refusal(),
        ).describedAs("an administrator says so").isEqualTo(Refusal.NOT_ASSIGNEE)

        val released = desk.apply(claimed, Release(admin, bobs, byAdministrator = true)).accepted()
        assertThat(released.action.actor).isEqualTo(admin)
        assertThat(released.action.details).isEqualTo(mapOf("step" to "desk", "by" to bob.toString()))
        assertThat(
            released.state.round.steps
                .single()
                .claimedBy,
        ).isNull()

        assertThat(desk.apply(started, Release(admin, bobs, byAdministrator = true)).refusal()).isEqualTo(Refusal.NOT_CLAIMED)
        assertThat(desk.apply(claimed, Release(admin, UUID(8, 8), byAdministrator = true)).refusal()).isEqualTo(Refusal.TASK_NOT_FOUND)
        val withdrawn = desk.apply(claimed, Withdraw(initiator)).accepted().state
        assertThat(desk.apply(withdrawn, Release(admin, bobs, byAdministrator = true)).refusal()).isEqualTo(Refusal.WRONG_STATE)
    }

    @Test
    fun `only a step offered to several candidates can be claimed, by one of them`() {
        assertThat(desk.apply(started, Claim(admin, started.openTaskOf(bob).id)).refusal()).isEqualTo(Refusal.NOT_ASSIGNEE)
        assertThat(desk.apply(started, Claim(bob, UUID(8, 8))).refusal()).isEqualTo(Refusal.TASK_NOT_FOUND)
        val withdrawn = desk.apply(started, Withdraw(initiator)).accepted().state
        assertThat(desk.apply(withdrawn, Claim(bob, started.openTaskOf(bob).id)).refusal()).isEqualTo(Refusal.WRONG_STATE)

        val single = transition(StepDef(id = "one", type = StepType.APPROVE, assignee = named(alice), next = Definition.END))
        val alone = single.start(initiator, emptyList()).accepted().state
        assertThat(single.apply(alone, Claim(alice, alone.openTaskOf(alice).id)).refusal()).isEqualTo(Refusal.NOT_SHARED)

        val board =
            transition(
                StepDef(
                    id = "board",
                    type = StepType.PARALLEL,
                    assignees = listOf(named(alice), named(bob)),
                    mode = ParallelMode.ANY,
                    next = Definition.END,
                ),
            )
        val parallel = board.start(initiator, emptyList()).accepted().state
        assertThat(board.apply(parallel, Claim(alice, parallel.openTaskOf(alice).id)).refusal()).isEqualTo(Refusal.NOT_SHARED)
    }
}
