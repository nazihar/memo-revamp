package com.memo.workflow.engine

import com.memo.workflow.ActionKind
import com.memo.workflow.AttributeType
import com.memo.workflow.InstanceStatus
import com.memo.workflow.definition.Assignee
import com.memo.workflow.definition.AssigneeKind
import com.memo.workflow.definition.Attribute
import com.memo.workflow.definition.Branch
import com.memo.workflow.definition.Definition
import com.memo.workflow.definition.StepDef
import com.memo.workflow.definition.StepType
import net.jqwik.api.Arbitraries
import net.jqwik.api.Arbitrary
import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.Provide
import org.assertj.core.api.Assertions.assertThat
import java.util.UUID

/**
 * Walking generated definitions (design E03 §4.2, test plan T3): approving every task in turn always reaches the end;
 * exactly one decision task is open at a time; every task goes to the person its step names (or the route's next
 * approver); and each branch, notify or escalate step passed leaves exactly one automatic action.
 */
class DefinitionWalkProperties {
    private val initiator = UUID(0, 0)
    private val route = listOf(UUID(0, 7), UUID(0, 8))

    @Suppress("ktlint:standard:function-naming")
    @Property(tries = 500)
    fun `approving every task walks any definition to the end`(
        @ForAll("definitions") case: Case,
    ) {
        var nextId = 1_000L
        val transition =
            Transition(
                TransitionContext({ UUID(1, nextId++) }, { null }, maxApprovers = 4, attributes = { mapOf("amount" to case.amount) }),
                case.definition,
            )
        val routed = case.definition.steps.any { it.type == StepType.ROUTE }
        var outcome = (transition.start(initiator, if (routed) route else emptyList()) as Decision.Accepted).outcome
        val automatic = outcome.followUps.toMutableList()
        var decisions = 0
        while (outcome.state.status == InstanceStatus.RUNNING) {
            val open = outcome.state.openTasks
            assertThat(open).hasSize(1)
            val (step, task) = outcome.state.findTask(open.single().id)!!
            val node = case.definition.step(step.nodeId)
            val expected = if (node.type == StepType.ROUTE) route[step.routeIndex!!] else node.assignee!!.userId
            assertThat(task.assignee).isEqualTo(expected)
            outcome = (transition.apply(outcome.state, Approve(task.assignee!!, task.id)) as Decision.Accepted).outcome
            automatic += outcome.followUps
            decisions++
            check(decisions < MAX_DECISIONS) { "the walk does not end" }
        }
        assertThat(outcome.state.status).isEqualTo(InstanceStatus.APPROVED)
        assertThat(outcome.state.openTasks).isEmpty()
        assertThat(automatic.map { it.kind }).allMatch { it in setOf(ActionKind.ROUTE, ActionKind.NOTIFY, ActionKind.ESCALATE_TO_FORUM) }
        val kinds = mapOf(ActionKind.ROUTE to StepType.BRANCH, ActionKind.NOTIFY to StepType.NOTIFY)
        automatic.filter { it.kind in kinds }.forEach { record ->
            assertThat(case.definition.step(record.details["step"] as String).type).isEqualTo(kinds.getValue(record.kind))
        }
    }

    data class Case(
        val definition: Definition,
        val amount: Int,
    )

    /** A chain of 1–10 steps (review, approve, branch on the amount, notify, escalate, at most one route). */
    @Provide
    fun definitions(): Arbitrary<Case> =
        Arbitraries.integers().between(1, 10).flatMap { size ->
            Arbitraries
                .of(StepType.REVIEW, StepType.APPROVE, StepType.BRANCH, StepType.NOTIFY, StepType.ESCALATE_TO_FORUM, StepType.ROUTE)
                .list()
                .ofSize(size)
                .flatMap { types -> Arbitraries.integers().between(0, 2000).map { amount -> Case(build(types), amount) } }
        }

    private fun build(types: List<StepType>): Definition {
        val ids = types.indices.map { "s$it" }
        var routeUsed = false
        val steps =
            types.mapIndexed { i, chosen ->
                val type = if (chosen == StepType.ROUTE && routeUsed) StepType.APPROVE else chosen
                if (type == StepType.ROUTE) routeUsed = true
                val next = ids.getOrElse(i + 1) { Definition.END }
                val person = Assignee(AssigneeKind.PERSON, UUID(0, 100L + i))
                when (type) {
                    StepType.REVIEW, StepType.APPROVE -> {
                        StepDef(id = ids[i], type = type, assignee = person, next = next)
                    }

                    StepType.BRANCH -> {
                        StepDef(
                            id = ids[i],
                            type = type,
                            branches = listOf(Branch("item.amount > 1000", ids.getOrElse(i + 2) { Definition.END })),
                            otherwise = next,
                        )
                    }

                    StepType.NOTIFY -> {
                        StepDef(id = ids[i], type = type, recipients = listOf(Assignee(AssigneeKind.INITIATOR)), message = "m", next = next)
                    }

                    StepType.ROUTE -> {
                        StepDef(id = ids[i], type = type, next = next)
                    }

                    else -> {
                        StepDef(id = ids[i], type = type, forum = "board", next = next)
                    }
                }
            }
        return Definition(attributes = listOf(Attribute("amount", AttributeType.INT)), start = ids.first(), steps = steps)
    }

    private companion object {
        const val MAX_DECISIONS = 50
    }
}
