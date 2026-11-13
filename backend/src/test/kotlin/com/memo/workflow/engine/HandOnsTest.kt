package com.memo.workflow.engine

import com.memo.workflow.ActionKind
import com.memo.workflow.BlockedReason
import com.memo.workflow.InstanceStatus
import com.memo.workflow.definition.Definition
import com.memo.workflow.definition.DefinitionCompiler
import com.memo.workflow.definition.ForwardWithin
import com.memo.workflow.definition.ParallelMode
import com.memo.workflow.definition.StepAction
import com.memo.workflow.definition.StepDef
import com.memo.workflow.definition.StepType
import com.memo.workflow.engine.EngineFixtures.ALL_ACTIONS
import com.memo.workflow.engine.EngineFixtures.named
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Forward, delegate, ask and answer, refer and opinion (design E03 §4.4, test plan T5) on the pure engine: who may do
 * them, what they need, and what they do to the step, its tasks and the history. Send back and recusal are in
 * [SendBackAndRecuseTest].
 */
class HandOnsTest {
    private val initiator = UUID(0, 1)
    private val alice = UUID(0, 2)
    private val bob = UUID(0, 3)
    private val carol = UUID(0, 4)
    private val dave = UUID(0, 5)
    private val ineligible = UUID(0, 6)

    private fun definition(forward: ForwardWithin? = null) =
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
                        forward = forward,
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

    private fun transition(
        definition: Definition = definition(),
        within: (UUID, UUID, ForwardWithin) -> Boolean = { _, _, _ -> true },
    ) = Transition(
        TransitionContext(
            EngineFixtures.ids(),
            { if (it == ineligible) BlockedReason.INACTIVE else null },
            maxApprovers = 5,
            within = within,
        ),
        definition,
    )

    private val transition = transition()

    private fun started(transition: Transition = this.transition): InstanceState = transition.start(initiator, emptyList()).accepted().state

    @Test
    fun `forward hands the step to the new holder, who decides it from then on`() {
        val state = started()
        val task = state.openTaskOf(alice)

        assertThat(transition.apply(state, Forward(alice, task.id, carol, " ")).refusal()).isEqualTo(Refusal.REMARK_REQUIRED)
        val forwarded = transition.apply(state, Forward(alice, task.id, carol, " for your area ")).accepted()
        val step =
            forwarded.state.round.steps
                .single()
        val replacement = forwarded.state.openTaskOf(carol)

        assertThat(step.assignee).isEqualTo(carol)
        assertThat(replacement.onBehalfOf).isNull()
        assertThat(replacement.kind).isEqualTo(TaskKind.DECIDE)
        assertThat(step.tasks.first { it.id == task.id }.state).isEqualTo(TaskState.TRANSFERRED)
        assertThat(forwarded.effects).containsExactly(
            Effect.TaskClosed(task.id, TaskState.TRANSFERRED, null),
            Effect.TaskCreated(step.id, replacement),
        )
        assertThat(forwarded.action)
            .isEqualTo(ActionRecord(ActionKind.FORWARD, 1, task.id, alice, "for your area", mapOf("to" to carol.toString())))
        assertThat(forwarded.state.currentAssignees).containsExactly(carol)
        assertThat(transition.apply(forwarded.state, Approve(alice, task.id)).refusal()).isEqualTo(Refusal.TASK_NOT_OPEN)

        val approved = transition.apply(forwarded.state, Approve(carol, replacement.id)).accepted()
        assertThat(approved.action.actingFor).describedAs("a forward moves responsibility").isNull()
        assertThat(approved.state.currentAssignees).containsExactly(bob)
    }

    @Test
    fun `forward and delegate go only to someone eligible who is not the holder, the initiator or already on the step`() {
        val state = started()
        val task = state.openTaskOf(alice).id

        assertThat(transition.apply(state, Forward(bob, task, carol, "x")).refusal()).isEqualTo(Refusal.NOT_ASSIGNEE)
        assertThat(transition.apply(state, Forward(alice, task, alice, "x")).refusal()).isEqualTo(Refusal.SAME_ASSIGNEE)
        assertThat(transition.apply(state, Forward(alice, task, initiator, "x")).refusal()).isEqualTo(Refusal.INITIATOR_AS_APPROVER)
        assertThat(transition.apply(state, Forward(alice, task, ineligible, "x")).refusal()).isEqualTo(Refusal.ASSIGNEE_NOT_ELIGIBLE)
        assertThat(transition.apply(state, Delegate(alice, task, alice, null)).refusal()).isEqualTo(Refusal.SAME_ASSIGNEE)
        assertThat(transition.apply(state, Delegate(alice, task, initiator, null)).refusal()).isEqualTo(Refusal.INITIATOR_AS_APPROVER)
        assertThat(transition.apply(state, Delegate(alice, task, ineligible, null)).refusal()).isEqualTo(Refusal.ASSIGNEE_NOT_ELIGIBLE)

        val board =
            Definition(
                start = "board",
                steps =
                    listOf(
                        StepDef(
                            id = "board",
                            type = StepType.PARALLEL,
                            assignees = listOf(named(alice), named(bob)),
                            mode = ParallelMode.ALL,
                            actions = ALL_ACTIONS,
                            next = Definition.END,
                        ),
                    ),
            )
        val parallel = transition(board)
        val shared = started(parallel)
        val alicesTask = shared.openTaskOf(alice).id
        assertThat(parallel.apply(shared, Forward(alice, alicesTask, bob, "x")).refusal()).isEqualTo(Refusal.ALREADY_ON_STEP)
        assertThat(parallel.apply(shared, Delegate(alice, alicesTask, bob, null)).refusal()).isEqualTo(Refusal.ALREADY_ON_STEP)
    }

    @Test
    fun `forwarding a parallel holder's task keeps the step's holders, and the replacement's approval counts instead`() {
        val board =
            Definition(
                start = "board",
                steps =
                    listOf(
                        StepDef(
                            id = "board",
                            type = StepType.PARALLEL,
                            assignees = listOf(named(alice), named(bob)),
                            mode = ParallelMode.ALL,
                            actions = ALL_ACTIONS,
                            next = Definition.END,
                        ),
                    ),
            )
        val parallel = transition(board)
        val shared = started(parallel)
        val step =
            shared.round.steps
                .single()

        val forwarded = parallel.apply(shared, Forward(alice, shared.openTaskOf(alice).id, carol, "my deputy decides")).accepted().state
        assertThat(
            forwarded.round.steps
                .single()
                .assignee,
        ).isEqualTo(step.assignee)
        assertThat(forwarded.currentAssignees).containsExactlyInAnyOrder(bob, carol)

        val one = parallel.apply(forwarded, Approve(carol, forwarded.openTaskOf(carol).id)).accepted().state
        assertThat(one.status).describedAs("the transferred task is not a missing approval").isEqualTo(InstanceStatus.RUNNING)
        assertThat(one.currentAssignees).containsExactly(bob)
        val both = parallel.apply(one, Approve(bob, one.openTaskOf(bob).id)).accepted().state
        assertThat(both.status).isEqualTo(InstanceStatus.APPROVED)
    }

    @Test
    fun `a step that does not offer an action refuses it`() {
        val sequential = Transition(TransitionContext(EngineFixtures.ids(), { null }, maxApprovers = 5), DefinitionCompiler.SEQUENTIAL)
        val state = sequential.start(initiator, listOf(alice, bob)).accepted().state
        val task = state.openTaskOf(alice).id
        val earlier = state.round.steps[1].id

        assertThat(sequential.apply(state, Forward(alice, task, carol, "x")).refusal()).isEqualTo(Refusal.ACTION_NOT_ALLOWED)
        assertThat(sequential.apply(state, Delegate(alice, task, carol, "x")).refusal()).isEqualTo(Refusal.ACTION_NOT_ALLOWED)
        assertThat(sequential.apply(state, Ask(alice, task, carol, "x")).refusal()).isEqualTo(Refusal.ACTION_NOT_ALLOWED)
        assertThat(sequential.apply(state, Refer(alice, task, listOf(carol), "x")).refusal()).isEqualTo(Refusal.ACTION_NOT_ALLOWED)
        assertThat(sequential.apply(state, SendBack(alice, task, earlier, "x")).refusal()).isEqualTo(Refusal.ACTION_NOT_ALLOWED)
    }

    @Test
    fun `a review step recommends instead of approving, and refuses what it does not offer`() {
        val review =
            Definition(
                start = "review",
                steps = listOf(StepDef(id = "review", type = StepType.REVIEW, assignee = named(alice), next = Definition.END)),
            )
        val reviewing = transition(review)
        val state = started(reviewing)
        val task = state.openTaskOf(alice).id

        assertThat(reviewing.apply(state, Reject(alice, task, "no")).refusal()).isEqualTo(Refusal.ACTION_NOT_ALLOWED)
        val recommended = reviewing.apply(state, Approve(alice, task)).accepted()
        assertThat(recommended.action.kind).isEqualTo(ActionKind.RECOMMEND)
        assertThat(recommended.effects.first()).isEqualTo(Effect.TaskClosed(task, TaskState.COMPLETED, ActionKind.RECOMMEND))
        assertThat(recommended.state.status).isEqualTo(InstanceStatus.APPROVED)
    }

    @Test
    fun `a step limited to the holder's unit refuses a forward outside it`() {
        val asked = mutableListOf<Triple<UUID, UUID, ForwardWithin>>()
        val limited =
            transition(definition(ForwardWithin.UNIT)) { holder, to, limit ->
                asked += Triple(holder, to, limit)
                to == carol
            }
        val state = started(limited)
        val task = state.openTaskOf(alice).id

        assertThat(limited.apply(state, Forward(alice, task, dave, "x")).refusal()).isEqualTo(Refusal.FORWARD_OUTSIDE_LIMIT)
        assertThat(
            limited
                .apply(state, Forward(alice, task, carol, "x"))
                .accepted()
                .state.currentAssignees,
        ).containsExactly(carol)
        assertThat(asked).containsExactly(Triple(alice, dave, ForwardWithin.UNIT), Triple(alice, carol, ForwardWithin.UNIT))

        val anyone = transition(definition(ForwardWithin.ANYONE)) { _, _, _ -> false }
        val open = started(anyone)
        assertThat(anyone.apply(open, Forward(alice, open.openTaskOf(alice).id, dave, "x")).refusal()).isNull()
    }

    @Test
    fun `a delegate acts for the holder, who keeps the step, and cannot hand it on again`() {
        val state = started()
        val task = state.openTaskOf(alice)

        val delegated = transition.apply(state, Delegate(alice, task.id, carol, null)).accepted()
        val step =
            delegated.state.round.steps
                .single()
        val replacement = delegated.state.openTaskOf(carol)
        assertThat(step.assignee).describedAs("responsibility stays with the holder").isEqualTo(alice)
        assertThat(replacement.onBehalfOf).isEqualTo(alice)
        assertThat(delegated.effects).containsExactly(
            Effect.TaskClosed(task.id, TaskState.TRANSFERRED, null),
            Effect.TaskCreated(step.id, replacement),
        )
        assertThat(delegated.action).isEqualTo(ActionRecord(ActionKind.DELEGATE, 1, task.id, alice, null, mapOf("to" to carol.toString())))
        assertThat(delegated.state.currentAssignees).containsExactly(carol)

        assertThat(transition.apply(delegated.state, Forward(carol, replacement.id, dave, "x")).refusal())
            .isEqualTo(Refusal.ACTION_NOT_ALLOWED)
        assertThat(transition.apply(delegated.state, Delegate(carol, replacement.id, dave, "x")).refusal())
            .isEqualTo(Refusal.ACTION_NOT_ALLOWED)

        val asked = transition.apply(delegated.state, Ask(carol, replacement.id, dave, "which figures?")).accepted()
        assertThat(asked.action.actingFor).isEqualTo(alice)
        val approved = transition.apply(delegated.state, Approve(carol, replacement.id, "ok")).accepted()
        assertThat(approved.action.actor).isEqualTo(carol)
        assertThat(approved.action.actingFor).isEqualTo(alice)
        assertThat(approved.state.currentAssignees).containsExactly(bob)
    }

    @Test
    fun `asking puts the holder's task on hold until the person asked answers`() {
        val state = started()
        val task = state.openTaskOf(alice)

        val asked = transition.apply(state, Ask(alice, task.id, initiator, "Which annex?")).accepted()
        val step =
            asked.state.round.steps
                .single()
        val question = asked.state.openTaskOf(initiator)
        assertThat(question.kind).isEqualTo(TaskKind.CLARIFY)
        assertThat(question.parentTaskId).isEqualTo(task.id)
        assertThat(
            asked.state
                .findTask(task.id)!!
                .second.waiting,
        ).isTrue()
        assertThat(asked.effects).containsExactly(Effect.TaskWaiting(task.id, true), Effect.TaskCreated(step.id, question))
        assertThat(asked.action).isEqualTo(
            ActionRecord(
                ActionKind.ASK,
                1,
                task.id,
                alice,
                "Which annex?",
                mapOf("to" to initiator.toString()),
            ),
        )
        assertThat(asked.state.currentAssignees).describedAs("the item is held by the person asked").containsExactly(initiator)
        assertThat(asked.state.openDecisions.map { it.id }).containsExactly(task.id)

        assertThat(transition.apply(asked.state, Approve(alice, task.id)).refusal()).isEqualTo(Refusal.WAITING_FOR_ANSWER)
        assertThat(transition.apply(asked.state, Ask(alice, task.id, carol, "again?")).refusal()).isEqualTo(Refusal.WAITING_FOR_ANSWER)
        assertThat(transition.apply(asked.state, Recall(initiator)).refusal()).isEqualTo(Refusal.NOT_RECALLABLE)

        val answered = transition.apply(asked.state, Answer(initiator, question.id, "Annex B")).accepted()
        assertThat(answered.effects).containsExactly(
            Effect.TaskClosed(question.id, TaskState.COMPLETED, ActionKind.ANSWER),
            Effect.TaskWaiting(task.id, false),
        )
        assertThat(answered.action).isEqualTo(ActionRecord(ActionKind.ANSWER, 1, question.id, initiator, "Annex B"))
        assertThat(
            answered.state
                .findTask(task.id)!!
                .second.waiting,
        ).isFalse()
        assertThat(
            answered.state
                .findTask(question.id)!!
                .second.outcome,
        ).isEqualTo(ActionKind.ANSWER)
        assertThat(answered.state.currentAssignees).containsExactly(alice)
        assertThat(transition.apply(answered.state, Approve(alice, task.id)).refusal()).isNull()
    }

    @Test
    fun `a question needs a text, someone else who is eligible, and an answer from that person only`() {
        val state = started()
        val task = state.openTaskOf(alice).id

        assertThat(transition.apply(state, Ask(alice, task, carol, null)).refusal()).isEqualTo(Refusal.REMARK_REQUIRED)
        assertThat(transition.apply(state, Ask(alice, task, alice, "x")).refusal()).isEqualTo(Refusal.SAME_ASSIGNEE)
        assertThat(transition.apply(state, Ask(alice, task, ineligible, "x")).refusal()).isEqualTo(Refusal.ASSIGNEE_NOT_ELIGIBLE)
        assertThat(transition.apply(state, Answer(alice, task, "x")).refusal()).isEqualTo(Refusal.NOT_A_CONSULTATION)

        val asked = transition.apply(state, Ask(alice, task, carol, "Which annex?")).accepted().state
        val question = asked.openTaskOf(carol).id
        assertThat(transition.apply(asked, Answer(dave, question, "x")).refusal()).isEqualTo(Refusal.NOT_ASSIGNEE)
        assertThat(transition.apply(asked, Answer(carol, question, " ")).refusal()).isEqualTo(Refusal.REMARK_REQUIRED)
        assertThat(transition.apply(asked, GiveOpinion(carol, question, "x")).refusal()).isEqualTo(Refusal.NOT_A_CONSULTATION)
        assertThat(transition.apply(asked, Approve(carol, question)).refusal()).isEqualTo(Refusal.ACTION_NOT_ALLOWED)
        assertThat(transition.apply(asked, Recuse(carol, question, "x")).refusal()).isEqualTo(Refusal.ACTION_NOT_ALLOWED)
    }

    @Test
    fun `referring asks each person for an opinion while the holder keeps deciding`() {
        val state = started()
        val task = state.openTaskOf(alice)

        val referred = transition.apply(state, Refer(alice, task.id, listOf(carol, dave), "Views on the budget")).accepted()
        val step =
            referred.state.round.steps
                .single()
        val opinions = step.tasks.filter { it.kind == TaskKind.OPINION }
        assertThat(opinions.map { it.assignee }).containsExactly(carol, dave)
        assertThat(opinions.map { it.parentTaskId }).containsOnly(task.id)
        assertThat(referred.effects).containsExactlyElementsOf(opinions.map { Effect.TaskCreated(step.id, it) })
        assertThat(referred.action.kind).isEqualTo(ActionKind.REFER)
        assertThat(referred.action.details).isEqualTo(mapOf("to" to listOf(carol.toString(), dave.toString())))
        assertThat(
            referred.state
                .findTask(task.id)!!
                .second.waiting,
        ).isFalse()
        assertThat(
            referred.state.currentAssignees,
        ).describedAs("people asked for an opinion are consulted, not holders").containsExactly(alice)
        assertThat(referred.state.openDecisions.map { it.id }).containsExactly(task.id)

        val carols = referred.state.openTaskOf(carol).id
        assertThat(transition.apply(referred.state, GiveOpinion(carol, carols, null)).refusal()).isEqualTo(Refusal.REMARK_REQUIRED)
        assertThat(transition.apply(referred.state, Answer(carol, carols, "x")).refusal()).isEqualTo(Refusal.NOT_A_CONSULTATION)
        assertThat(transition.apply(referred.state, GiveOpinion(dave, carols, "x")).refusal()).isEqualTo(Refusal.NOT_ASSIGNEE)
        val given = transition.apply(referred.state, GiveOpinion(carol, carols, "Within budget")).accepted()
        assertThat(given.effects).containsExactly(Effect.TaskClosed(carols, TaskState.COMPLETED, ActionKind.OPINION))
        assertThat(given.action).isEqualTo(ActionRecord(ActionKind.OPINION, 1, carols, carol, "Within budget"))
        assertThat(
            given.state
                .findTask(carols)!!
                .second.outcome,
        ).isEqualTo(ActionKind.OPINION)

        val daves = given.state.openTaskOf(dave).id
        val decided = transition.apply(given.state, Approve(alice, task.id)).accepted()
        assertThat(decided.effects)
            .describedAs("an opinion not given is no longer needed")
            .contains(Effect.TaskClosed(daves, TaskState.CANCELLED, null))
        assertThat(decided.state.currentAssignees).containsExactly(bob)
    }

    @Test
    fun `a referral names each person once, not the holder, and keeps within the limits`() {
        val state = started()
        val task = state.openTaskOf(alice).id
        val many = (1..HandOnGuards.MAX_REFERRALS + 1).map { UUID(5, it.toLong()) }

        assertThat(transition.apply(state, Refer(alice, task, emptyList(), "x")).refusal()).isEqualTo(Refusal.ROUTE_DUPLICATE)
        assertThat(transition.apply(state, Refer(alice, task, listOf(carol, carol), "x")).refusal()).isEqualTo(Refusal.ROUTE_DUPLICATE)
        assertThat(transition.apply(state, Refer(alice, task, listOf(carol, alice), "x")).refusal()).isEqualTo(Refusal.SAME_ASSIGNEE)
        assertThat(transition.apply(state, Refer(alice, task, listOf(carol, ineligible), "x")).refusal())
            .isEqualTo(Refusal.ASSIGNEE_NOT_ELIGIBLE)
        assertThat(transition.apply(state, Refer(alice, task, listOf(carol), null)).refusal()).isEqualTo(Refusal.REMARK_REQUIRED)
        assertThat(transition.apply(state, Refer(alice, task, many, "x")).refusal()).isEqualTo(Refusal.TOO_MANY_REFERRALS)

        // Up to 10 at a time, and up to 30 opinions open on the step.
        var open = state
        repeat(HandOnGuards.MAX_OPEN_OPINIONS / HandOnGuards.MAX_REFERRALS) { batch ->
            val people = (1..HandOnGuards.MAX_REFERRALS).map { UUID(6 + batch.toLong(), it.toLong()) }
            open = transition.apply(open, Refer(alice, task, people, "x")).accepted().state
        }
        assertThat(open.openTasks.count { it.kind == TaskKind.OPINION }).isEqualTo(HandOnGuards.MAX_OPEN_OPINIONS)
        assertThat(transition.apply(open, Refer(alice, task, listOf(carol), "x")).refusal()).isEqualTo(Refusal.TOO_MANY_REFERRALS)

        val one = open.openTasks.first { it.kind == TaskKind.OPINION }
        val fewer = transition.apply(open, GiveOpinion(one.assignee!!, one.id, "fine")).accepted().state
        assertThat(transition.apply(fewer, Refer(alice, task, listOf(carol), "x")).refusal()).isNull()
    }

    @Test
    fun `consultations of a claimed step are still answered by the people asked`() {
        val shared =
            Definition(
                start = "desk",
                steps =
                    listOf(
                        StepDef(
                            id = "desk",
                            type = StepType.APPROVE,
                            assignees = listOf(named(alice), named(bob)),
                            actions = ALL_ACTIONS,
                            next = Definition.END,
                        ),
                    ),
            )
        val desk = transition(shared)
        val state = started(desk)
        val claimed = desk.apply(state, Claim(alice, state.openTaskOf(alice).id)).accepted().state
        val referred = desk.apply(claimed, Refer(alice, claimed.openTaskOf(alice).id, listOf(carol), "views?")).accepted().state

        assertThat(desk.apply(referred, GiveOpinion(carol, referred.openTaskOf(carol).id, "fine")).refusal()).isNull()
        assertThat(desk.apply(referred, Approve(bob, referred.openTaskOf(bob).id)).refusal()).isEqualTo(Refusal.CLAIMED_BY_OTHER)
    }

    @Test
    fun `the engine never offers what a definition leaves out`() {
        val narrow =
            Definition(
                start = "first",
                steps =
                    listOf(
                        StepDef(
                            id = "first",
                            type = StepType.APPROVE,
                            assignee = named(alice),
                            actions = listOf(StepAction.APPROVE, StepAction.DELEGATE),
                            remarks = listOf(StepAction.APPROVE, StepAction.DELEGATE),
                            next = Definition.END,
                        ),
                    ),
            )
        val narrowed = transition(narrow)
        val state = started(narrowed)
        val task = state.openTaskOf(alice).id

        assertThat(narrowed.apply(state, Return(alice, task, "x")).refusal()).isEqualTo(Refusal.ACTION_NOT_ALLOWED)
        assertThat(narrowed.apply(state, Approve(alice, task)).refusal())
            .describedAs("the definition requires a remark")
            .isEqualTo(Refusal.REMARK_REQUIRED)
        assertThat(narrowed.apply(state, Delegate(alice, task, carol, null)).refusal()).isEqualTo(Refusal.REMARK_REQUIRED)
        assertThat(narrowed.apply(state, Delegate(alice, task, carol, "x")).refusal()).isNull()
    }
}
