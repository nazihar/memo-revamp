package com.memo.workflow.engine

import com.memo.workflow.ActionKind
import com.memo.workflow.BlockedReason
import com.memo.workflow.definition.Definition
import com.memo.workflow.definition.StepDef
import com.memo.workflow.definition.StepType
import com.memo.workflow.engine.EngineFixtures.ALL_ACTIONS
import com.memo.workflow.engine.EngineFixtures.named
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * An overdue task escalates (design E05 §4.3, ADR-0041), run by the workflow's system actor: `notify` tells the targets
 * and the holder keeps the task; `reassign` and `nextLevel` move it to the target, or to a blocked task an
 * administrator assigns when nobody was found.
 */
class EscalationsTest {
    private val initiator = UUID(0, 1)
    private val alice = UUID(0, 2)
    private val bob = UUID(0, 3)
    private val carol = UUID(0, 4)
    private val supervisor = UUID(0, 5)
    private val inactive = UUID(0, 6)
    private val admin = UUID(0, 9)
    private val system = UUID(9, 9)

    private val definition =
        Definition(
            start = "first",
            steps =
                listOf(
                    StepDef(id = "first", type = StepType.APPROVE, assignee = named(alice), actions = ALL_ACTIONS, next = "second"),
                    StepDef(id = "second", type = StepType.APPROVE, assignee = named(bob), next = Definition.END),
                ),
        )

    private val transition =
        Transition(
            TransitionContext(EngineFixtures.ids(), { if (it == inactive) BlockedReason.INACTIVE else null }, maxApprovers = 5),
            definition,
        )

    private val started = transition.start(initiator, emptyList()).accepted().state
    private val task = started.openTaskOf(alice).id

    @Test
    fun `a notify escalation tells the targets and the holder keeps the task`() {
        val escalated =
            transition
                .apply(
                    started,
                    Escalate(system, task, "notify", move = false, to = null, targets = listOf(supervisor, initiator)),
                ).accepted()

        assertThat(escalated.state).isEqualTo(started)
        assertThat(escalated.effects).containsExactly(Effect.Escalated(task, listOf(supervisor, initiator)))
        assertThat(escalated.action).isEqualTo(
            ActionRecord(ActionKind.ESCALATE, 1, task, system, null, mapOf("action" to "notify", "to" to supervisor.toString())),
        )
    }

    @Test
    fun `a reassign escalation moves the task to the target, who then decides`() {
        val escalated = transition.apply(started, Escalate(system, task, "reassign", move = true, to = carol)).accepted()
        val step =
            escalated.state.round.steps
                .single()
        val replacement = escalated.state.openTaskOf(carol)

        assertThat(step.assignee).isEqualTo(carol)
        assertThat(step.tasks.first { it.id == task }.state).isEqualTo(TaskState.REASSIGNED)
        assertThat(replacement.blocked).isNull()
        assertThat(escalated.effects).containsExactly(
            Effect.TaskClosed(task, TaskState.REASSIGNED, null),
            Effect.TaskCreated(step.id, replacement),
        )
        assertThat(escalated.action).isEqualTo(
            ActionRecord(ActionKind.ESCALATE, 1, task, system, null, mapOf("action" to "reassign", "to" to carol.toString())),
        )
        assertThat(transition.apply(escalated.state, Approve(alice, task)).refusal()).isEqualTo(Refusal.TASK_NOT_OPEN)
        val approved = transition.apply(escalated.state, Approve(carol, replacement.id)).accepted().state
        assertThat(approved.currentAssignees).containsExactly(bob)
    }

    @Test
    fun `an escalation with nobody to move to leaves a blocked task for an administrator`() {
        val escalated = transition.apply(started, Escalate(system, task, "nextLevel", move = true, to = null)).accepted()
        val step =
            escalated.state.round.steps
                .single()
        val replacement = escalated.state.openTasks.single()

        assertThat(step.assignee).isNull()
        assertThat(replacement.assignee).isNull()
        assertThat(replacement.blocked).isEqualTo(BlockedReason.UNRESOLVED)
        assertThat(escalated.action.details).isEqualTo(mapOf("action" to "nextLevel", "to" to null))
        assertThat(escalated.state.currentAssignees).isEmpty()

        val assigned = transition.apply(escalated.state, Reassign(admin, replacement.id, carol, "covering")).accepted().state
        assertThat(assigned.currentAssignees).containsExactly(carol)
    }

    @Test
    fun `a task moved to someone who may not hold it is blocked`() {
        val escalated = transition.apply(started, Escalate(system, task, "reassign", move = true, to = inactive)).accepted()
        assertThat(escalated.state.openTaskOf(inactive).blocked).isEqualTo(BlockedReason.INACTIVE)
    }

    @Test
    fun `only an open decision task of a running instance escalates`() {
        assertThat(
            transition.apply(started, Escalate(system, UUID(8, 8), "notify", false, null)).refusal(),
        ).isEqualTo(Refusal.TASK_NOT_FOUND)

        val referred = transition.apply(started, Refer(alice, task, listOf(carol), "views?")).accepted().state
        val opinion = referred.openTaskOf(carol).id
        assertThat(transition.apply(referred, Escalate(system, opinion, "notify", false, null)).refusal()).isEqualTo(Refusal.TASK_NOT_OPEN)

        val approved = transition.apply(started, Approve(alice, task)).accepted().state
        assertThat(transition.apply(approved, Escalate(system, task, "notify", false, null)).refusal()).isEqualTo(Refusal.TASK_NOT_OPEN)

        val withdrawn = transition.apply(started, Withdraw(initiator)).accepted().state
        assertThat(transition.apply(withdrawn, Escalate(system, task, "notify", false, null)).refusal()).isEqualTo(Refusal.WRONG_STATE)
    }
}
