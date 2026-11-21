package com.memo.minutes.internal

import com.memo.forum.ForumRole
import com.memo.forum.Outcome
import com.memo.forum.OutcomeKind
import com.memo.forum.QuorumRule
import com.memo.meeting.QuorumSnapshotInputs
import com.memo.meeting.QuorumSnapshotPresence
import com.memo.meeting.QuorumSnapshotSeat
import net.jqwik.api.Arbitraries
import net.jqwik.api.Arbitrary
import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.Provide
import org.assertj.core.api.Assertions.assertThat
import java.time.Instant
import java.util.UUID

/**
 * For random snapshots, people named and counts (test plan E06 T3, property): a decision the rules accept never names a
 * person recused from the item, absent, stepped out or without a voting seat, never counts more votes than the voting
 * members present and not recused, and its "took no part" never names someone who may vote.
 */
class DecisionPeopleProperties {
    /** One random meeting for one item: seats with a state each, and a decision naming some of the people. */
    data class Case(
        val inputs: QuorumSnapshotInputs,
        val people: List<UUID>,
        val dissent: List<UUID>,
        val abstentions: List<UUID>,
        val votes: Triple<Int?, Int?, Int?>,
    )

    @Property(tries = 500)
    fun anAcceptedDecisionNamesOnlyVotersAndCountsNoMoreThanThem(
        @ForAll("cases") case: Case,
    ) {
        val content =
            DecisionContent(
                Outcome(OutcomeKind.APPROVED, "Approved", true),
                "Resolved",
                emptyList(),
                null,
                null,
                null,
                null,
                VoteMode.VOTE,
                case.votes.first,
                case.votes.second,
                case.votes.third,
                case.dissent.map { Dissent(it) },
                case.abstentions,
            )
        val voters = DecisionRules.voters(case.inputs)
        val excluded = case.inputs.recused.toSet() + case.inputs.steppedOut
        assertThat(voters.intersect(excluded)).isEmpty()
        assertThat(DecisionRules.tookNoPart(case.inputs).intersect(voters)).isEmpty()
        if (DecisionRules.people(content, case.inputs).isEmpty()) {
            assertThat(case.dissent + case.abstentions).allMatch { it in voters }
            assertThat(listOfNotNull(case.votes.first, case.votes.second, case.votes.third).sum()).isLessThanOrEqualTo(voters.size)
        } else {
            val named = case.dissent + case.abstentions
            val counted = listOfNotNull(case.votes.first, case.votes.second, case.votes.third).sum()
            val tooMany =
                case.votes.second?.let { case.dissent.size > it } == true || case.votes.third?.let { case.abstentions.size > it } == true
            assertThat(named.any { it !in voters } || counted > voters.size || tooMany).isTrue()
        }
    }

    @Provide
    fun cases(): Arbitrary<Case> =
        Arbitraries.integers().between(1, 12).flatMap { size ->
            Arbitraries.integers().between(0, STATES - 1).list().ofSize(size).flatMap { states ->
                val people = (0 until size).map { UUID.randomUUID() }
                val seats = people.map { UUID.randomUUID() }
                val inputs = inputs(people, seats, states)
                val pick =
                    Arbitraries
                        .of(people + UUID.randomUUID())
                        .set()
                        .ofMaxSize(4)
                        .map { it.toList() }
                val count = Arbitraries.integers().between(0, size + 1).injectNull(0.3)
                pick.flatMap { dissent ->
                    pick.map { it - dissent.toSet() }.flatMap { abstentions ->
                        count.list().ofSize(3).map { c -> Case(inputs, people, dissent, abstentions, Triple(c[0], c[1], c[2])) }
                    }
                }
            }
        }

    /** State per person: 0 present, 1 present recused, 2 stepped out, 3 absent, 4 present without a vote. */
    private fun inputs(
        people: List<UUID>,
        seats: List<UUID>,
        states: List<Int>,
    ): QuorumSnapshotInputs =
        QuorumSnapshotInputs(
            meetingId = UUID(1, 1),
            itemId = UUID(2, 2),
            at = Instant.parse("2026-12-12T05:00:00Z"),
            typeId = UUID(3, 3),
            typeVersion = 1,
            rule = QuorumRule(),
            roster =
                people.indices.map { i ->
                    QuorumSnapshotSeat(seats[i], people[i], ForumRole.MEMBER, states[i] != NO_VOTE, emptyList())
                },
            presence =
                people.indices
                    .filter { states[it] == PRESENT || states[it] == RECUSED || states[it] == NO_VOTE }
                    .map { i -> QuorumSnapshotPresence(seats[i], people[i], false, false, states[i] == RECUSED) },
            recused = people.indices.filter { states[it] == RECUSED }.map { people[it] },
            steppedOut = people.indices.filter { states[it] == STEPPED_OUT }.map { people[it] },
        )

    private companion object {
        const val PRESENT = 0
        const val RECUSED = 1
        const val STEPPED_OUT = 2
        const val NO_VOTE = 4
        const val STATES = 5
    }
}
