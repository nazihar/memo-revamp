package com.memo.analytics.measures

import com.memo.search.DecisionFact
import com.memo.workflow.WorkingDayCounter
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * Measures M3 and M4's definitions (design E08 §2.1; test plan E08 T4 `DecisionTimingTest`): the median and nearest-rank
 * 90th percentile, calendar and working days, a referral chain and a deferral chain counted from their first submission,
 * DEFERRED and REFERRED not final, quarters by meeting date, items deferred twice counted once.
 */
class DecisionTimingTest {
    private val zone = ZoneId.of("Asia/Dhaka")
    private val forum = UUID.randomUUID()
    private val start = LocalDate.of(2026, 1, 4)

    /** Sunday to Thursday are working days (the Bangladeshi week). */
    private val counter =
        WorkingDayCounter { from, to ->
            generateSequence(from.plusDays(1)) { it.plusDays(1) }
                .takeWhile { !it.isAfter(to) }
                .count { it.dayOfWeek != DayOfWeek.FRIDAY && it.dayOfWeek != DayOfWeek.SATURDAY }
        }

    private fun fact(
        days: Long,
        outcome: String = "APPROVED",
        submitted: LocalDate = start,
        memo: UUID = UUID.randomUUID(),
        prior: Int = 0,
        memoType: UUID? = null,
    ): DecisionFact {
        val decided = submitted.plusDays(days)
        return DecisionFact(
            forumId = forum,
            memoTypeId = memoType,
            memoId = memo,
            submissionId = UUID.randomUUID(),
            meetingDate = decided,
            decidedAt = decided.atTime(11, 0).atZone(zone).toInstant(),
            chainSubmittedAt = submitted.atTime(16, 0).atZone(zone).toInstant(),
            outcomeKind = outcome,
            priorDeferrals = prior,
        )
    }

    @Test
    fun `nearest rank picks the value at rank ceil(p n)`() {
        val days = (1..10).toList()
        assertThat(DecisionMeasure.nearestRank(days, 0.5)).isEqualTo(5)
        assertThat(DecisionMeasure.nearestRank(days, 0.9)).isEqualTo(9)
        assertThat(DecisionMeasure.nearestRank(listOf(7), 0.9)).isEqualTo(7)
        assertThat(DecisionMeasure.nearestRank(listOf(3, 8), 0.5)).isEqualTo(3)
        assertThat(DecisionMeasure.nearestRank(emptyList(), 0.5)).isNull()
    }

    @Test
    fun `calendar days and working days, median and 90th percentile, with the basis`() {
        val facts = listOf(7L, 14L, 3L, 21L, 10L, 30L).map { fact(it) }
        val timing = DecisionMeasure.timing(facts, zone, counter)
        assertThat(timing.decisions).isEqualTo(6)
        assertThat(timing.medianDays to timing.p90Days).isEqualTo(10 to 30)
        // 4 Jan 2026 is a Sunday: 7 calendar days hold 5 working days (Friday and Saturday off).
        assertThat(DecisionMeasure.timing(listOf(fact(7)), zone, counter).medianWorkingDays).isEqualTo(5)
        assertThat(timing.enough).isTrue()
        assertThat(DecisionMeasure.timing(facts.take(4), zone, counter).enough).isFalse()
    }

    @Test
    fun `a deferral and a referral are not final, and the later decision counts from the chain's first submission`() {
        val memo = UUID.randomUUID()
        val deferred = fact(10, "DEFERRED", memo = memo)
        val referred = fact(20, "REFERRED", memo = memo, prior = 1)
        val approved = fact(45, "APPROVED", memo = memo, prior = 1)
        val timing = DecisionMeasure.timing(listOf(deferred, referred, approved), zone, counter)
        assertThat(timing.decisions).isEqualTo(1)
        assertThat(timing.medianDays).isEqualTo(45)
    }

    @Test
    fun `a decision without a known chain start is left out of the timing but not of the deferrals`() {
        val unknown = fact(5).copy(chainSubmittedAt = null)
        assertThat(DecisionMeasure.timing(listOf(unknown), zone, counter).decisions).isZero()
        assertThat(DecisionMeasure.deferrals(listOf(unknown)).decisions).isEqualTo(1)
    }

    @Test
    fun `quarters by the meeting's date, and memo types with the most decisions first`() {
        val credit = UUID.randomUUID()
        val facts =
            listOf(fact(10, memoType = credit), fact(20, memoType = credit), fact(100), fact(110, "DEFERRED"))
        val quarters = DecisionMeasure.timingByQuarter(facts, zone, counter)
        assertThat(quarters.map { it.quarter }).containsExactly("2026-Q1", "2026-Q2")
        assertThat(quarters.map { it.timing.decisions }).containsExactly(2, 1)
        val types = DecisionMeasure.timingByMemoType(facts, zone, counter)
        assertThat(types.first().memoTypeId).isEqualTo(credit)
        assertThat(types.first().timing.medianDays).isEqualTo(10)
        assertThat(DecisionMeasure.deferralsByQuarter(facts).map { it.quarter to it.deferrals.deferred })
            .containsExactly("2026-Q1" to 0, "2026-Q2" to 1)
    }

    @Test
    fun `items deferred, and an item deferred twice or more counted once`() {
        val twice = UUID.randomUUID()
        val facts =
            listOf(
                fact(10, "DEFERRED", memo = twice),
                fact(40, "DEFERRED", memo = twice, prior = 1),
                fact(70, "DEFERRED", memo = twice, prior = 2),
                fact(90, "APPROVED", memo = twice, prior = 3),
                fact(12, "DEFERRED"),
                fact(15, "APPROVED"),
            )
        val deferrals = DecisionMeasure.deferrals(facts)
        assertThat(deferrals).isEqualTo(Deferrals(6, 4, 1))
        assertThat(deferrals.deferredPercent).isEqualTo(67)
        assertThat(DecisionMeasure.deferrals(emptyList()).deferredPercent).isNull()
    }
}
