package com.memo.forum

import net.jqwik.api.Arbitraries
import net.jqwik.api.Arbitrary
import net.jqwik.api.Combinators
import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.Provide
import net.jqwik.api.constraints.IntRange
import org.assertj.core.api.Assertions.assertThat
import java.math.BigInteger
import java.util.UUID

/** ADR-0057: the quorum evaluator's arithmetic and invariants over random rosters, rules and attendance. */
class QuorumProperties {
    @Property
    fun majorityIsMoreThanHalf(
        @ForAll @IntRange(min = 0, max = 500) n: Int,
    ) {
        val needed = Quorum.threshold(QuorumThreshold(ThresholdKind.MAJORITY), n)
        assertThat(needed * 2).isGreaterThan(n)
        assertThat((needed - 1) * 2).isLessThanOrEqualTo(n)
    }

    @Property
    fun fractionIsTheExactFractionRoundedUp(
        @ForAll @IntRange(min = 0, max = 500) n: Int,
        @ForAll @IntRange(min = 1, max = 10) denominator: Int,
        @ForAll @IntRange(min = 1, max = 10) numeratorSeed: Int,
    ) {
        val numerator = (numeratorSeed - 1) % denominator + 1
        val needed = Quorum.threshold(QuorumThreshold(ThresholdKind.FRACTION, numerator, denominator), n)
        // needed is the smallest integer with needed * denominator >= n * numerator.
        val product = BigInteger.valueOf(n.toLong() * numerator)
        assertThat(BigInteger.valueOf(needed.toLong() * denominator)).isGreaterThanOrEqualTo(product)
        assertThat(BigInteger.valueOf((needed - 1L) * denominator)).isLessThan(product.max(BigInteger.ONE))
    }

    @Property
    fun neededNeverFallsAsTheBasisGrows(
        @ForAll("thresholds") threshold: QuorumThreshold,
        @ForAll @IntRange(min = 0, max = 200) n: Int,
    ) {
        assertThat(Quorum.threshold(threshold, n + 1)).isGreaterThanOrEqualTo(Quorum.threshold(threshold, n))
    }

    @Property
    fun oneMoreAttendeeNeverBreaksQuorumWithoutAMajorityCondition(
        @ForAll("rules") rule: QuorumRule,
        @ForAll("meetings") meeting: Meeting,
    ) {
        val before = Quorum.evaluate(rule, meeting.roster, meeting.attendance)
        val absent = meeting.roster.firstOrNull { it.memberId !in meeting.attendance } ?: return
        val after = Quorum.evaluate(rule, meeting.roster, meeting.attendance + (absent.memberId to QuorumPresence()))
        if (before.quorate) assertThat(after.quorate).isTrue()
        assertThat(after.present).isGreaterThanOrEqualTo(before.present)
    }

    @Property
    fun resultDoesNotDependOnRosterOrder(
        @ForAll("rules") rule: QuorumRule,
        @ForAll("meetings") meeting: Meeting,
    ) {
        val forward = Quorum.evaluate(rule, meeting.roster, meeting.attendance)
        val backward = Quorum.evaluate(rule, meeting.roster.reversed(), meeting.attendance)
        assertThat(backward.copy(unmet = backward.unmet.sortedBy { it.toString() }))
            .isEqualTo(forward.copy(unmet = forward.unmet.sortedBy { it.toString() }))
    }

    @Property
    fun quorateExactlyWhenNothingIsUnmet(
        @ForAll("rules") rule: QuorumRule,
        @ForAll("meetings") meeting: Meeting,
    ) {
        val result = Quorum.evaluate(rule, meeting.roster, meeting.attendance)
        assertThat(result.quorate).isEqualTo(result.unmet.isEmpty())
        assertThat(result.present).isLessThanOrEqualTo(meeting.roster.size)
        assertThat(result.needed).isGreaterThanOrEqualTo(rule.minimum)
    }

    data class Meeting(
        val roster: List<QuorumSeat>,
        val attendance: Map<UUID, QuorumPresence>,
    )

    @Provide
    fun thresholds(): Arbitrary<QuorumThreshold> =
        Arbitraries.oneOf(
            Arbitraries.just(QuorumThreshold(ThresholdKind.MAJORITY)),
            Arbitraries.integers().between(1, 10).flatMap { d ->
                Arbitraries.integers().between(1, d).map { n -> QuorumThreshold(ThresholdKind.FRACTION, n, d) }
            },
            Arbitraries.integers().between(1, 20).map { QuorumThreshold(ThresholdKind.COUNT, count = it) },
        )

    @Provide
    fun rules(): Arbitrary<QuorumRule> {
        val roles =
            Arbitraries
                .of(*ForumRole.entries.toTypedArray())
                .set()
                .ofMinSize(1)
                .ofMaxSize(2)
                .map { it.toList() }
        val composition =
            Arbitraries
                .of("independent", "management")
                .flatMap { a -> Arbitraries.integers().between(1, 4).map { QuorumComposition(a, atLeast = it) } }
        return Combinators
            .combine(
                thresholds(),
                Arbitraries.integers().between(1, 5),
                roles.list().ofMaxSize(2),
                composition.list().ofMaxSize(2),
                Arbitraries.of(QuorumCounts.VOTING, QuorumCounts.ALL),
                Arbitraries.of(true, false),
                Arbitraries.of(true, false),
                Arbitraries.of(true, false),
            ).`as` { threshold, minimum, required, comp, counts, remote, alternates, recused ->
                QuorumRule(
                    counts = counts,
                    threshold = threshold,
                    minimum = minimum,
                    required = required,
                    composition = comp,
                    remoteCounts = remote,
                    alternatesCount = alternates,
                    recusedCount = recused,
                )
            }
    }

    @Provide
    fun meetings(): Arbitrary<Meeting> {
        val seat =
            Combinators
                .combine(
                    Arbitraries.of(*ForumRole.entries.toTypedArray()),
                    Arbitraries.of(true, false),
                    Arbitraries.of("independent", "management").set().ofMaxSize(2),
                ).`as` { role, voting, attributes -> Triple(role, voting, attributes) }
        val presence =
            Combinators.combine(Arbitraries.of(true, false), Arbitraries.of(true, false), Arbitraries.of(true, false)).`as` { r, a, x ->
                QuorumPresence(remote = r, byAlternate = a, recused = x)
            }
        return seat.list().ofMinSize(1).ofMaxSize(15).flatMap { seats ->
            val roster = seats.mapIndexed { i, (role, voting, attributes) -> QuorumSeat(UUID(0, i.toLong()), role, voting, attributes) }
            presence.injectNull(0.5).list().ofSize(roster.size).map { presences ->
                Meeting(roster, roster.zip(presences).mapNotNull { (s, p) -> p?.let { s.memberId to it } }.toMap())
            }
        }
    }
}
