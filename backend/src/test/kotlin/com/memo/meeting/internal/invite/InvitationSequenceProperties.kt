package com.memo.meeting.internal.invite

import net.jqwik.api.Arbitraries
import net.jqwik.api.Arbitrary
import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.Provide
import net.jqwik.api.Tuple
import org.assertj.core.api.Assertions.assertThat
import java.util.UUID

/**
 * The sequence rules of meeting invitations (test plan T2 `InvitationSequenceProperties`, ADR-0063): for random runs of
 * changes (time, place, link, title), people joining and leaving the audience, resends, sends and a cancellation, each
 * person's sequences never go down, rise with every message but a resend (which repeats the last), nothing follows a
 * meeting's CANCEL, and after a last send everyone holds what the meeting is: the invitable people the current version,
 * the others nothing, and with a cancelled meeting nobody holds the event.
 */
class InvitationSequenceProperties {
    private val people = (1..5).map { UUID(0, it.toLong()) }

    sealed interface Op

    data object Change : Op

    data class Join(
        val user: Int,
    ) : Op

    data class Leave(
        val user: Int,
    ) : Op

    data class Resend(
        val user: Int,
    ) : Op

    data object Send : Op

    data object Cancel : Op

    /** One simulated meeting: the invitation's sequence, who is invitable, what each person holds, and every message. */
    private inner class Run(
        initial: Set<UUID>,
    ) {
        var sequence = 0
        var sent: Int? = null
        var cancelled = false
        val invitable = initial.toMutableSet()
        val holdings = mutableMapOf<UUID, Holding>()
        val log = mutableMapOf<UUID, MutableList<PlannedMessage>>()

        fun apply(op: Op) {
            when (op) {
                Change -> {
                    if (!cancelled) sequence = InvitationPlan.afterChange(sequence, sent)
                }

                is Join -> {
                    if (!cancelled) invitable += people[op.user]
                }

                is Leave -> {
                    if (!cancelled) invitable -= people[op.user]
                }

                is Resend -> {
                    if (!cancelled) InvitationPlan.resend(sequence, holdings[people[op.user]])?.let(::deliver)
                }

                Send -> {
                    send()
                }

                Cancel -> {
                    if (!cancelled) {
                        sequence = InvitationPlan.afterChange(sequence, sent)
                        cancelled = true
                    }
                }
            }
        }

        fun send() {
            InvitationPlan.reconcile(sequence, cancelled, invitable, holdings).forEach(::deliver)
            sent = sequence
        }

        private fun deliver(message: PlannedMessage) {
            holdings[message.userId] = InvitationPlan.after(holdings[message.userId], message, sequence)
            log.getOrPut(message.userId) { mutableListOf() } += message
        }
    }

    @Property
    fun sequencesNeverGoBackAndEveryoneEndsHoldingTheMeeting(
        @ForAll("initial") initial: Set<Int>,
        @ForAll("ops") ops: List<Op>,
    ) {
        val run = Run(initial.map { people[it] }.toSet())
        run.send()
        ops.forEach(run::apply)
        run.send()

        run.log.forEach { (user, messages) ->
            messages.zipWithNext().forEach { (before, after) ->
                assertThat(before.kind).describedAs("nothing after a CANCEL for $user").isNotEqualTo(InvitationMessageKind.CANCEL)
                if (after.kind == InvitationMessageKind.RESEND) {
                    assertThat(after.sequence).describedAs("a resend repeats the last").isEqualTo(before.sequence)
                } else {
                    assertThat(after.sequence).describedAs("a new message rises").isGreaterThan(before.sequence)
                }
            }
            assertThat(messages.first().kind).describedAs("the first message invites").isEqualTo(InvitationMessageKind.INVITATION)
        }
        if (run.cancelled) {
            assertThat(run.holdings.values.map { it.state }).doesNotContain(InviteeState.INVITED)
        } else {
            val holding = run.holdings.values.filter { it.state == InviteeState.INVITED }
            assertThat(holding.map { it.userId }.toSet()).isEqualTo(run.invitable)
            assertThat(holding.map { it.heldSequence }.toSet()).allMatch { it == run.sequence }
        }
        // A last send changes nothing: the reconciler is idempotent.
        assertThat(InvitationPlan.reconcile(run.sequence, run.cancelled, run.invitable, run.holdings)).isEmpty()
    }

    @Property
    fun changesBetweenTwoSendsRaiseTheSequenceOnce(
        @ForAll("changes") changes: Int,
    ) {
        val run = Run(people.toSet())
        run.send()
        repeat(changes) { run.apply(Change) }
        run.send()
        assertThat(run.sequence).isEqualTo(1)
        run.log.values.forEach { messages ->
            assertThat(messages.map { it.kind to it.sequence }).containsExactly(
                InvitationMessageKind.INVITATION to 0,
                InvitationMessageKind.UPDATE to 1,
            )
        }
    }

    @Provide
    fun changes(): Arbitrary<Int> = Arbitraries.integers().between(1, 6)

    @Provide
    fun initial(): Arbitrary<Set<Int>> =
        Arbitraries
            .integers()
            .between(0, people.size - 1)
            .set()
            .ofMaxSize(people.size)

    @Provide
    fun ops(): Arbitrary<List<Op>> {
        val user = Arbitraries.integers().between(0, people.size - 1)
        val op: Arbitrary<Op> =
            Arbitraries.frequencyOf(
                Tuple.of(3, Arbitraries.just<Op>(Change)),
                Tuple.of(2, user.map<Op> { Join(it) }),
                Tuple.of(2, user.map<Op> { Leave(it) }),
                Tuple.of(1, user.map<Op> { Resend(it) }),
                Tuple.of(3, Arbitraries.just<Op>(Send)),
                Tuple.of(1, Arbitraries.just<Op>(Cancel)),
            )
        return op.list().ofMaxSize(30)
    }
}
