package com.memo.minutes.internal

import net.jqwik.api.Arbitraries
import net.jqwik.api.Arbitrary
import net.jqwik.api.Combinators
import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.Provide
import org.assertj.core.api.Assertions.assertThat
import java.time.Instant
import java.util.UUID

/**
 * For random agendas and random recording orders (test plan E06 T4, `AgendaOrderProperties`; ADR-0065): the numbers given
 * at the meeting's end follow the agenda, not the order the decisions were recorded in. Each resolution without a number
 * takes exactly one, the decisions that are not resolutions or have a number take none, and an item no longer on the
 * agenda comes after every item on it.
 */
class AgendaOrderProperties {
    /** An agenda, its decisions in the order they were recorded (some off the agenda), and which are resolutions. */
    data class Case(
        val agenda: List<UUID>,
        val decisions: List<NumberingCandidate>,
    )

    @Property(tries = 500)
    fun numbersFollowTheAgendaWhateverTheRecordingOrder(
        @ForAll("cases") case: Case,
    ) {
        val numbered = ResolutionOrder.toNumber(case.agenda, case.decisions)
        val sequence = numbered.withIndex().associate { (i, c) -> c.decisionId to i + 1 }
        val position = case.agenda.withIndex().associate { (i, id) -> id to i }

        // Exactly the resolutions without a number, each once.
        assertThat(numbered.map { it.decisionId })
            .containsExactlyInAnyOrderElementsOf(case.decisions.filter { it.resolution && !it.numbered }.map { it.decisionId })
        // Agenda order: an item earlier on the agenda never takes a later number; items off the agenda come last.
        numbered.zipWithNext().forEach { (a, b) ->
            val pa = position[a.itemId] ?: Int.MAX_VALUE
            val pb = position[b.itemId] ?: Int.MAX_VALUE
            assertThat(pa).isLessThanOrEqualTo(pb)
            if (pa == pb) assertThat(a.decidedAt).isBeforeOrEqualTo(b.decidedAt)
        }
        // The same agenda and decisions recorded in any other order number the same way.
        assertThat(ResolutionOrder.toNumber(case.agenda, case.decisions.shuffled()).map { sequence[it.decisionId] })
            .containsExactlyElementsOf((1..numbered.size).toList())
    }

    @Provide
    fun cases(): Arbitrary<Case> {
        val agenda = Arbitraries.integers().between(0, 30).map { n -> List(n) { UUID.randomUUID() } }
        return agenda.flatMap { items ->
            val offAgenda = Arbitraries.integers().between(0, 3).map { n -> List(n) { UUID.randomUUID() } }
            Combinators.combine(offAgenda, Arbitraries.randoms()).`as` { extra, random ->
                // One decision per item (on the agenda or not), in a random recording order, at random times.
                val decided = (items + extra).filter { random.nextInt(4) != 0 }.shuffled(random)
                val start = Instant.parse("2026-12-12T04:00:00Z")
                val decisions =
                    decided.map { item ->
                        NumberingCandidate(
                            decisionId = UUID.randomUUID(),
                            itemId = item,
                            decidedAt = start.plusSeconds(random.nextInt(10_800).toLong()),
                            resolution = random.nextInt(3) != 0,
                            numbered = random.nextInt(6) == 0,
                        )
                    }
                Case(items, decisions)
            }
        }
    }
}
