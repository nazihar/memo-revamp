package com.memo.workflow.engine

import com.memo.workflow.ActionKind
import com.memo.workflow.BlockedReason
import com.memo.workflow.InstanceStatus
import com.memo.workflow.definition.Definition
import com.memo.workflow.definition.StepDef
import com.memo.workflow.definition.StepType
import com.memo.workflow.engine.EngineFixtures.ALL_ACTIONS
import com.memo.workflow.engine.EngineFixtures.named
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Sending an item back to an earlier step (design E03 §4.4) and standing aside from it (SOD-4, design E04 §4.4): which
 * steps can be sent back to, how the target runs again, and who the step goes to after a recusal.
 */
class SendBackAndRecuseTest {
    private val initiator = UUID(0, 1)
    private val alice = UUID(0, 2)
    private val bob = UUID(0, 3)
    private val carol = UUID(0, 4)
    private val dave = UUID(0, 5)
    private val admin = UUID(0, 9)

    private val twoSteps =
        Definition(
            start = "first",
            steps =
                listOf(
                    StepDef(
                        id = "first",
                        type = StepType.APPROVE,
                        name = "First",
                        assignee = named(alice),
                        actions = ALL_ACTIONS,
                        next = "second",
                    ),
                    StepDef(
                        id = "second",
                        type = StepType.APPROVE,
                        name = "Second",
                        assignee = named(bob),
                        actions = ALL_ACTIONS,
                        next = Definition.END,
                    ),
                ),
        )

    private val routed =
        Definition(
            start = "route",
            steps = listOf(StepDef(id = "route", type = StepType.ROUTE, actions = ALL_ACTIONS, next = Definition.END)),
        )

    private fun transition(
        definition: Definition,
        oncePerRound: Boolean = true,
    ) = Transition(TransitionContext(EngineFixtures.ids(), { null }, maxApprovers = 5, oncePerRound = oncePerRound), definition)

    private fun approveAll(
        transition: Transition,
        state: InstanceState,
        vararg people: UUID,
    ): InstanceState =
        people.fold(state) { current, person -> transition.apply(current, Approve(person, current.openTaskOf(person).id)).accepted().state }

    @Test
    fun `send back runs the earlier step again with its holder, and what followed it after that`() {
        val transition = transition(twoSteps)
        val started = transition.start(initiator, emptyList()).accepted().state
        val atSecond = approveAll(transition, started, alice)
        val first = atSecond.round.steps[0]
        val second = atSecond.round.steps[1]
        val task = atSecond.openTaskOf(bob).id
        val referred = transition.apply(atSecond, Refer(bob, task, listOf(carol), "views?")).accepted().state
        val opinion = referred.openTaskOf(carol).id

        assertThat(transition.apply(referred, SendBack(bob, task, first.id, null)).refusal()).isEqualTo(Refusal.REMARK_REQUIRED)
        val sent = transition.apply(referred, SendBack(bob, task, first.id, "check the figures")).accepted()
        val again =
            sent.state.round.steps
                .last()

        assertThat(again.nodeId).isEqualTo("first")
        assertThat(again.id).isNotEqualTo(first.id)
        assertThat(again.ordinal).isEqualTo(3)
        assertThat(again.state).isEqualTo(StepState.ACTIVE)
        assertThat(again.tasks.map { it.assignee }).containsExactly(alice)
        assertThat(
            sent.state.round
                .step(second.id)
                .state,
        ).isEqualTo(StepState.DONE)
        assertThat(
            sent.state
                .findTask(task)!!
                .second.outcome,
        ).isEqualTo(ActionKind.SEND_BACK)
        assertThat(sent.effects).containsExactly(
            Effect.TaskClosed(task, TaskState.COMPLETED, ActionKind.SEND_BACK),
            Effect.TaskClosed(opinion, TaskState.CANCELLED, null),
            Effect.TaskCreated(again.id, again.tasks.single()),
        )
        assertThat(sent.action).isEqualTo(
            ActionRecord(
                ActionKind.SEND_BACK,
                1,
                task,
                bob,
                "check the figures",
                mapOf("toStep" to "first", "toStepId" to first.id.toString()),
            ),
        )
        assertThat(sent.state.status).isEqualTo(InstanceStatus.RUNNING)
        assertThat(sent.state.currentAssignees).containsExactly(alice)

        val redone = approveAll(transition, sent.state, alice)
        assertThat(redone.currentAssignees).describedAs("later steps are redone when reached").containsExactly(bob)
        assertThat(approveAll(transition, redone, bob).status).isEqualTo(InstanceStatus.APPROVED)
    }

    @Test
    fun `only an earlier step of this round that was done can be sent back to`() {
        val transition = transition(routed)
        val started = transition.start(initiator, listOf(alice, bob, carol)).accepted().state
        val (aliceStep, bobStep, carolStep) = started.round.steps
        val atBob = approveAll(transition, started, alice)
        val task = atBob.openTaskOf(bob).id

        assertThat(transition.apply(atBob, SendBack(bob, task, bobStep.id, "x")).refusal()).isEqualTo(Refusal.NOT_AN_EARLIER_STEP)
        assertThat(transition.apply(atBob, SendBack(bob, task, carolStep.id, "x")).refusal()).isEqualTo(Refusal.NOT_AN_EARLIER_STEP)
        assertThat(transition.apply(atBob, SendBack(bob, task, UUID(8, 8), "x")).refusal()).isEqualTo(Refusal.NOT_AN_EARLIER_STEP)
        assertThat(transition.apply(atBob, SendBack(bob, task, aliceStep.id, "x")).refusal()).isNull()
    }

    @Test
    fun `a route entry sent back to restarts there, with the rest of the route behind it`() {
        val transition = transition(routed)
        val started = transition.start(initiator, listOf(alice, bob, carol)).accepted().state
        val aliceStep = started.round.steps[0]
        val atCarol = approveAll(transition, started, alice, bob)

        val sent = transition.apply(atCarol, SendBack(carol, atCarol.openTaskOf(carol).id, aliceStep.id, "start again")).accepted().state
        val active = sent.round.steps.filter { it.state == StepState.ACTIVE }
        val pending = sent.round.steps.filter { it.state == StepState.PENDING }
        assertThat(active.map { it.routeIndex }).containsExactly(0)
        assertThat(pending.map { it.routeIndex }).containsExactly(1, 2)
        assertThat(pending.map { it.assignee }).containsExactly(bob, carol)
        assertThat(sent.currentAssignees).containsExactly(alice)

        val atBobAgain = approveAll(transition, sent, alice)
        assertThat(atBobAgain.currentAssignees).containsExactly(bob)
        assertThat(approveAll(transition, atBobAgain, bob, carol).status).isEqualTo(InstanceStatus.APPROVED)
    }

    @Test
    fun `a shared step sent back to resolves its candidates again, including whoever decided it before`() {
        val shared =
            Definition(
                start = "desk",
                steps =
                    listOf(
                        StepDef(id = "desk", type = StepType.APPROVE, assignees = listOf(named(alice), named(bob)), next = "check"),
                        StepDef(
                            id = "check",
                            type = StepType.APPROVE,
                            assignee = named(carol),
                            actions = ALL_ACTIONS,
                            next = Definition.END,
                        ),
                    ),
            )
        val transition = transition(shared)
        val started = transition.start(initiator, emptyList()).accepted().state
        val desk =
            started.round.steps
                .single()
        val atCheck = approveAll(transition, started, alice)

        val sent = transition.apply(atCheck, SendBack(carol, atCheck.openTaskOf(carol).id, desk.id, "again")).accepted().state
        val again =
            sent.round.steps
                .last()
        assertThat(again.nodeId).isEqualTo("desk")
        assertThat(again.needed).isEqualTo(1)
        assertThat(again.tasks.map { it.assignee }).containsExactly(alice, bob)
        assertThat(again.resolution).doesNotContainKey("excluded")
    }

    @Test
    fun `a holder who stands aside needs a reason, and the step goes to someone else or waits for an administrator`() {
        val transition = transition(twoSteps)
        val started = transition.start(initiator, emptyList()).accepted().state
        val task = started.openTaskOf(alice).id

        assertThat(transition.apply(started, Recuse(alice, task, " ")).refusal()).isEqualTo(Refusal.REMARK_REQUIRED)
        assertThat(transition.apply(started, Recuse(bob, task, "x")).refusal()).isEqualTo(Refusal.NOT_ASSIGNEE)
        val recused = transition.apply(started, Recuse(alice, task, "Related party")).accepted()
        val (old, again) = recused.state.round.steps

        assertThat(old.state).isEqualTo(StepState.SKIPPED)
        assertThat(old.tasks.single().state).isEqualTo(TaskState.CANCELLED)
        assertThat(again.nodeId).isEqualTo("first")
        val blocked = again.tasks.single()
        assertThat(blocked.assignee).isNull()
        assertThat(blocked.blocked).isEqualTo(BlockedReason.UNRESOLVED)
        @Suppress("UNCHECKED_CAST")
        val excluded = again.resolution!!["excluded"] as List<Map<String, Any?>>
        assertThat(excluded).containsExactly(mapOf("person" to alice.toString(), "rule" to "SOD-4"))
        assertThat(recused.effects).containsExactly(
            Effect.TaskClosed(task, TaskState.CANCELLED, null),
            Effect.TaskCreated(again.id, blocked),
        )
        assertThat(recused.action).isEqualTo(ActionRecord(ActionKind.RECUSE, 1, task, alice, "Related party"))

        val assigned = transition.apply(recused.state, Reassign(admin, blocked.id, dave, "acting")).accepted().state
        assertThat(approveAll(transition, assigned, dave).currentAssignees).containsExactly(bob)
    }

    @Test
    fun `when a candidate stands aside the others carry on, and a claim of theirs ends`() {
        val shared =
            Definition(
                start = "desk",
                steps =
                    listOf(
                        StepDef(
                            id = "desk",
                            type = StepType.APPROVE,
                            assignees = listOf(named(alice), named(bob), named(carol)),
                            next = Definition.END,
                        ),
                    ),
            )
        val transition = transition(shared)
        val started = transition.start(initiator, emptyList()).accepted().state
        val claimed = transition.apply(started, Claim(bob, started.openTaskOf(bob).id)).accepted().state
        val bobs = claimed.openTaskOf(bob).id

        val recused = transition.apply(claimed, Recuse(bob, bobs, "My brother's company")).accepted()
        val step =
            recused.state.round.steps
                .single()
        assertThat(step.claimedBy).isNull()
        assertThat(step.state).isEqualTo(StepState.ACTIVE)
        assertThat(recused.effects).containsExactly(Effect.TaskClosed(bobs, TaskState.CANCELLED, null))
        assertThat(recused.state.currentAssignees).containsExactlyInAnyOrder(alice, carol)
    }

    @Test
    fun `the other candidates of a claimed step cannot stand aside until it is released`() {
        val shared =
            Definition(
                start = "desk",
                steps =
                    listOf(
                        StepDef(
                            id = "desk",
                            type = StepType.APPROVE,
                            assignees = listOf(named(alice), named(bob), named(carol)),
                            next = Definition.END,
                        ),
                    ),
            )
        val transition = transition(shared)
        val started = transition.start(initiator, emptyList()).accepted().state
        val claimed = transition.apply(started, Claim(alice, started.openTaskOf(alice).id)).accepted().state
        val carols = claimed.openTaskOf(carol).id

        assertThat(transition.apply(claimed, Recuse(carol, carols, "interest")).refusal()).isEqualTo(Refusal.CLAIMED_BY_OTHER)
        val released = transition.apply(claimed, Release(alice, claimed.openTaskOf(alice).id)).accepted().state
        assertThat(transition.apply(released, Recuse(carol, carols, "interest")).refusal()).isNull()
    }

    @Test
    fun `a route approver who stands aside leaves the entry to an administrator, then the route goes on`() {
        val transition = transition(routed)
        val started = transition.start(initiator, listOf(alice, bob)).accepted().state

        val recused = transition.apply(started, Recuse(alice, started.openTaskOf(alice).id, "interest")).accepted().state
        val entry = recused.round.steps.single { it.state == StepState.ACTIVE }
        assertThat(entry.routeIndex).isEqualTo(0)
        assertThat(entry.tasks.single().blocked).isEqualTo(BlockedReason.UNRESOLVED)
        assertThat(entry.resolution).containsEntry("excluded", listOf(mapOf("person" to alice.toString(), "rule" to "SOD-4")))
        assertThat(
            recused.round.steps
                .filter { it.state == StepState.PENDING }
                .map { it.assignee },
        ).containsExactly(bob)

        val assigned = transition.apply(recused, Reassign(admin, entry.tasks.single().id, carol, "deputy")).accepted().state
        val atBob = approveAll(transition, assigned, carol)
        assertThat(atBob.currentAssignees).containsExactly(bob)
        assertThat(approveAll(transition, atBob, bob).status).isEqualTo(InstanceStatus.APPROVED)
    }

    @Test
    fun `nobody decides twice in a round unless the tenant allows it`() {
        val overlapping =
            Definition(
                start = "first",
                steps =
                    listOf(
                        StepDef(id = "first", type = StepType.APPROVE, assignee = named(alice), next = "second"),
                        StepDef(
                            id = "second",
                            type = StepType.APPROVE,
                            assignees = listOf(named(alice), named(bob)),
                            next = Definition.END,
                        ),
                    ),
            )
        val strict = transition(overlapping)
        val once = approveAll(strict, strict.start(initiator, emptyList()).accepted().state, alice)
        val second =
            once.round.steps
                .last()
        assertThat(second.tasks.map { it.assignee }).containsExactly(bob)
        assertThat(second.resolution).containsEntry("excluded", listOf(mapOf("person" to alice.toString(), "rule" to "SOD-2")))

        val lenient = transition(overlapping, oncePerRound = false)
        val twice = approveAll(lenient, lenient.start(initiator, emptyList()).accepted().state, alice)
        assertThat(
            twice.round.steps
                .last()
                .tasks
                .map { it.assignee },
        ).containsExactly(alice, bob)
    }
}
