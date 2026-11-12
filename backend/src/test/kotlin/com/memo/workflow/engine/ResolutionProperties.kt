package com.memo.workflow.engine

import com.memo.workflow.BlockedReason
import com.memo.workflow.InstanceStatus
import com.memo.workflow.definition.Assignee
import com.memo.workflow.definition.AssigneeKind
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
 * Invariants 19 and 22 (design E04 §4) over random resolver answers: a chain of position steps, each resolving to
 * between none and four people (sometimes the initiator), approved by whoever may act and assigned by an administrator
 * when nobody was found. A step without holder never advances, nobody ever decides on their own item, and every
 * activated step records its resolution.
 */
class ResolutionProperties {
    private val initiator = UUID(0, 1)
    private val admin = UUID(0, 99)

    @Suppress("ktlint:standard:function-naming")
    @Property(tries = 300)
    fun `unresolved steps wait for an administrator, and every step records how it was resolved`(
        @ForAll("answers") answers: List<List<Int>>,
    ) {
        val positions = answers.indices.map { UUID(7, it.toLong()) }
        val people = answers.map { ids -> ids.map { if (it == 0) initiator else UUID(0, it.toLong() + 1) } }
        val definition =
            Definition(
                start = "s0",
                steps =
                    positions.mapIndexed { i, position ->
                        StepDef(
                            id = "s$i",
                            type = StepType.APPROVE,
                            assignee = Assignee(AssigneeKind.POSITION, positionId = position),
                            next = if (i == positions.lastIndex) Definition.END else "s${i + 1}",
                        )
                    },
            )
        var next = 1_000L
        val resolver = Resolver { assignee, _ -> Resolution(people[positions.indexOf(assignee.positionId)]) }
        val transition = Transition(TransitionContext({ UUID(1, next++) }, { null }, maxApprovers = 4, resolver = resolver), definition)
        var state = (transition.start(initiator, emptyList()) as Decision.Accepted).outcome.state
        var guard = 0
        while (state.status == InstanceStatus.RUNNING) {
            val step = checkNotNull(state.activeStep)
            assertThat(step.resolution).isNotNull()
            val open = state.openTasks
            assertThat(open.mapNotNull { it.assignee }).doesNotContain(initiator)
            val unresolved = open.filter { it.blocked == BlockedReason.UNRESOLVED }
            state =
                if (unresolved.isNotEmpty()) {
                    val task = unresolved.single()
                    // Someone new each time: nobody may hold two steps of one round (E01).
                    val standIn = UUID(5, guard.toLong())
                    assertThat(task.assignee).isNull()
                    assertThat(transition.apply(state, Approve(standIn, task.id))).isInstanceOf(Decision.Refused::class.java)
                    (transition.apply(state, Reassign(admin, task.id, standIn, "assigned")) as Decision.Accepted).outcome.state
                } else {
                    val task = open.first()
                    (transition.apply(state, Approve(task.assignee!!, task.id)) as Decision.Accepted).outcome.state
                }
            check(++guard < MAX_ACTIONS) { "the walk does not end" }
        }
        assertThat(state.status).isEqualTo(InstanceStatus.APPROVED)
    }

    @Provide
    fun answers(): Arbitrary<List<List<Int>>> =
        Arbitraries
            .integers()
            .between(0, 6)
            .list()
            .ofMaxSize(4)
            .map { it.distinct() }
            .list()
            .ofMinSize(1)
            .ofMaxSize(5)

    private companion object {
        const val MAX_ACTIONS = 50
    }
}
