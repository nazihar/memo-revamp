package com.memo.workflow.engine

import com.memo.workflow.BlockedReason
import com.memo.workflow.GroupMode
import com.memo.workflow.InstanceStatus
import com.memo.workflow.RouteEntry
import com.memo.workflow.RouteGroup
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Initiator routes with positions and groups (design E04 §4.7, test plan T7): a position goes to its holder when the
 * entry is reached; a group of people and positions decides together (all, or any one); a vacant position waits for an
 * administrator; malformed or duplicated entries and the initiator are refused. The resolver is a fake.
 */
class RouteEntriesTest {
    private val initiator = UUID(0, 1)
    private val alice = UUID(0, 2)
    private val bob = UUID(0, 3)
    private val carol = UUID(0, 4)
    private val gm = UUID(7, 1)
    private val cfo = UUID(7, 2)

    private fun transition(holders: Map<UUID, UUID> = mapOf(gm to bob, cfo to carol)): Transition {
        var next = 100L
        val resolver =
            Resolver { assignee, owner ->
                assignee.positionId?.let { Resolution(listOfNotNull(holders[it]), mapOf("positionId" to "$it")) }
                    ?: Resolver.NAMED.resolve(assignee, owner)
            }
        return Transition(TransitionContext({ UUID(1, next++) }, { null }, maxApprovers = 4, resolver = resolver))
    }

    private fun Decision.outcome(): Outcome = (this as Decision.Accepted).outcome

    private fun Outcome.taskOf(person: UUID): UUID = state.openTasks.first { it.assignee == person }.id

    private fun group(
        mode: GroupMode,
        vararg members: RouteEntry,
    ) = RouteEntry(group = RouteGroup(mode, members.toList()))

    @Test
    fun `a position entry goes to its holder when it is reached, after the people before it`() {
        val transition = transition()
        val route = listOf(RouteEntry.person(alice), RouteEntry(positionId = gm))
        val started = transition.start(initiator, emptyList(), entries = route).outcome()
        assertThat(started.state.currentAssignees).containsExactly(alice)
        assertThat(
            started.state.round.steps
                .last()
                .state,
        ).isEqualTo(StepState.PENDING)

        val next = transition.apply(started.state, Approve(alice, started.taskOf(alice))).outcome()
        val step =
            next.state.round.steps
                .last()
        assertThat(step.id).isEqualTo(
            started.state.round.steps
                .last()
                .id,
        )
        assertThat(step.tasks.map { it.assignee }).containsExactly(bob)
        assertThat(step.resolution).containsKey("resolved")
        val done = transition.apply(next.state, Approve(bob, next.taskOf(bob))).outcome()
        assertThat(done.state.status).isEqualTo(InstanceStatus.APPROVED)
    }

    @Test
    fun `a group decides together, all of them or any one`() {
        val transition = transition()
        val all =
            transition
                .start(
                    initiator,
                    emptyList(),
                    entries = listOf(group(GroupMode.ALL, RouteEntry.person(alice), RouteEntry(positionId = cfo))),
                ).outcome()
        assertThat(all.state.currentAssignees).containsExactlyInAnyOrder(alice, carol)
        val one = transition.apply(all.state, Approve(alice, all.taskOf(alice))).outcome()
        assertThat(one.state.status).isEqualTo(InstanceStatus.RUNNING)
        assertThat(
            transition
                .apply(one.state, Approve(carol, one.taskOf(carol)))
                .outcome()
                .state.status,
        ).isEqualTo(InstanceStatus.APPROVED)

        val any =
            transition
                .start(
                    initiator,
                    emptyList(),
                    entries = listOf(group(GroupMode.ANY, RouteEntry.person(alice), RouteEntry(positionId = cfo))),
                ).outcome()
        assertThat(
            transition
                .apply(any.state, Approve(carol, any.taskOf(carol)))
                .outcome()
                .state.status,
        ).isEqualTo(InstanceStatus.APPROVED)
    }

    @Test
    fun `a vacant position waits for an administrator`() {
        val started = transition(emptyMap()).start(initiator, emptyList(), entries = listOf(RouteEntry(positionId = gm))).outcome()
        assertThat(
            started.state.round.steps
                .single()
                .tasks
                .single()
                .blocked,
        ).isEqualTo(BlockedReason.UNRESOLVED)
    }

    @Test
    fun `malformed or duplicated entries and the initiator are refused`() {
        val transition = transition()

        fun refused(route: List<RouteEntry>) = (transition.start(initiator, emptyList(), entries = route) as Decision.Refused).reason

        assertThat(refused(listOf(RouteEntry(userId = alice, positionId = gm)))).isEqualTo(Refusal.ROUTE_INVALID)
        assertThat(refused(listOf(group(GroupMode.ALL, RouteEntry.person(alice))))).isEqualTo(Refusal.ROUTE_INVALID)
        assertThat(refused(listOf(RouteEntry(positionId = gm), RouteEntry(positionId = gm)))).isEqualTo(Refusal.ROUTE_DUPLICATE)
        assertThat(refused(listOf(RouteEntry.person(alice), group(GroupMode.ANY, RouteEntry.person(alice), RouteEntry.person(bob)))))
            .isEqualTo(Refusal.ROUTE_DUPLICATE)
        assertThat(
            refused(listOf(group(GroupMode.ANY, RouteEntry.person(initiator), RouteEntry.person(bob)))),
        ).isEqualTo(Refusal.INITIATOR_AS_APPROVER)
    }
}
