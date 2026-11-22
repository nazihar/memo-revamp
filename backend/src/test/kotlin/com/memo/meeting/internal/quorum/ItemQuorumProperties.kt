package com.memo.meeting.internal.quorum

import com.memo.forum.ForumRole
import com.memo.forum.ForumSeat
import com.memo.forum.Quorum
import com.memo.forum.QuorumComposition
import com.memo.forum.QuorumCounts
import com.memo.forum.QuorumResult
import com.memo.forum.QuorumRule
import com.memo.forum.QuorumThreshold
import com.memo.forum.ThresholdKind
import com.memo.forum.UnmetCondition
import com.memo.forum.UnmetKind
import com.memo.meeting.DeclarationState
import com.memo.meeting.QuorumSnapshotInputs
import net.jqwik.api.Arbitraries
import net.jqwik.api.Arbitrary
import net.jqwik.api.Combinators
import net.jqwik.api.Example
import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.Provide
import org.assertj.core.api.Assertions.assertThat
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * An item's quorum over random rules × rosters × attendance × declarations × alternates × stepping out (test plan E05 T5
 * `ItemQuorumProperties`, ADR-0064, ADR-0057):
 *  - with `recusedCount` false, a recused member and the alternate of a recused member never count for the item;
 *  - with `recusedCount` true, the member counts and their alternate still does not stand in;
 *  - a TAKING_PART or WITHDRAWN declaration changes nothing;
 *  - someone out of the room for the item never counts for it, and back in the room never makes it less quorate but under a
 *    composition majority, which is of those counted present;
 *  - live equals recorded: a snapshot's inputs re-evaluate to the live result, and their SHA-256 does not depend on the
 *    order of the lists but changes with any input.
 */
class ItemQuorumProperties {
    private val item: UUID = UUID(5, 5)
    private val meetingId: UUID = UUID(9, 9)
    private val at: Instant = Instant.parse("2026-12-12T05:00:00Z")

    @Property
    fun withoutRecusedCountARecusedMemberAndTheirAlternateNeverCount(
        @ForAll("meetings") meeting: Meeting,
        @ForAll("rules") base: QuorumRule,
    ) {
        val rule = base.copy(recusedCount = false)
        val recused = meeting.recused()
        val seats = PresenceBuilder.build(meeting.roster, meeting.standIns, meeting.present, item, recused)
        val alternates = seats.filter { it.byAlternate }
        assertThat(alternates.map { it.holderId }.intersect(recused)).describedAs("no alternate for a recused member").isEmpty()
        assertThat(alternates.map { it.userId }.intersect(recused)).describedAs("no recused alternate").isEmpty()
        // Leaving the recused holders out entirely gives the same result: they count for nothing.
        val without = seats.filterNot { it.holderId in recused && !it.byAlternate }
        assertThat(evaluate(rule, meeting.roster, seats)).isEqualTo(evaluate(rule, meeting.roster, without))
    }

    @Property
    fun withRecusedCountTheMemberCountsAndTheirAlternateStillDoesNotStandIn(
        @ForAll("meetings") meeting: Meeting,
        @ForAll("rules") base: QuorumRule,
    ) {
        val rule = base.copy(recusedCount = true)
        val recused = meeting.recused()
        val seats = PresenceBuilder.build(meeting.roster, meeting.standIns, meeting.present, item, recused)
        assertThat(seats.filter { it.byAlternate }.map { it.holderId }.intersect(recused)).isEmpty()
        // A recused holder present counts as if not recused.
        val cleared = seats.map { it.copy(recused = false) }
        assertThat(evaluate(rule, meeting.roster, seats)).isEqualTo(evaluate(rule, meeting.roster, cleared))
    }

    @Property
    fun aTakingPartOrWithdrawnDeclarationChangesNothing(
        @ForAll("meetings") meeting: Meeting,
        @ForAll("rules") rule: QuorumRule,
    ) {
        val effective = meeting.copy(declarations = meeting.declarations.filterValues { it.effective })
        assertThat(meeting.recused()).isEqualTo(effective.recused())
        assertThat(result(rule, meeting)).isEqualTo(result(rule, effective))
    }

    @Property
    fun someoneOutOfTheRoomForTheItemNeverCountsForIt(
        @ForAll("meetings") meeting: Meeting,
        @ForAll("rules") rule: QuorumRule,
    ) {
        val seats = PresenceBuilder.build(meeting.roster, meeting.standIns, meeting.present, item, meeting.recused())
        val out = meeting.present.filterValues { it.steppedOutOf == item }.keys
        assertThat(seats.map { it.userId }.intersect(out)).isEmpty()
        // Back in the room, they never make the item less quorate: one seat per person, and online attendance counting (an
        // online member back in the room for the item silences their alternate, PresenceBuilderProperties' example).
        if (meeting.roster.groupBy { it.userId }.any { it.value.size > 1 } || !rule.remoteCounts) return
        // A composition majority is of those counted present (ADR-0057), so a member without the attribute coming back can
        // break it (aMemberWithoutTheAttributeBackInTheRoomCanBreakACompositionMajority); every other condition is monotonic.
        val monotonic = rule.copy(composition = rule.composition.filterNot { it.majority })
        val back = meeting.copy(present = meeting.present.mapValues { it.value.copy(steppedOutOf = null) })
        if (result(monotonic, meeting).quorate) assertThat(result(monotonic, back).quorate).isTrue()
    }

    /**
     * The shrunk counterexample of `someoneOutOfTheRoomForTheItemNeverCountsForIt` (seed -1377197321439722442, CI run
     * 45039708200): four voting chairs, two of them independent; the third holder, not independent, steps out of the item.
     * Without them, 2 of the 3 present are independent, a majority; back in the room, 2 of 4 are not. That is the rule as
     * ADR-0057 writes it ("a majority of those counted present") and as `QuorumTest` pins it, not a miscount.
     */
    @Example
    fun aMemberWithoutTheAttributeBackInTheRoomCanBreakACompositionMajority() {
        fun seat(
            i: Long,
            holder: Long,
            independent: Boolean,
        ) = ForumSeat(
            memberId = UUID(1, i),
            forumId = UUID(9, 1),
            userId = UUID(2, holder),
            positionId = null,
            role = ForumRole.CHAIR,
            voting = true,
            attributes = if (independent) setOf("independent") else setOf(),
            alternateUserId = UUID(2, 0),
            startsOn = LocalDate.of(2026, 1, 1),
            termEndsOn = null,
            termNumber = 1,
            until = null,
        )
        val roster = listOf(seat(0, 1, false), seat(1, 2, true), seat(2, 0, false), seat(3, 3, true))
        val meeting =
            Meeting(
                roster = roster,
                standIns = listOf(0L, 1L, 3L).associate { UUID(1, it) to UUID(2, 0) },
                present =
                    listOf(1L, 2L, 3L).associate { UUID(2, it) to PersonPresence(remote = true) } +
                        (UUID(2, 0) to PersonPresence(remote = true, steppedOutOf = item)),
                declarations = emptyMap(),
            )
        val rule =
            QuorumRule(
                threshold = QuorumThreshold(ThresholdKind.MAJORITY),
                minimum = 1,
                composition = listOf(QuorumComposition("independent", majority = true)),
                recusedCount = true,
            )
        val back = meeting.copy(present = meeting.present.mapValues { it.value.copy(steppedOutOf = null) })

        val out = live(rule, meeting)
        assertThat(out.steppedOut).containsExactly(UUID(2, 0))
        assertThat(out.seats.map { it.userId }).doesNotContain(UUID(2, 0))
        assertThat(out.result.present).isEqualTo(3)
        assertThat(out.result.quorate).isTrue()

        val returned = result(rule, back)
        assertThat(returned.present).isEqualTo(4)
        assertThat(returned.quorate).isFalse()
        assertThat(returned.unmet)
            .containsExactly(UnmetCondition(UnmetKind.COMPOSITION, 3, 2, attribute = "independent", majority = true))
        // The rest of the rule is monotonic: without the majority condition, the item stays quorate.
        val monotonic = rule.copy(composition = emptyList())
        assertThat(result(monotonic, meeting).quorate).isTrue()
        assertThat(result(monotonic, back).quorate).isTrue()
    }

    @Property
    fun theRecordedInputsReEvaluateToTheLiveResultWhateverTheirOrder(
        @ForAll("meetings") meeting: Meeting,
        @ForAll("rules") rule: QuorumRule,
    ) {
        val live = live(rule, meeting)
        val inputs = QuorumSnapshotInputs.of(meetingId, live, UUID(7, 7), 3, at)
        assertThat(inputs.evaluate()).describedAs("live equals recorded").isEqualTo(live.result)
        val shuffled =
            inputs.copy(
                roster = inputs.roster.reversed().map { it.copy(attributes = it.attributes.reversed()) },
                presence = inputs.presence.reversed(),
                recused = inputs.recused.reversed(),
                steppedOut = inputs.steppedOut.reversed(),
            )
        assertThat(shuffled.sha256()).isEqualTo(inputs.sha256())
        assertThat(shuffled.evaluate()).isEqualTo(inputs.evaluate())
        assertThat(inputs.sha256()).matches("[0-9a-f]{64}")
        // Any change of an input changes the hash.
        assertThat(inputs.copy(at = at.plusSeconds(1)).sha256()).isNotEqualTo(inputs.sha256())
        assertThat(inputs.copy(rule = rule.copy(minimum = rule.minimum + 1)).sha256()).isNotEqualTo(inputs.sha256())
        inputs.presence.firstOrNull()?.let { first ->
            val flipped = inputs.copy(presence = listOf(first.copy(remote = !first.remote)) + inputs.presence.drop(1))
            assertThat(flipped.sha256()).isNotEqualTo(inputs.sha256())
        }
    }

    private fun live(
        rule: QuorumRule,
        meeting: Meeting,
    ): QuorumAt {
        val recused = meeting.recused()
        val seats = PresenceBuilder.build(meeting.roster, meeting.standIns, meeting.present, item, recused)
        val out = meeting.present.filterValues { it.steppedOutOf == item }.keys
        return QuorumAt(at, item, rule, meeting.roster, seats, recused, out, evaluate(rule, meeting.roster, seats))
    }

    private fun result(
        rule: QuorumRule,
        meeting: Meeting,
    ): QuorumResult = live(rule, meeting).result

    private fun evaluate(
        rule: QuorumRule,
        roster: List<ForumSeat>,
        seats: List<SeatPresence>,
    ): QuorumResult = Quorum.evaluate(rule, PresenceBuilder.seats(roster), PresenceBuilder.attendance(seats))

    data class Meeting(
        val roster: List<ForumSeat>,
        /** The alternate standing in, by seat. */
        val standIns: Map<UUID, UUID>,
        val present: Map<UUID, PersonPresence>,
        /** Each person's declaration on the item, if any. */
        val declarations: Map<UUID, DeclarationState>,
    ) {
        /**
         * Who is recused from the item, as `Recusals` answers it: an effective declaration (RECUSED or AWAITING_RULING),
         * and the alternate standing in for a member who holds one.
         */
        fun recused(): Set<UUID> {
            val declarants = declarations.filterValues { it.effective }.keys
            val holders = roster.associate { it.memberId to it.userId }
            val inherited = standIns.filter { (seat, _) -> holders[seat] in declarants }.values
            return declarants + inherited
        }
    }

    @Provide
    fun rules(): Arbitrary<QuorumRule> =
        Combinators
            .combine(
                Arbitraries.integers().between(1, 4),
                Arbitraries.of(true, false),
                Arbitraries.of(true, false),
                Arbitraries.of(QuorumCounts.VOTING, QuorumCounts.ALL),
                Arbitraries.of(emptyList(), listOf(listOf(ForumRole.CHAIR, ForumRole.VICE_CHAIR))),
                Arbitraries.of(
                    emptyList(),
                    listOf(QuorumComposition("independent", atLeast = 2)),
                    listOf(QuorumComposition("independent", majority = true)),
                ),
                Arbitraries.of(true, false),
                Arbitraries.of(ThresholdKind.MAJORITY, ThresholdKind.FRACTION, ThresholdKind.COUNT),
            ).`as` { minimum, alternates, remote, counts, required, composition, recusedCount, threshold ->
                QuorumRule(
                    counts = counts,
                    threshold = QuorumThreshold(threshold, numerator = 2, denominator = 3, count = 3),
                    minimum = minimum,
                    required = required,
                    composition = composition,
                    alternatesCount = alternates,
                    remoteCounts = remote,
                    recusedCount = recusedCount,
                )
            }

    @Provide
    fun meetings(): Arbitrary<Meeting> {
        val seat =
            Combinators
                .combine(
                    Arbitraries.of(*ForumRole.entries.toTypedArray()),
                    Arbitraries.of(true, false),
                    Arbitraries.of(setOf(), setOf("independent")),
                    Arbitraries.integers().between(0, 7),
                    Arbitraries.integers().between(-1, 11),
                ).`as` { role, voting, attributes, holder, alternate -> Generated(role, voting, attributes, holder, alternate) }
        return seat.list().ofMinSize(1).ofMaxSize(9).flatMap { generated ->
            val roster =
                generated.mapIndexed { i, g ->
                    ForumSeat(
                        memberId = UUID(1, i.toLong()),
                        forumId = UUID(9, 1),
                        userId = UUID(2, g.holder.toLong()),
                        positionId = null,
                        role = g.role,
                        voting = g.voting,
                        attributes = g.attributes,
                        alternateUserId = g.alternate.takeIf { it >= 0 }?.let { UUID(2, it.toLong()) },
                        startsOn = LocalDate.of(2026, 1, 1),
                        termEndsOn = null,
                        termNumber = 1,
                        until = null,
                    )
                }
            val standIns = roster.filter { it.alternateUserId != null && it.memberId.leastSignificantBits % 3 != 2L }
            val people = (roster.map { it.userId } + standIns.mapNotNull { it.alternateUserId }).distinct()
            val presence =
                Combinators
                    .combine(Arbitraries.of(true, false), Arbitraries.of(null, item, UUID(5, 6)))
                    .`as` { remote, out -> PersonPresence(remote, out) }
                    .injectNull(0.3)
            val declaration = Arbitraries.of(*DeclarationState.entries.toTypedArray()).injectNull(0.6)
            Combinators
                .combine(presence.list().ofSize(people.size), declaration.list().ofSize(people.size))
                .`as` { presences, declared ->
                    Meeting(
                        roster = roster,
                        standIns = standIns.associate { it.memberId to checkNotNull(it.alternateUserId) },
                        present = people.zip(presences).mapNotNull { (p, presence) -> presence?.let { p to it } }.toMap(),
                        declarations = people.zip(declared).mapNotNull { (p, state) -> state?.let { p to it } }.toMap(),
                    )
                }
        }
    }

    data class Generated(
        val role: ForumRole,
        val voting: Boolean,
        val attributes: Set<String>,
        val holder: Int,
        val alternate: Int,
    )
}
