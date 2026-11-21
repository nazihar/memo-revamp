package com.memo.minutes.internal

import com.memo.minutes.ResolutionState
import com.memo.minutes.ResolutionState.IN_FORCE
import com.memo.minutes.ResolutionState.VOID
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * The register check and the order of numbering (test plan E06 T4; design E06 §1.3): every number from the counter's
 * first to its last given once, in force or void; a hole, a duplicate or a stray fails it; a starting number set by an
 * administrator counts from there. At the meeting's end, resolutions without a number in agenda order.
 */
class ResolutionChecksTest {
    private val forum = UUID.randomUUID()

    private fun counter(
        last: Long,
        first: Long = 1,
    ) = CounterRow(forum, "2026", last, first, first != 1L)

    private fun given(vararg numbers: Pair<Long, ResolutionState>) = numbers.map { (s, state) -> GivenNumber(s, state) }

    @Test
    fun `every number given once, in force or void, is complete`() {
        val check = ResolutionChecks.of(forum, counter(4), given(1L to IN_FORCE, 2L to VOID, 3L to IN_FORCE, 4L to IN_FORCE))
        assertThat(check.complete).isTrue()
        assertThat(check.inForce to check.void).isEqualTo(3 to 1)
        assertThat(check.missing + check.duplicates + check.outOfRange).isEmpty()
        assertThat(check.missingCount).isZero()
        assertThat(check.firstNumber to check.lastNumber).isEqualTo(1L to 4L)
        assertThat(check.period).isEqualTo("2026")
    }

    @Test
    fun `a hole, a duplicate or a number beyond the counter fails the check`() {
        val hole = ResolutionChecks.of(forum, counter(4), given(1L to IN_FORCE, 2L to IN_FORCE, 4L to IN_FORCE))
        assertThat(hole.complete).isFalse()
        assertThat(hole.missing to hole.missingCount).isEqualTo(listOf(3L) to 1L)

        val twice = ResolutionChecks.of(forum, counter(2), given(1L to IN_FORCE, 2L to IN_FORCE, 2L to VOID))
        assertThat(twice.complete).isFalse()
        assertThat(twice.duplicates).containsExactly(2L)
        assertThat(twice.missingCount).isZero()

        val stray = ResolutionChecks.of(forum, counter(1), given(1L to IN_FORCE, 7L to IN_FORCE))
        assertThat(stray.complete).isFalse()
        assertThat(stray.outOfRange).containsExactly(7L)

        val short = ResolutionChecks.of(forum, counter(3), given(1L to IN_FORCE, 2L to IN_FORCE))
        assertThat(short.complete).isFalse()
        assertThat(short.missing).containsExactly(3L)
    }

    @Test
    fun `a starting number counts from there, and nothing given yet is complete`() {
        val started = ResolutionChecks.of(forum, counter(15, first = 14), given(14L to IN_FORCE, 15L to VOID))
        assertThat(started.complete).isTrue()
        assertThat(started.firstNumber).isEqualTo(14L)
        val below = ResolutionChecks.of(forum, counter(14, first = 14), given(3L to IN_FORCE, 14L to IN_FORCE))
        assertThat(below.outOfRange).containsExactly(3L)
        assertThat(below.complete).isFalse()
        val none = ResolutionChecks.of(forum, counter(13, first = 14), emptyList())
        assertThat(none.complete).isTrue()
        assertThat(none.inForce + none.void).isZero()
    }

    @Test
    fun `missing numbers are listed up to the limit and counted in full`() {
        val check = ResolutionChecks.of(forum, counter(200), given(1L to IN_FORCE))
        assertThat(check.missing).hasSize(ResolutionChecks.MAX_LISTED).startsWith(2L, 3L)
        assertThat(check.missingCount).isEqualTo(199)
    }

    @Test
    fun `at the meeting's end, resolutions without a number in agenda order, items off the agenda after by time`() {
        val (a, b, c) = List(3) { UUID.randomUUID() }
        val off = UUID.randomUUID()
        val t = Instant.parse("2026-12-12T04:00:00Z")

        fun candidate(
            item: UUID,
            seconds: Long,
            resolution: Boolean = true,
            numbered: Boolean = false,
        ) = NumberingCandidate(UUID.randomUUID(), item, t.plusSeconds(seconds), resolution, numbered)
        val decisions =
            listOf(
                candidate(c, 1),
                candidate(off, 0),
                candidate(a, 2),
                candidate(b, 3, resolution = false),
                candidate(b, 4, numbered = true),
                candidate(b, 5),
            )
        val order = ResolutionOrder.toNumber(listOf(a, b, c), decisions).map { it.itemId to it.decidedAt }
        assertThat(order).containsExactly(a to t.plusSeconds(2), b to t.plusSeconds(5), c to t.plusSeconds(1), off to t)
    }
}
