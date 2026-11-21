package com.memo.workflow.engine

import com.memo.workflow.BlockedReason
import com.memo.workflow.InstanceStatus
import com.memo.workflow.definition.Assignee
import com.memo.workflow.definition.AssigneeKind
import com.memo.workflow.definition.Definition
import com.memo.workflow.definition.StepAction
import com.memo.workflow.definition.StepDef
import com.memo.workflow.definition.StepType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Steps on people the item names (ADR-0068, E07 test plan T1), in the pure engine with a fake resolver: a slot of
 * several people is a pool exactly as a role is (first decides, claims); SOD-1 is off only when the subject lets the
 * initiator take part, and SOD-2 still keeps whoever recommended off the next step.
 */
class SubjectSlotsTest {
    private val initiator = UUID(0, 1)
    private val alice = UUID(0, 2)
    private val bob = UUID(0, 3)
    private val carol = UUID(0, 4)

    private val definition =
        Definition(
            subject = "test.slots",
            start = "owner",
            steps =
                listOf(
                    StepDef(
                        id = "owner",
                        type = StepType.REVIEW,
                        assignee = Assignee(AssigneeKind.SUBJECT, slot = "owner"),
                        actions = listOf(StepAction.RECOMMEND),
                        next = "verify",
                    ),
                    StepDef(
                        id = "verify",
                        type = StepType.APPROVE,
                        assignee = Assignee(AssigneeKind.SUBJECT, slot = "verifier"),
                        actions = listOf(StepAction.APPROVE, StepAction.SEND_BACK),
                        next = Definition.END,
                    ),
                ),
        )

    private fun transition(
        slots: Map<String, List<UUID>>,
        takesPart: Boolean,
    ): Transition {
        val resolver =
            Resolver { assignee, owner ->
                if (assignee.kind == AssigneeKind.SUBJECT) {
                    Resolution(slots[assignee.slot].orEmpty(), mapOf("kind" to "subject", "slot" to assignee.slot))
                } else {
                    Resolver.NAMED.resolve(assignee, owner)
                }
            }
        val context =
            TransitionContext(EngineFixtures.ids(), { null }, maxApprovers = 4, resolver = resolver, initiatorTakesPart = takesPart)
        return Transition(context, definition)
    }

    @Test
    fun `a slot of one person gives them the task, and the step records the slot`() {
        val started = transition(mapOf("owner" to listOf(alice)), takesPart = false).start(initiator, emptyList()).accepted()
        val step =
            started.state.round.steps
                .single()
        assertThat(step.assignee).isEqualTo(alice)
        assertThat(step.needed).isNull()
        assertThat(step.resolution!!["resolved"].toString()).contains("slot=owner")
    }

    @Test
    fun `a slot of several people is a pool, as a role is, where any one claims it and the first decision completes it`() {
        val transition = transition(mapOf("owner" to listOf(alice, bob, carol), "verifier" to listOf(bob)), takesPart = false)
        val started = transition.start(initiator, emptyList()).accepted()
        val step =
            started.state.round.steps
                .single()
        assertThat(step.shared).isTrue()
        assertThat(step.needed).isEqualTo(1)
        assertThat(started.state.currentAssignees).containsExactly(alice, bob, carol)

        val claimed = transition.apply(started.state, Claim(bob, started.state.openTaskOf(bob).id)).accepted()
        assertThat(claimed.state.currentAssignees).containsExactly(bob)
        assertThat(transition.apply(claimed.state, Approve(alice, claimed.state.openTaskOf(alice).id)).refusal())
            .isEqualTo(Refusal.CLAIMED_BY_OTHER)
        val released = transition.apply(claimed.state, Release(bob, claimed.state.openTaskOf(bob).id)).accepted()
        val decided = transition.apply(released.state, Approve(carol, released.state.openTaskOf(carol).id)).accepted()
        assertThat(
            decided.state.round.steps
                .first()
                .state,
        ).isEqualTo(StepState.DONE)
        assertThat(decided.state.currentAssignees).containsExactly(bob)
    }

    @Test
    fun `without the subject's consent, the initiator is left out of a slot (SOD-1)`() {
        val started = transition(mapOf("owner" to listOf(initiator, alice)), takesPart = false).start(initiator, emptyList()).accepted()
        assertThat(started.state.currentAssignees).containsExactly(alice)
        assertThat(
            started.state.round.steps
                .single()
                .resolution!!["excluded"]
                .toString(),
        ).contains("SOD-1")
    }

    @Test
    fun `when the initiator takes part they may hold the step, and SOD-2 still keeps the recommender off the next`() {
        val transition = transition(mapOf("owner" to listOf(initiator), "verifier" to listOf(initiator, bob)), takesPart = true)
        val started = transition.start(initiator, emptyList()).accepted()
        assertThat(started.state.currentAssignees).containsExactly(initiator)

        val recommended = transition.apply(started.state, Approve(initiator, started.state.openTaskOf(initiator).id)).accepted()
        assertThat(recommended.state.currentAssignees).containsExactly(bob)
        assertThat(
            recommended.state.round.steps
                .last()
                .resolution!!["excluded"]
                .toString(),
        ).contains("SOD-2")
        val approved = transition.apply(recommended.state, Approve(bob, recommended.state.openTaskOf(bob).id)).accepted()
        assertThat(approved.state.status).isEqualTo(InstanceStatus.APPROVED)
    }

    @Test
    fun `when the initiator takes part they may be reassigned a task, and otherwise not`() {
        val slots = mapOf("owner" to listOf(alice))
        listOf(true to null, false to Refusal.INITIATOR_AS_APPROVER).forEach { (takesPart, refusal) ->
            val transition = transition(slots, takesPart)
            val started = transition.start(initiator, emptyList()).accepted()
            val reassign = Reassign(carol, started.state.openTaskOf(alice).id, initiator, "Alice is away")
            assertThat(transition.apply(started.state, reassign).refusal()).isEqualTo(refusal)
        }
    }

    @Test
    fun `an empty slot holds the step for an administrator`() {
        val started = transition(emptyMap(), takesPart = false).start(initiator, emptyList()).accepted()
        assertThat(
            started.state.openTasks
                .single()
                .blocked,
        ).isEqualTo(BlockedReason.UNRESOLVED)
    }
}
