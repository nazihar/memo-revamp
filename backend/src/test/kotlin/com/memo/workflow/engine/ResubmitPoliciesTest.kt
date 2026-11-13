package com.memo.workflow.engine

import com.memo.workflow.ActionKind
import com.memo.workflow.InstanceStatus
import com.memo.workflow.RouteEntry
import com.memo.workflow.definition.Assignee
import com.memo.workflow.definition.AssigneeKind
import com.memo.workflow.definition.Definition
import com.memo.workflow.definition.ParallelMode
import com.memo.workflow.definition.ResubmitPolicy
import com.memo.workflow.definition.StepDef
import com.memo.workflow.definition.StepType
import com.memo.workflow.engine.EngineFixtures.named
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * The resubmission policies (design E03 §4.6, test plan T4): `restart` from the start, `resume` where the item was
 * returned, and `skip-unchanged`, where approvals of the previous round on the same subject carry over as CARRY actions.
 */
class ResubmitPoliciesTest {
    private val initiator = UUID(0, 1)
    private val alice = UUID(0, 2)
    private val bob = UUID(0, 3)
    private val carol = UUID(0, 4)
    private val dave = UUID(0, 5)
    private val gm = UUID(7, 1)

    private fun routed(policy: ResubmitPolicy? = null) =
        Definition(resubmit = policy, start = "route", steps = listOf(StepDef(id = "route", type = StepType.ROUTE, next = Definition.END)))

    private fun twoSteps(policy: ResubmitPolicy?) =
        Definition(
            resubmit = policy,
            start = "first",
            steps =
                listOf(
                    StepDef(id = "first", type = StepType.APPROVE, assignee = named(alice), next = "second"),
                    StepDef(id = "second", type = StepType.APPROVE, assignee = named(bob), next = Definition.END),
                ),
        )

    private fun transition(
        definition: Definition,
        carried: Set<Carried> = emptySet(),
    ): Transition {
        val resolver =
            Resolver { assignee, owner ->
                if (assignee.positionId == gm) Resolution(listOf(alice)) else Resolver.NAMED.resolve(assignee, owner)
            }
        return Transition(
            TransitionContext(EngineFixtures.ids(), { null }, maxApprovers = 5, carried = carried, resolver = resolver),
            definition,
        )
    }

    private fun approve(
        transition: Transition,
        state: InstanceState,
        person: UUID,
    ): Outcome = transition.apply(state, Approve(person, state.openTaskOf(person).id)).accepted()

    private fun returnedBy(
        transition: Transition,
        state: InstanceState,
        person: UUID,
    ): InstanceState = transition.apply(state, Return(person, state.openTaskOf(person).id, "rework")).accepted().state

    @Test
    fun `resume starts the next round at the approver who returned it, at their place in the new route`() {
        val transition = transition(routed(ResubmitPolicy.RESUME))
        val started = transition.start(initiator, listOf(alice, bob, carol)).accepted().state
        val returned = returnedBy(transition, approve(transition, started, alice).state, bob)

        val resumed = transition.apply(returned, Resubmit(initiator, listOf(alice, bob, carol), "fixed")).accepted()
        assertThat(resumed.state.round.number).isEqualTo(2)
        assertThat(resumed.action).isEqualTo(ActionRecord(ActionKind.RESUBMIT, 2, null, initiator, "fixed"))
        assertThat(resumed.effects.first()).isEqualTo(Effect.RoundStarted(2))
        assertThat(resumed.state.currentAssignees).describedAs("steps before it are not repeated").containsExactly(bob)
        assertThat(
            resumed.state.round.steps
                .map { it.routeIndex },
        ).containsExactly(1, 2)
        assertThat(
            resumed.state.round.steps
                .map { it.state },
        ).containsExactly(StepState.ACTIVE, StepState.PENDING)

        val atCarol = approve(transition, resumed.state, bob).state
        assertThat(approve(transition, atCarol, carol).state.status).isEqualTo(InstanceStatus.APPROVED)

        val reordered = transition.apply(returned, Resubmit(initiator, listOf(carol, bob))).accepted().state
        assertThat(reordered.currentAssignees).containsExactly(bob)
        assertThat(approve(transition, reordered, bob).state.status).isEqualTo(InstanceStatus.APPROVED)

        val without = transition.apply(returned, Resubmit(initiator, listOf(dave, carol))).accepted().state
        assertThat(without.currentAssignees).describedAs("an approver no longer in the route: from its first").containsExactly(dave)
    }

    @Test
    fun `resume starts again at the definition step that returned it, restart at the start`() {
        val resume = transition(twoSteps(ResubmitPolicy.RESUME))
        val started = resume.start(initiator, emptyList()).accepted().state
        val returned = returnedBy(resume, approve(resume, started, alice).state, bob)
        val resumed = resume.apply(returned, Resubmit(initiator, emptyList())).accepted().state
        assertThat(resumed.currentAssignees).containsExactly(bob)
        assertThat(resumed.round.steps.map { it.nodeId }).containsExactly("second")

        val restart = transition(twoSteps(null))
        val again = restart.start(initiator, emptyList()).accepted().state
        val back = returnedBy(restart, approve(restart, again, alice).state, bob)
        assertThat(
            restart
                .apply(back, Resubmit(initiator, emptyList()))
                .accepted()
                .state.currentAssignees,
        ).containsExactly(alice)
    }

    @Test
    fun `a recalled item resumes from the start, since nobody returned it`() {
        val transition = transition(twoSteps(ResubmitPolicy.RESUME))
        val started = transition.start(initiator, emptyList()).accepted().state
        val recalled = transition.apply(started, Recall(initiator)).accepted().state

        assertThat(
            transition
                .apply(recalled, Resubmit(initiator, emptyList()))
                .accepted()
                .state.currentAssignees,
        ).containsExactly(alice)
    }

    @Test
    fun `an unchanged approval carries over, recorded as a CARRY by the approver`() {
        val first = transition(routed(ResubmitPolicy.SKIP_UNCHANGED))
        val started = first.start(initiator, listOf(alice, bob)).accepted().state
        val returned = returnedBy(first, approve(first, started, alice).state, bob)

        val second = transition(routed(ResubmitPolicy.SKIP_UNCHANGED), setOf(Carried("route", alice)))
        val resubmitted = second.apply(returned, Resubmit(initiator, listOf(alice, bob))).accepted()
        assertThat(resubmitted.state.currentAssignees).containsExactly(bob)
        assertThat(resubmitted.followUps).containsExactly(
            ActionRecord(ActionKind.CARRY, 2, null, alice, null, mapOf("step" to "route")),
        )
    }

    @Test
    fun `an approval later in the route carries over when the route reaches it`() {
        val transition = transition(routed(ResubmitPolicy.SKIP_UNCHANGED), setOf(Carried("route", bob)))
        val started = transition.start(initiator, listOf(alice, bob, carol)).accepted().state
        assertThat(started.currentAssignees).containsExactly(alice)
        val bobStep = started.round.steps[1]

        val next = approve(transition, started, alice)
        assertThat(next.state.currentAssignees).containsExactly(carol)
        assertThat(
            next.state.round
                .step(bobStep.id)
                .state,
        ).isEqualTo(StepState.SKIPPED)
        assertThat(next.followUps).containsExactly(ActionRecord(ActionKind.CARRY, 1, null, bob, null, mapOf("step" to "route")))
        assertThat(approve(transition, next.state, carol).state.status).isEqualTo(InstanceStatus.APPROVED)
    }

    @Test
    fun `when every approval carries over the round is approved at once`() {
        val transition = transition(routed(ResubmitPolicy.SKIP_UNCHANGED), setOf(Carried("route", alice), Carried("route", bob)))
        val approved = transition.start(initiator, listOf(alice, bob)).accepted()

        assertThat(approved.state.status).isEqualTo(InstanceStatus.APPROVED)
        assertThat(approved.followUps.map { it.actor }).containsExactly(alice, bob)
        assertThat(approved.effects).containsExactly(
            Effect.RoundStarted(1),
            Effect.RoundEnded(1, RoundOutcome.APPROVED),
            Effect.InstanceEnded(InstanceStatus.APPROVED),
        )
    }

    @Test
    fun `positions and groups in a route are asked again`() {
        val transition = transition(routed(ResubmitPolicy.SKIP_UNCHANGED), setOf(Carried("route", alice)))
        val started =
            transition
                .start(
                    initiator,
                    emptyList(),
                    entries = listOf(RouteEntry(positionId = gm), RouteEntry.person(bob)),
                ).accepted()

        assertThat(started.state.currentAssignees).containsExactly(alice)
        assertThat(started.followUps).isEmpty()
    }

    @Test
    fun `a single holder's approval of a definition step carries over, a parallel step always runs again`() {
        val definition =
            Definition(
                resubmit = ResubmitPolicy.SKIP_UNCHANGED,
                start = "review",
                steps =
                    listOf(
                        StepDef(id = "review", type = StepType.REVIEW, assignee = named(alice), next = "board"),
                        StepDef(
                            id = "board",
                            type = StepType.PARALLEL,
                            assignees = listOf(Assignee(AssigneeKind.PERSON, userId = bob)),
                            mode = ParallelMode.ALL,
                            next = Definition.END,
                        ),
                    ),
            )
        val transition = transition(definition, setOf(Carried("review", alice), Carried("board", bob)))
        val started = transition.start(initiator, emptyList()).accepted()

        assertThat(started.followUps).containsExactly(ActionRecord(ActionKind.CARRY, 1, null, alice, null, mapOf("step" to "review")))
        assertThat(started.state.currentAssignees).containsExactly(bob)
        assertThat(
            started.state.round.steps
                .map { it.nodeId },
        ).containsExactly("board")
    }
}
