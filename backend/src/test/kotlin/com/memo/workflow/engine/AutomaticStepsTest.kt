package com.memo.workflow.engine

import com.memo.workflow.ActionKind
import com.memo.workflow.AttributeType
import com.memo.workflow.definition.Assignee
import com.memo.workflow.definition.AssigneeKind
import com.memo.workflow.definition.Attribute
import com.memo.workflow.definition.Branch
import com.memo.workflow.definition.Definition
import com.memo.workflow.definition.StepDef
import com.memo.workflow.definition.StepType
import com.memo.workflow.engine.EngineFixtures.named
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * Steps without a person (design E03 §4.2, ADR-0039): a branch takes the first true condition (else `otherwise`) and
 * records what it read; a notify step tells its recipients; an escalate-to-forum step is published. Each is recorded
 * as an automatic action by the person whose action led there.
 */
class AutomaticStepsTest {
    private val initiator = UUID(0, 1)
    private val alice = UUID(0, 2)
    private val bob = UUID(0, 3)
    private val carol = UUID(0, 4)

    private val definition =
        Definition(
            attributes =
                listOf(
                    Attribute("amount", AttributeType.INT),
                    Attribute("category", AttributeType.STRING),
                    Attribute("due", AttributeType.TIMESTAMP),
                ),
            start = "first",
            steps =
                listOf(
                    StepDef(id = "first", type = StepType.APPROVE, assignee = named(alice), next = "size"),
                    StepDef(
                        id = "size",
                        type = StepType.BRANCH,
                        branches =
                            listOf(
                                Branch("item.amount > 1000", "tell"),
                                Branch("item.category == 'capex' && item.due > timestamp('2026-01-01T00:00:00Z')", "board"),
                            ),
                        otherwise = "board",
                    ),
                    StepDef(
                        id = "tell",
                        type = StepType.NOTIFY,
                        recipients = listOf(Assignee(AssigneeKind.INITIATOR), named(carol), named(carol)),
                        message = "workflow.large",
                        next = "board",
                    ),
                    StepDef(id = "board", type = StepType.ESCALATE_TO_FORUM, forum = "credit-committee", next = "last"),
                    StepDef(id = "last", type = StepType.APPROVE, assignee = named(bob), next = Definition.END),
                ),
        )

    private fun afterFirst(attributes: Map<String, Any?>): Outcome {
        val transition =
            Transition(TransitionContext(EngineFixtures.ids(), { null }, maxApprovers = 5, attributes = { attributes }), definition)
        val started = transition.start(initiator, emptyList()).accepted().state
        return transition.apply(started, Approve(alice, started.openTaskOf(alice).id)).accepted()
    }

    @Test
    fun `a branch takes the first true condition and records the values it read`() {
        val outcome = afterFirst(mapOf("amount" to 1500, "category" to "opex"))

        val route = outcome.followUps.first()
        assertThat(route.kind).isEqualTo(ActionKind.ROUTE)
        assertThat(route.actor).isEqualTo(alice)
        assertThat(route.details).isEqualTo(
            mapOf("step" to "size", "taken" to 0, "inputs" to mapOf("amount" to 1500), "failed" to emptyList<Int>()),
        )
        assertThat(outcome.followUps.map { it.kind }).containsExactly(ActionKind.ROUTE, ActionKind.NOTIFY, ActionKind.ESCALATE_TO_FORUM)
        assertThat(outcome.state.currentAssignees).containsExactly(bob)
    }

    @Test
    fun `a notify step tells each recipient once, and an escalation to a forum is published`() {
        val outcome = afterFirst(mapOf("amount" to 1500))

        assertThat(outcome.effects).contains(
            Effect.Notify("tell", listOf(initiator, carol), "workflow.large"),
            Effect.EscalateToForum("board", "credit-committee"),
        )
        val (_, notify, forum) = outcome.followUps
        assertThat(notify).isEqualTo(
            ActionRecord(ActionKind.NOTIFY, 1, null, alice, null, mapOf("step" to "tell", "message" to "workflow.large")),
        )
        assertThat(forum).isEqualTo(
            ActionRecord(ActionKind.ESCALATE_TO_FORUM, 1, null, alice, null, mapOf("step" to "board", "forum" to "credit-committee")),
        )
    }

    @Test
    fun `a later condition is read when the earlier ones are false, and values are shown short`() {
        val due = Instant.parse("2026-06-01T00:00:00Z")
        val outcome = afterFirst(mapOf("amount" to 10, "category" to "capex", "due" to due))

        val route = outcome.followUps.first()
        assertThat(route.details["taken"]).isEqualTo(1)
        assertThat(route.details["inputs"]).isEqualTo(mapOf("amount" to 10, "category" to "capex", "due" to "2026-06-01T00:00:00Z"))
        assertThat(outcome.followUps.map { it.kind })
            .describedAs("the notify step was not on the way")
            .containsExactly(ActionKind.ROUTE, ActionKind.ESCALATE_TO_FORUM)
        assertThat(outcome.effects.filterIsInstance<Effect.Notify>()).isEmpty()

        val long = afterFirst(mapOf("amount" to 10, "category" to "x".repeat(500), "due" to due)).followUps.first()

        @Suppress("UNCHECKED_CAST")
        val inputs = long.details["inputs"] as Map<String, Any?>
        assertThat(inputs["category"] as String).hasSize(200)
        assertThat(long.details["taken"]).isEqualTo("otherwise")
    }

    @Test
    fun `conditions that cannot be evaluated count as false and are recorded`() {
        val outcome = afterFirst(emptyMap())

        val route = outcome.followUps.first()
        assertThat(route.details["taken"]).isEqualTo("otherwise")
        assertThat(route.details["failed"]).isEqualTo(listOf(0, 1))
        assertThat(route.details["inputs"]).isEqualTo(mapOf("amount" to null, "category" to null, "due" to null))
        assertThat(outcome.state.currentAssignees).containsExactly(bob)
    }
}
