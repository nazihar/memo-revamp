package com.memo.workflow.engine

import com.memo.workflow.BlockedReason
import com.memo.workflow.InstanceStatus
import com.memo.workflow.definition.Assignee
import com.memo.workflow.definition.AssigneeKind
import com.memo.workflow.definition.Definition
import com.memo.workflow.definition.StepDef
import com.memo.workflow.definition.StepType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Steps that go to whoever their assignee resolves to (design E04 §4.1, test plan T2): one holder, several candidates
 * of whom the first decides, nobody (held by a blocked task until an administrator assigns it, never skipped), the
 * fallback, and the initiator left out. The resolver is a fake; the organisation's is `ResolversIT`.
 */
class ResolutionTest {
    private val initiator = UUID(0, 1)
    private val alice = UUID(0, 2)
    private val bob = UUID(0, 3)
    private val carol = UUID(0, 4)
    private val admin = UUID(0, 9)
    private val position = UUID(7, 7)

    private fun definition(fallback: Assignee? = null) =
        Definition(
            start = "gm",
            steps =
                listOf(
                    StepDef(
                        id = "gm",
                        type = StepType.APPROVE,
                        name = "GM",
                        assignee = Assignee(AssigneeKind.POSITION, positionId = position),
                        fallback = fallback,
                        next = Definition.END,
                    ),
                ),
        )

    private fun transition(
        people: List<UUID>,
        fallback: Assignee? = null,
    ): Transition {
        var next = 100L
        val resolver =
            Resolver { assignee, owner ->
                if (assignee.kind ==
                    AssigneeKind.POSITION
                ) {
                    Resolution(people, mapOf("positionId" to position.toString()))
                } else {
                    Resolver.NAMED.resolve(assignee, owner)
                }
            }
        return Transition(TransitionContext({ UUID(1, next++) }, { null }, maxApprovers = 4, resolver = resolver), definition(fallback))
    }

    private fun started(transition: Transition): Outcome = (transition.start(initiator, emptyList()) as Decision.Accepted).outcome

    @Test
    fun `one holder gets the task, and the step records what it was resolved from`() {
        val outcome = started(transition(listOf(alice)))
        val step =
            outcome.state.round.steps
                .single()
        assertThat(step.assignee).isEqualTo(alice)
        assertThat(step.needed).isNull()
        assertThat(step.resolution).containsEntry("people", listOf(alice.toString()))
        assertThat(step.resolution!!["resolved"] as List<*>).hasSize(1)
    }

    @Test
    fun `several candidates each get the task, and the first decision completes the step and cancels the rest`() {
        val transition = transition(listOf(alice, bob, carol))
        val outcome = started(transition)
        val step =
            outcome.state.round.steps
                .single()
        assertThat(step.tasks.map { it.assignee }).containsExactly(alice, bob, carol)
        assertThat(step.needed).isEqualTo(1)
        assertThat(outcome.state.currentAssignees).containsExactlyInAnyOrder(alice, bob, carol)

        val bobsTask = step.tasks.first { it.assignee == bob }
        val decided = (transition.apply(outcome.state, Approve(bob, bobsTask.id)) as Decision.Accepted).outcome
        assertThat(decided.state.status).isEqualTo(InstanceStatus.APPROVED)
        assertThat(decided.state.openTasks).isEmpty()
    }

    @Test
    fun `nobody found holds the step with a blocked task until an administrator assigns it`() {
        val transition = transition(emptyList())
        val outcome = started(transition)
        val task = outcome.state.openTasks.single()
        assertThat(task.assignee).isNull()
        assertThat(task.blocked).isEqualTo(BlockedReason.UNRESOLVED)
        assertThat(outcome.state.status).isEqualTo(InstanceStatus.RUNNING)
        assertThat(outcome.state.currentAssignees).isEmpty()
        assertThat(transition.apply(outcome.state, Approve(alice, task.id))).isInstanceOf(Decision.Refused::class.java)

        val assigned = (transition.apply(outcome.state, Reassign(admin, task.id, alice, "acting GM")) as Decision.Accepted).outcome
        assertThat(assigned.state.currentAssignees).containsExactly(alice)
        val done =
            (
                transition.apply(
                    assigned.state,
                    Approve(
                        alice,
                        assigned.state.openTasks
                            .single()
                            .id,
                    ),
                ) as Decision.Accepted
            ).outcome
        assertThat(done.state.status).isEqualTo(InstanceStatus.APPROVED)
    }

    @Test
    fun `the fallback takes a step nobody was found for, and the initiator is never a candidate`() {
        val withFallback = started(transition(emptyList(), Assignee(AssigneeKind.PERSON, userId = carol)))
        assertThat(withFallback.state.currentAssignees).containsExactly(carol)
        assertThat(
            withFallback.state.round.steps
                .single()
                .resolution,
        ).containsEntry("fallback", true)

        val own = started(transition(listOf(initiator)))
        assertThat(
            own.state.openTasks
                .single()
                .blocked,
        ).isEqualTo(BlockedReason.UNRESOLVED)
        assertThat(
            own.state.round.steps
                .single()
                .resolution!!["excluded"] as List<*>,
        ).hasSize(1)
    }
}
