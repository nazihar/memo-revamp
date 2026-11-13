package com.memo.workflow.engine

import com.memo.workflow.ActionKind
import com.memo.workflow.BlockedReason
import com.memo.workflow.InstanceStatus
import net.jqwik.api.Arbitraries
import net.jqwik.api.Arbitrary
import net.jqwik.api.Combinators
import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.Provide
import net.jqwik.api.Tuple
import net.jqwik.api.statistics.Statistics
import org.assertj.core.api.Assertions.assertThat
import java.util.UUID
import java.util.function.Predicate

/**
 * Random action sequences by random people never break the workflow invariants (design E01 §2, TESTING_STRATEGY §2):
 * only the assignee of an open, unblocked task acts; an instance is approved only after every step of the current round
 * was approved; earlier rounds never count; terminal states accept nothing; one person holds at most one step per round.
 */
class WorkflowInvariantProperties {
    private val initiator = UUID(0, 0)
    private val people = (1L..6L).map { UUID(0, it) }
    private val admin = UUID(0, 99)

    /** Person 6 is never eligible, so blocked tasks and their reassignment are exercised. */
    private val ineligible = people.last()

    @Suppress("ktlint:standard:function-naming")
    @Property(tries = 1_000)
    fun `random action sequences never break the invariants`(
        @ForAll("routes") route: List<Int>,
        @ForAll("scripts") script: List<Move>,
    ) {
        var nextId = 1_000L
        val transition =
            Transition(
                TransitionContext({ UUID(1, nextId++) }, { if (it == ineligible) BlockedReason.INACTIVE else null }, maxApprovers = 4),
            )
        var state = (transition.start(initiator, route.map { people[it] }) as Decision.Accepted).outcome.state
        assertInvariants(state)

        for (move in script) {
            val command = move.command(state)
            when (val decision = transition.apply(state, command)) {
                is Decision.Accepted -> {
                    assertAcceptedWasAllowed(state, command, decision.outcome)
                    state = decision.outcome.state
                }

                is Decision.Refused -> {}
            }
            assertInvariants(state)
        }
        Statistics.label("final status").collect(state.status)
        Statistics.label("rounds reached").collect(state.round.number.coerceAtMost(2))
        Statistics.label("final status").coverage { checker ->
            // Cancellation is an administrator's command with its own tests (E03 T6); random scripts here never cancel.
            (InstanceStatus.entries - InstanceStatus.CANCELLED).forEach { status ->
                checker.check(status).count(
                    Predicate { n: Int ->
                        n >=
                            MIN_CASES
                    },
                )
            }
        }
        // Random scripts rarely chain several returns; many rounds are the next property's job.
        Statistics.label("rounds reached").coverage { checker -> checker.check(2).count(Predicate { n: Int -> n >= MIN_CASES }) }
    }

    @Suppress("ktlint:standard:function-naming")
    @Property(tries = 300)
    fun `only the approvals of the last round count, however many rounds there were`(
        @ForAll("routes") firstRoute: List<Int>,
        @ForAll("cycles") cycles: List<Cycle>,
    ) {
        var nextId = 1_000L
        val transition = Transition(TransitionContext({ UUID(1, nextId++) }, { null }, maxApprovers = 4))
        var state = (transition.start(initiator, firstRoute.map { people[it] }) as Decision.Accepted).outcome.state

        for ((round, cycle) in cycles.withIndex()) {
            repeat(cycle.approvals.coerceAtMost(state.round.steps.size - 1)) { state = act(transition, state, ::approveCurrent) }
            state = act(transition, state) { Return(it.openTasks.single().assignee, it.openTasks.single().id, "rework") }
            assertThat(state.status).isEqualTo(InstanceStatus.RETURNED)
            state = act(transition, state) { Resubmit(initiator, cycle.route.map { index -> people[index] }) }
            assertThat(state.round.number).isEqualTo(round + 2)
            assertThat(
                state.round.steps
                    .flatMap { it.tasks }
                    .none { it.outcome == ActionKind.APPROVE },
            ).isTrue()
        }

        val steps = state.round.steps.size
        repeat(steps - 1) {
            state = act(transition, state, ::approveCurrent)
            assertThat(state.status).isEqualTo(InstanceStatus.RUNNING)
        }
        state = act(transition, state, ::approveCurrent)
        assertThat(state.status).isEqualTo(InstanceStatus.APPROVED)
        assertThat(state.round.number).isEqualTo(cycles.size + 1)
        assertThat(
            state.round.steps
                .flatMap { it.tasks }
                .count { it.outcome == ActionKind.APPROVE },
        ).isEqualTo(steps)
    }

    private fun approveCurrent(state: InstanceState): Command = Approve(state.openTasks.single().assignee, state.openTasks.single().id)

    private fun act(
        transition: Transition,
        state: InstanceState,
        command: (InstanceState) -> Command,
    ): InstanceState {
        val decision = transition.apply(state, command(state))
        assertThat(decision).isInstanceOf(Decision.Accepted::class.java)
        return (decision as Decision.Accepted).outcome.state
    }

    @Provide
    fun cycles(): Arbitrary<List<Cycle>> =
        Combinators
            .combine(Arbitraries.integers().between(0, 3), routes())
            .`as` { approvals, route -> Cycle(approvals, route) }
            .list()
            .ofMinSize(1)
            .ofMaxSize(5)

    private fun assertAcceptedWasAllowed(
        before: InstanceState,
        command: Command,
        outcome: Outcome,
    ) {
        assertThat(before.status.terminal).describedAs("terminal states accept nothing").isFalse()
        val after = outcome.state
        when (command) {
            is Approve, is Return, is Reject -> {
                val task = before.openTasks.single()
                assertThat(command.actor).describedAs("only the assignee acts").isEqualTo(task.assignee)
                assertThat(task.blocked).describedAs("blocked tasks wait for reassignment").isNull()
            }

            is Withdraw, is Resubmit -> {
                assertThat(command.actor).isEqualTo(before.initiator)
            }

            is Reassign -> {
                assertThat(after.currentAssignees).containsExactly(command.newAssignee)
            }

            is SendBack, is Forward, is Delegate, is Ask, is Answer, is Refer, is GiveOpinion -> {
                throw AssertionError("the E01 scripts never generate E03 hand-on commands")
            }
        }
        if (command is Resubmit) {
            assertThat(after.round.number).isEqualTo(before.round.number + 1)
            assertThat(
                after.round.steps
                    .flatMap { it.tasks }
                    .none { it.outcome == ActionKind.APPROVE },
            ).describedAs("earlier rounds never count")
                .isTrue()
        } else {
            assertThat(after.round.number).isEqualTo(before.round.number)
        }
        if (after.status == InstanceStatus.APPROVED) {
            assertThat(command).isInstanceOf(Approve::class.java)
            assertThat(
                before.round.steps
                    .last()
                    .state,
            ).isEqualTo(StepState.ACTIVE)
        }
        assertThat(outcome.action.round).isEqualTo(after.round.number)
    }

    private fun assertInvariants(state: InstanceState) {
        val round = state.round
        assertThat(state.status == InstanceStatus.RUNNING).isEqualTo(round.outcome == RoundOutcome.OPEN)
        assertThat(round.steps.map { it.assignee }).doesNotHaveDuplicates().doesNotContain(state.initiator)
        assertThat(state.currentAssignees).isEqualTo(state.openTasks.map { it.assignee })
        round.steps.forEach { step ->
            assertThat(step.tasks.count { it.state == TaskState.OPEN }).isLessThanOrEqualTo(1)
            step.tasks.filter { it.state == TaskState.OPEN }.forEach { assertThat(it.assignee).isEqualTo(step.assignee) }
        }
        state.openTasks.forEach { assertThat(it.blocked != null).isEqualTo(it.assignee == ineligible) }

        if (state.status == InstanceStatus.RUNNING) {
            val active = round.steps.indexOfFirst { it.state == StepState.ACTIVE }
            assertThat(round.steps.count { it.state == StepState.ACTIVE }).isEqualTo(1)
            assertThat(state.openTasks).hasSize(1)
            assertThat(round.steps.take(active).map { it.state }).allMatch { it == StepState.DONE }
            assertThat(round.steps.drop(active + 1).map { it.state }).allMatch { it == StepState.PENDING }
            assertThat(round.steps.take(active).map { approvedBy(it) }).doesNotContainNull()
        } else {
            assertThat(state.openTasks).isEmpty()
            assertThat(state.activeStep).isNull()
        }
        if (state.status == InstanceStatus.APPROVED) {
            assertThat(round.steps).allMatch { it.state == StepState.DONE && approvedBy(it) == it.assignee }
        }
    }

    private fun approvedBy(step: Step): UUID? =
        step.tasks.lastOrNull { it.state == TaskState.COMPLETED && it.outcome == ActionKind.APPROVE }?.assignee

    @Provide
    fun routes(): Arbitrary<List<Int>> =
        Arbitraries
            .integers()
            .between(0, people.size - 1)
            .list()
            .ofMinSize(1)
            .ofMaxSize(4)
            .uniqueElements()

    @Provide
    fun scripts(): Arbitrary<List<Move>> =
        Combinators
            .combine(
                // Approve, return and resubmit dominate; withdraw and reject end a script, so they are rarer.
                Arbitraries.frequencyOf(
                    Tuple.of(4, Arbitraries.just(0)),
                    Tuple.of(3, Arbitraries.just(1)),
                    Tuple.of(1, Arbitraries.just(2)),
                    Tuple.of(1, Arbitraries.just(3)),
                    Tuple.of(3, Arbitraries.just(4)),
                    Tuple.of(1, Arbitraries.just(5)),
                ),
                // Half biased toward the right person, a written remark and a valid route, so returns and later rounds
                // happen often; the other half stays random, so refusals are exercised too.
                Arbitraries.oneOf(Arbitraries.just(people.size + 1), Arbitraries.integers().between(0, people.size + 2)),
                Arbitraries.oneOf(Arbitraries.just(2), Arbitraries.integers().between(0, 2)),
                Arbitraries.oneOf(
                    routes(),
                    Arbitraries
                        .integers()
                        .between(0, people.size - 1)
                        .list()
                        .ofMaxSize(5),
                ),
                Arbitraries.integers().between(0, people.size - 1),
                Arbitraries.frequency(Tuple.of(4, true), Tuple.of(1, false)),
            ).`as` { kind, actor, remark, route, target, knownTask -> Move(kind, actor, remark, route, target, knownTask) }
            .list()
            .ofMaxSize(30)

    /** Interprets a [Move] against the current state, so the script stays meaningful as the instance changes. */
    private fun Move.command(state: InstanceState): Command {
        val task = state.openTasks.firstOrNull()
        val taskId = if (knownTask && task != null) task.id else UUID(2, target.toLong())
        val text = listOf(null, " ", "a remark")[remark]
        return when (kind) {
            0 -> Approve(who(state, task?.assignee), taskId, text)
            1 -> Return(who(state, task?.assignee), taskId, text)
            2 -> Reject(who(state, task?.assignee), taskId, text)
            3 -> Withdraw(who(state, state.initiator), text)
            4 -> Resubmit(who(state, state.initiator), route.map { people[it] }, text)
            else -> Reassign(admin, taskId, people[target], text)
        }
    }

    /** `people.size + 1` is "the right person" (the open task's assignee, or the initiator), so valid actions happen often. */
    private fun Move.who(
        state: InstanceState,
        right: UUID?,
    ): UUID =
        when (actor) {
            people.size + 1 -> right ?: state.initiator
            people.size + 2 -> admin
            people.size -> state.initiator
            else -> people[actor]
        }

    private companion object {
        /** Each ending and the third round must occur at least this often in 1,000 tries, or the scripts test too little. */
        const val MIN_CASES = 10
    }
}

/** One scripted step: [kind] picks the command, [remark] 0 none, 1 blank, 2 text; [knownTask] false names a random task. */
data class Move(
    val kind: Int,
    val actor: Int,
    val remark: Int,
    val route: List<Int>,
    val target: Int,
    val knownTask: Boolean,
)

/** One return-and-resubmit cycle: approvals given before the return, then the route of the next round. */
data class Cycle(
    val approvals: Int,
    val route: List<Int>,
)
