package com.memo.workflow.definition

import com.memo.workflow.AttributeType
import net.jqwik.api.Arbitraries
import net.jqwik.api.Arbitrary
import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.Provide
import org.assertj.core.api.Assertions.assertThat
import java.util.UUID

/**
 * Generated definitions (design E03 §2): valid ones survive print → parse unchanged and pass the validator; the same
 * graphs with a link turned backwards are always refused as cycles (ADR-0038: going back is an action, not an edge).
 */
class DefinitionRoundTripProperties {
    private val validator = DefinitionValidator(null) { null }

    @Suppress("ktlint:standard:function-naming")
    @Property(tries = 300)
    fun `valid definitions round-trip and validate`(
        @ForAll("definitions") definition: Definition,
    ) {
        val printed = DefinitionJson.print(definition)
        assertThat(DefinitionJson.parse(printed)).isEqualTo(definition)
        val result = validator.validate(printed)
        assertThat(result.problems).isEmpty()
        assertThat(result.definition).isEqualTo(definition)
    }

    @Suppress("ktlint:standard:function-naming")
    @Property(tries = 300)
    fun `a link back to an earlier step is always refused as a cycle`(
        @ForAll("definitions") definition: Definition,
    ) {
        val linear = definition.steps.filter { it.next != null }
        if (linear.size < 2) return
        val last = linear.last()
        val looped = definition.copy(steps = definition.steps.map { if (it.id == last.id) it.copy(next = linear.first().id) else it })
        assertThat(validator.validate(DefinitionJson.print(looped)).problems.map { it.code }).contains("CYCLE")
    }

    /** A chain of 1–12 steps of random runnable types, each linking to the next; branches may also skip ahead or end. */
    @Provide
    fun definitions(): Arbitrary<Definition> =
        Arbitraries.integers().between(1, 12).flatMap { size ->
            Arbitraries
                .of(StepType.REVIEW, StepType.APPROVE, StepType.PARALLEL, StepType.BRANCH, StepType.NOTIFY, StepType.ESCALATE_TO_FORUM)
                .list()
                .ofSize(size)
                .flatMap { types ->
                    Arbitraries
                        .longs()
                        .between(1, 50)
                        .list()
                        .ofSize(size * 3)
                        .map { seeds -> build(types, seeds) }
                }
        }

    private fun build(
        types: List<StepType>,
        seeds: List<Long>,
    ): Definition {
        val ids = types.indices.map { "s$it" }
        val withRoute = (seeds.first() % 3L == 0L) && types.isNotEmpty()
        val steps = types.mapIndexed { i, type -> step(i, type, ids, seeds, i == 0 && withRoute) }
        val attributes = if (seeds.last() % 2L == 0L) listOf(Attribute("classificationRank", AttributeType.INT)) else emptyList()
        return Definition(
            attributes = attributes,
            resubmit = ResubmitPolicy.entries[(seeds[1] % 3L).toInt()],
            start = ids.first(),
            steps = steps,
        )
    }

    private fun step(
        i: Int,
        type: StepType,
        ids: List<String>,
        seeds: List<Long>,
        route: Boolean,
    ): StepDef {
        val next = ids.getOrElse(i + 1) { Definition.END }
        val person = { k: Int -> Assignee(AssigneeKind.PERSON, UUID(0, seeds[(i * 3 + k) % seeds.size] + k * 100L)) }
        return when {
            route -> {
                StepDef(id = ids[i], type = StepType.ROUTE, name = "Approvers", next = next)
            }

            type == StepType.REVIEW -> {
                StepDef(id = ids[i], type = type, name = "Review $i", assignee = person(0), next = next)
            }

            type == StepType.APPROVE -> {
                StepDef(
                    id = ids[i],
                    type = type,
                    assignee = person(0),
                    actions = listOf(StepAction.APPROVE, StepAction.RETURN, StepAction.SEND_BACK),
                    remarks = listOf(StepAction.SEND_BACK),
                    sla = Sla("P2D", true),
                    next = next,
                )
            }

            type == StepType.PARALLEL -> {
                StepDef(
                    id = ids[i],
                    type = type,
                    assignees = listOf(person(0), person(1), person(2)),
                    mode = ParallelMode.N_OF_M,
                    n = 2,
                    next = next,
                )
            }

            type == StepType.BRANCH -> {
                StepDef(
                    id = ids[i],
                    type = type,
                    branches = listOf(Branch("true", ids.getOrElse(i + 2) { Definition.END })),
                    otherwise = next,
                )
            }

            type == StepType.NOTIFY -> {
                StepDef(
                    id = ids[i],
                    type = type,
                    recipients = listOf(Assignee(AssigneeKind.INITIATOR)),
                    message = "done",
                    next = next,
                )
            }

            else -> {
                StepDef(id = ids[i], type = type, forum = "board", next = next)
            }
        }
    }
}
