package com.memo.workflow.engine

import com.memo.workflow.ActionKind
import com.memo.workflow.BlockedReason
import com.memo.workflow.InstanceStatus
import com.memo.workflow.RouteEntry
import com.memo.workflow.definition.Assignee
import com.memo.workflow.definition.AssigneeKind
import com.memo.workflow.definition.Definition
import com.memo.workflow.definition.StepDef
import com.memo.workflow.definition.StepType
import com.memo.workflow.engine.EngineFixtures.ALL_ACTIONS
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Staff changes while an item is in flight (design E04 §4.6, test plan T8): a deactivated holder's task waits blocked
 * for an administrator, and a step resolved from a position whose holder changed resolves again. Both are recorded as
 * reassignments by whoever changed the organisation. Also the substitution applied to an open task (§4.5).
 */
class StaffChangesTest {
    private val initiator = UUID(0, 1)
    private val alice = UUID(0, 2)
    private val bob = UUID(0, 3)
    private val carol = UUID(0, 4)
    private val dave = UUID(0, 5)
    private val admin = UUID(0, 9)
    private val gm = UUID(7, 1)

    /** Who holds the GM position now; tests change it as the organisation would. */
    private val holders = mutableMapOf(gm to listOf(alice))

    private val resolver =
        Resolver { assignee, owner ->
            assignee.positionId?.let { Resolution(holders[it].orEmpty(), mapOf("positionId" to "$it")) }
                ?: Resolver.NAMED.resolve(assignee, owner)
        }

    private val definition =
        Definition(
            start = "gm",
            steps =
                listOf(
                    StepDef(
                        id = "gm",
                        type = StepType.APPROVE,
                        name = "GM",
                        assignee = Assignee(AssigneeKind.POSITION, positionId = gm),
                        actions = ALL_ACTIONS,
                        next = "cfo",
                    ),
                    StepDef(
                        id = "cfo",
                        type = StepType.APPROVE,
                        assignee = Assignee(AssigneeKind.PERSON, userId = carol),
                        next = Definition.END,
                    ),
                ),
        )

    private fun transition(definition: Definition = this.definition) =
        Transition(TransitionContext(EngineFixtures.ids(), { null }, maxApprovers = 5, resolver = resolver), definition)

    private val transition = transition()

    @Test
    fun `a deactivated holder's task is replaced by one blocked for an administrator`() {
        val state = transition.start(initiator, emptyList()).accepted().state
        val task = state.openTaskOf(alice)
        val step =
            state.round.steps
                .single()

        val blocked = transition.apply(state, Block(admin, task.id)).accepted()
        val replacement = blocked.state.openTasks.single()
        assertThat(replacement.id).isNotEqualTo(task.id)
        assertThat(replacement).isEqualTo(task.copy(id = replacement.id, blocked = BlockedReason.INACTIVE))
        assertThat(
            blocked.state
                .findTask(task.id)!!
                .second.state,
        ).isEqualTo(TaskState.REASSIGNED)
        assertThat(blocked.effects).containsExactly(
            Effect.TaskClosed(task.id, TaskState.REASSIGNED, null),
            Effect.TaskCreated(step.id, replacement),
        )
        assertThat(blocked.action).isEqualTo(
            ActionRecord(ActionKind.REASSIGN, 1, task.id, admin, null, mapOf("reason" to StaffChanges.INACTIVE)),
        )
        assertThat(transition.apply(blocked.state, Approve(alice, replacement.id)).refusal()).isEqualTo(Refusal.TASK_BLOCKED)

        assertThat(transition.apply(blocked.state, Block(admin, replacement.id)).refusal()).isEqualTo(Refusal.ALREADY_BLOCKED)
        assertThat(transition.apply(blocked.state, Block(admin, task.id)).refusal()).isEqualTo(Refusal.TASK_NOT_OPEN)
        assertThat(transition.apply(blocked.state, Block(admin, UUID(8, 8))).refusal()).isEqualTo(Refusal.TASK_NOT_FOUND)

        val reassigned = transition.apply(blocked.state, Reassign(admin, replacement.id, bob, "acting GM")).accepted().state
        assertThat(reassigned.currentAssignees).containsExactly(bob)
    }

    @Test
    fun `nothing is blocked on a task without a holder or an instance that has stopped`() {
        holders[gm] = emptyList()
        val vacant = transition.start(initiator, emptyList()).accepted().state
        val unresolved = vacant.openTasks.single()
        assertThat(transition.apply(vacant, Block(admin, unresolved.id)).refusal()).isEqualTo(Refusal.TASK_NOT_OPEN)

        holders[gm] = listOf(alice)
        val running = transition.start(initiator, emptyList()).accepted().state
        val withdrawn = transition.apply(running, Withdraw(initiator)).accepted().state
        assertThat(transition.apply(withdrawn, Block(admin, running.openTaskOf(alice).id)).refusal()).isEqualTo(Refusal.WRONG_STATE)
    }

    @Test
    fun `a step whose position changed holder resolves again to the new holder`() {
        val state = transition.start(initiator, emptyList()).accepted().state
        val old =
            state.round.steps
                .single()
        val task = state.openTaskOf(alice).id
        val referred = transition.apply(state, Refer(alice, task, listOf(dave), "views?")).accepted().state
        val opinion = referred.openTaskOf(dave).id

        holders[gm] = listOf(bob)
        val changed = transition.apply(referred, Reresolve(admin, old.id)).accepted()
        val (skipped, again) = changed.state.round.steps

        assertThat(skipped.state).isEqualTo(StepState.SKIPPED)
        assertThat(skipped.tasks.map { it.state }).containsOnly(TaskState.REASSIGNED)
        assertThat(again.nodeId).isEqualTo("gm")
        assertThat(again.tasks.map { it.assignee }).containsExactly(bob)
        assertThat(changed.effects).containsExactly(
            Effect.TaskClosed(task, TaskState.REASSIGNED, null),
            Effect.TaskClosed(opinion, TaskState.REASSIGNED, null),
            Effect.TaskCreated(again.id, again.tasks.single()),
        )
        assertThat(changed.action).isEqualTo(
            ActionRecord(
                ActionKind.REASSIGN,
                1,
                null,
                admin,
                null,
                mapOf("step" to "gm", "reason" to StaffChanges.HOLDER_CHANGED, "from" to listOf(alice.toString())),
            ),
        )
        assertThat(changed.state.currentAssignees).containsExactly(bob)
    }

    @Test
    fun `a claimed step that resolves again is no longer claimed`() {
        holders[gm] = listOf(alice, bob)
        val state = transition.start(initiator, emptyList()).accepted().state
        val claimed = transition.apply(state, Claim(alice, state.openTaskOf(alice).id)).accepted().state

        holders[gm] = listOf(bob, dave)
        val changed =
            transition
                .apply(
                    claimed,
                    Reresolve(
                        admin,
                        claimed.round.steps
                            .single()
                            .id,
                    ),
                ).accepted()
        val again =
            changed.state.round.steps
                .last()
        assertThat(
            changed.state.round.steps[0]
                .claimedBy,
        ).isNull()
        assertThat(again.claimedBy).isNull()
        assertThat(again.tasks.map { it.assignee }).containsExactly(bob, dave)
        assertThat(changed.action.details["from"]).isEqualTo(listOf(alice.toString(), bob.toString()))
    }

    @Test
    fun `only an active step of a running instance resolved from a position or group resolves again`() {
        val state = transition.start(initiator, emptyList()).accepted().state
        val gmStep =
            state.round.steps
                .single()
        assertThat(transition.apply(state, Reresolve(admin, UUID(8, 8))).refusal()).isEqualTo(Refusal.NOTHING_TO_RESOLVE)

        val atCfo = transition.apply(state, Approve(alice, state.openTaskOf(alice).id)).accepted().state
        assertThat(transition.apply(atCfo, Reresolve(admin, gmStep.id)).refusal())
            .describedAs("a step already done")
            .isEqualTo(Refusal.NOTHING_TO_RESOLVE)

        val withdrawn = transition.apply(state, Withdraw(initiator)).accepted().state
        assertThat(transition.apply(withdrawn, Reresolve(admin, gmStep.id)).refusal()).isEqualTo(Refusal.NOTHING_TO_RESOLVE)

        val routed =
            transition(Definition(start = "route", steps = listOf(StepDef(id = "route", type = StepType.ROUTE, next = Definition.END))))
        val named = routed.start(initiator, listOf(alice)).accepted().state
        assertThat(
            routed
                .apply(
                    named,
                    Reresolve(
                        admin,
                        named.round.steps
                            .single()
                            .id,
                    ),
                ).refusal(),
        ).describedAs("a route entry naming a person stays theirs").isEqualTo(Refusal.NOTHING_TO_RESOLVE)
    }

    @Test
    fun `a route entry naming a position resolves again, and the rest of the route still follows once`() {
        val routed =
            transition(Definition(start = "route", steps = listOf(StepDef(id = "route", type = StepType.ROUTE, next = Definition.END))))
        val state =
            routed
                .start(
                    initiator,
                    emptyList(),
                    entries = listOf(RouteEntry(positionId = gm), RouteEntry.person(carol)),
                ).accepted()
                .state
        val entry = state.round.steps[0]

        holders[gm] = listOf(bob)
        val changed = routed.apply(state, Reresolve(admin, entry.id)).accepted().state
        assertThat(changed.currentAssignees).containsExactly(bob)
        assertThat(
            changed.round.steps
                .filter { it.state == StepState.PENDING }
                .map { it.assignee },
        ).describedAs("the rest of the route waits once")
            .containsExactly(carol)
        val active = changed.round.steps.single { it.state == StepState.ACTIVE }
        val waiting = changed.round.steps.single { it.state == StepState.PENDING }
        assertThat(waiting.ordinal).describedAs("the route keeps its order").isGreaterThan(active.ordinal)

        val atCarol = routed.apply(changed, Approve(bob, changed.openTaskOf(bob).id)).accepted().state
        val done = routed.apply(atCarol, Approve(carol, atCarol.openTaskOf(carol).id)).accepted().state
        assertThat(done.status).isEqualTo(InstanceStatus.APPROVED)
        assertThat(done.round.steps.map { it.state }).doesNotContain(StepState.PENDING, StepState.ACTIVE)
    }

    @Test
    fun `a substitution applied to an open task gives it to the substitute, acting for the holder`() {
        val state = transition.start(initiator, emptyList()).accepted().state
        val task = state.openTaskOf(alice).id

        val substituted = transition.apply(state, Reassign(admin, task, dave, "on leave", onBehalfOf = alice)).accepted()
        val step =
            substituted.state.round.steps
                .single()
        val replacement = substituted.state.openTaskOf(dave)
        assertThat(step.assignee).isEqualTo(alice)
        assertThat(replacement.onBehalfOf).isEqualTo(alice)
        assertThat(substituted.action).isEqualTo(ActionRecord(ActionKind.REASSIGN, 1, task, admin, "on leave"))

        val approved = transition.apply(substituted.state, Approve(dave, replacement.id)).accepted()
        assertThat(approved.action.actingFor).isEqualTo(alice)

        val plain = transition.apply(state, Reassign(admin, task, dave, "moved")).accepted().state
        assertThat(
            plain.round.steps
                .single()
                .assignee,
        ).isEqualTo(dave)
        assertThat(plain.openTaskOf(dave).onBehalfOf).isNull()
    }
}
