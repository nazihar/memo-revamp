package com.memo.workflow.engine

import com.memo.workflow.BlockedReason
import com.memo.workflow.InstanceStatus
import com.memo.workflow.definition.Assignee
import com.memo.workflow.definition.AssigneeKind
import com.memo.workflow.definition.Definition
import com.memo.workflow.definition.StepDef
import com.memo.workflow.definition.StepType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * `doa` steps (design E04 §4.3, test plan T6): the matching rule's groups activate in order, each needing its count of
 * approvals; a group that cannot be filled, or no rule at all, waits for an administrator; nobody approves in two
 * groups of one rule (SOD-2); a group cannot be claimed. The DoA and the resolver are fakes; the real ones are `DoaIT`.
 */
class DoaStepTest {
    private val initiator = UUID(0, 1)
    private val dmd = UUID(0, 2)
    private val amd = UUID(0, 3)
    private val ceo = UUID(0, 4)
    private val dmdPosition = UUID(7, 2)
    private val amdPosition = UUID(7, 3)
    private val ceoPosition = UUID(7, 4)

    private val definition =
        Definition(start = "doa", steps = listOf(StepDef(id = "doa", type = StepType.DOA, name = "Authority", next = Definition.END)))

    private fun position(id: UUID) = Assignee(AssigneeKind.POSITION, positionId = id)

    private val twoThenOne =
        DoaMatch(
            listOf(DoaGroup(2, listOf(position(dmdPosition), position(amdPosition))), DoaGroup(1, listOf(position(ceoPosition)))),
            mapOf("doa" to mapOf("version" to 1, "rule" to 0)),
        )

    private fun transition(
        doa: DoaMatch,
        holders: Map<UUID, UUID> = mapOf(dmdPosition to dmd, amdPosition to amd, ceoPosition to ceo),
    ): Transition {
        var next = 100L
        val resolver =
            Resolver { assignee, _ ->
                Resolution(
                    listOfNotNull(holders[assignee.positionId]),
                    mapOf("positionId" to "${assignee.positionId}"),
                )
            }
        return Transition(
            TransitionContext({ UUID(1, next++) }, { null }, maxApprovers = 4, resolver = resolver, doa = { doa }),
            definition,
        )
    }

    private fun Decision.outcome(): Outcome = (this as Decision.Accepted).outcome

    private fun Outcome.taskOf(person: UUID): UUID = state.openTasks.first { it.assignee == person }.id

    @Test
    fun `groups activate in order, each with the approvals it needs`() {
        val transition = transition(twoThenOne)
        val started = transition.start(initiator, emptyList()).outcome()
        val first =
            started.state.round.steps
                .single()
        assertThat(first.doaGroup).isEqualTo(0)
        assertThat(first.needed).isEqualTo(2)
        assertThat(first.tasks.map { it.assignee }).containsExactly(dmd, amd)
        assertThat(first.resolution).containsEntry("group", 0).containsKey("doa")

        val one = transition.apply(started.state, Approve(dmd, started.taskOf(dmd))).outcome()
        assertThat(one.state.round.steps).hasSize(1)
        assertThat(one.state.currentAssignees).containsExactly(amd)

        val two = transition.apply(one.state, Approve(amd, one.taskOf(amd))).outcome()
        val second =
            two.state.round.steps
                .last()
        assertThat(second.doaGroup).isEqualTo(1)
        assertThat(second.tasks.map { it.assignee }).containsExactly(ceo)

        val done = transition.apply(two.state, Approve(ceo, two.taskOf(ceo))).outcome()
        assertThat(done.state.status).isEqualTo(InstanceStatus.APPROVED)
    }

    @Test
    fun `a group short of people holds a task for an administrator for each missing approval`() {
        val started = transition(twoThenOne, mapOf(dmdPosition to dmd)).start(initiator, emptyList()).outcome()
        val step =
            started.state.round.steps
                .single()
        assertThat(step.tasks.map { it.assignee }).containsExactly(dmd, null)
        assertThat(step.tasks.last().blocked).isEqualTo(BlockedReason.UNRESOLVED)
    }

    @Test
    fun `no rule matching leaves the step to an administrator, saying why`() {
        val none = DoaMatch(emptyList(), mapOf("reason" to DoaMatch.NO_RULE))
        val step =
            transition(none)
                .start(initiator, emptyList())
                .outcome()
                .state.round.steps
                .single()
        assertThat(step.tasks.single().blocked).isEqualTo(BlockedReason.UNRESOLVED)
        assertThat(step.resolution).containsEntry("reason", DoaMatch.NO_RULE)
    }

    @Test
    fun `nobody approves in two groups of one rule, and the initiator never does`() {
        val sameTwice =
            DoaMatch(
                listOf(DoaGroup(1, listOf(position(dmdPosition))), DoaGroup(1, listOf(position(dmdPosition), position(ceoPosition)))),
                emptyMap(),
            )
        val transition = transition(sameTwice, mapOf(dmdPosition to dmd, ceoPosition to initiator))
        val started = transition.start(initiator, emptyList()).outcome()
        val next = transition.apply(started.state, Approve(dmd, started.taskOf(dmd))).outcome()
        val second =
            next.state.round.steps
                .last()
        assertThat(second.tasks.single().blocked).isEqualTo(BlockedReason.UNRESOLVED)
        @Suppress("UNCHECKED_CAST")
        val excluded = second.resolution!!["excluded"] as List<Map<String, Any?>>
        assertThat(excluded.map { it["rule"] }).containsExactlyInAnyOrder("SOD-1", "SOD-2")
    }

    @Test
    fun `a group cannot be claimed, and a recusal that leaves it short resolves it again`() {
        val transition = transition(twoThenOne)
        val started = transition.start(initiator, emptyList()).outcome()
        val claim = transition.apply(started.state, Claim(dmd, started.taskOf(dmd)))
        assertThat((claim as Decision.Refused).reason).isEqualTo(Refusal.NOT_SHARED)

        val recused = transition.apply(started.state, Recuse(amd, started.taskOf(amd), "Related party")).outcome()
        val again =
            recused.state.round.steps
                .last()
        assertThat(again.id).isNotEqualTo(
            started.state.round.steps
                .single()
                .id,
        )
        assertThat(again.tasks.map { it.assignee }).containsExactly(dmd, null)
    }
}
