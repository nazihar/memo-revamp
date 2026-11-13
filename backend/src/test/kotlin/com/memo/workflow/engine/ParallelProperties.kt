package com.memo.workflow.engine

import com.memo.workflow.InstanceStatus
import com.memo.workflow.definition.Assignee
import com.memo.workflow.definition.AssigneeKind
import com.memo.workflow.definition.Definition
import com.memo.workflow.definition.ParallelMode
import com.memo.workflow.definition.StepDef
import com.memo.workflow.definition.StepType
import net.jqwik.api.Arbitraries
import net.jqwik.api.Arbitrary
import net.jqwik.api.Combinators
import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.Provide
import net.jqwik.api.statistics.Statistics
import org.assertj.core.api.Assertions.assertThat
import java.util.UUID

/**
 * Parallel steps against an oracle written from the rule table (design E03 §4.3): any mode, two to five holders,
 * decisions in any order. The step completes at most once, the instance ends exactly when the oracle says, and no task
 * stays open after the step is settled.
 */
class ParallelProperties {
    private val initiator = UUID(0, 0)

    enum class Choice { APPROVE, REJECT, RETURN }

    data class Case(
        val mode: ParallelMode,
        val holders: Int,
        val n: Int,
        val order: List<Int>,
        val choices: List<Choice>,
    )

    @Suppress("ktlint:standard:function-naming")
    @Property(tries = 1_000)
    fun `parallel steps settle exactly when the rule table says`(
        @ForAll("cases") case: Case,
    ) {
        val people = (1..case.holders).map { UUID(0, it.toLong()) }
        val node =
            StepDef(
                id = "board",
                type = StepType.PARALLEL,
                assignees = people.map { Assignee(AssigneeKind.PERSON, it) },
                mode = case.mode,
                n = case.n.takeIf { case.mode == ParallelMode.N_OF_M },
                next = Definition.END,
            )
        var nextId = 1_000L
        val transition =
            Transition(
                TransitionContext({ UUID(1, nextId++) }, { null }, maxApprovers = 4),
                Definition(start = "board", steps = listOf(node)),
            )
        var state = (transition.start(initiator, emptyList()) as Decision.Accepted).outcome.state
        var roundsEnded = 0
        case.order.forEachIndexed { i, who ->
            // Only someone who still holds an open task decides; the others' turns pass.
            val task = state.openTasks.firstOrNull { it.assignee == people[who] }?.takeIf { state.status == InstanceStatus.RUNNING }
            if (task != null) {
                val command =
                    when (case.choices[i]) {
                        Choice.APPROVE -> Approve(task.assignee!!, task.id)
                        Choice.REJECT -> Reject(task.assignee!!, task.id, "no")
                        Choice.RETURN -> Return(task.assignee!!, task.id, "fix it")
                    }
                val outcome = (transition.apply(state, command) as Decision.Accepted).outcome
                roundsEnded += outcome.effects.count { it is Effect.RoundEnded }
                state = outcome.state
            }
        }
        val expected = oracle(case)
        Statistics.label("outcome").collect(expected)
        assertThat(state.status).isEqualTo(expected)
        assertThat(roundsEnded).isLessThanOrEqualTo(1)
        if (state.status != InstanceStatus.RUNNING) assertThat(state.openTasks).isEmpty()
    }

    /** The rule table, read literally: count approvals and noes in decision order; the first decisive point wins. */
    private fun oracle(case: Case): InstanceStatus {
        val needed =
            when (case.mode) {
                ParallelMode.ALL -> case.holders
                ParallelMode.ANY -> 1
                ParallelMode.N_OF_M -> case.n
            }
        // Each person's first decision counts; later turns of theirs find no open task.
        val seen = mutableSetOf<Int>()
        val firsts =
            case.order
                .withIndex()
                .filter { seen.add(it.value) }
                .map { case.choices[it.index] }
        var approvals = 0
        val after =
            firsts.mapIndexed { decided, choice ->
                if (choice == Choice.APPROVE) approvals++
                val open = case.holders - (decided + 1)
                when {
                    choice == Choice.RETURN -> InstanceStatus.RETURNED
                    approvals >= needed -> InstanceStatus.APPROVED
                    approvals + open < needed -> InstanceStatus.REJECTED
                    else -> InstanceStatus.RUNNING
                }
            }
        return after.firstOrNull { it != InstanceStatus.RUNNING } ?: InstanceStatus.RUNNING
    }

    @Provide
    fun cases(): Arbitrary<Case> =
        Arbitraries.integers().between(2, 5).flatMap { holders ->
            Combinators
                .combine(
                    Arbitraries.of(*ParallelMode.entries.toTypedArray()),
                    Arbitraries.integers().between(1, holders),
                    Arbitraries
                        .integers()
                        .between(0, holders - 1)
                        .list()
                        .ofMinSize(1)
                        .ofMaxSize(holders * 2),
                    Arbitraries.of(Choice.APPROVE, Choice.APPROVE, Choice.REJECT, Choice.RETURN).list().ofSize(holders * 2),
                ).`as` { mode, n, order, choices -> Case(mode, holders, n, order, choices) }
        }
}
