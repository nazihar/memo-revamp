package com.memo.analytics.measures

import com.memo.search.DecisionFact
import com.memo.workflow.WorkingDayCounter
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.time.temporal.IsoFields
import java.util.UUID
import kotlin.math.ceil

/**
 * Time from submission to decision over some decisions (design E08 §2.1 M3): their number, and the median and the 90th
 * percentile (nearest rank) in calendar days and in working days. [enough] is false below [MIN_BASIS] decisions, where
 * the screen lists the decisions instead of a typical time.
 */
data class Timing(
    val decisions: Int,
    val medianDays: Int?,
    val p90Days: Int?,
    val medianWorkingDays: Int?,
    val p90WorkingDays: Int?,
) {
    val enough: Boolean get() = decisions >= MIN_BASIS

    companion object {
        /** Fewer decisions than this give no typical time (flow AW). */
        const val MIN_BASIS = 5
    }
}

/** One quarter's [Timing] ("2026-Q1"), by the meeting's date. */
data class QuarterTiming(
    val quarter: String,
    val timing: Timing,
)

/** One memo type's [Timing]; [memoTypeId] null for submissions without a memo type. */
data class MemoTypeTiming(
    val memoTypeId: UUID?,
    val timing: Timing,
)

/**
 * Items deferred (design E08 §2.1 M4): of [decisions] on SUBMISSION items, [deferred] were DEFERRED, and [deferredTwice]
 * items were deferred for at least the second time in their chain.
 */
data class Deferrals(
    val decisions: Int,
    val deferred: Int,
    val deferredTwice: Int,
) {
    val deferredPercent: Int? get() = percent(deferred, decisions)
}

/** One quarter's [Deferrals]. */
data class QuarterDeferrals(
    val quarter: String,
    val deferrals: Deferrals,
)

/**
 * Measures M3 and M4 (ADR-0070, design E08 §2.1) over the decisions the viewer sees in full (`DecisionStats`: decisions
 * on SUBMISSION items, FULL rows of the register only, so a restricted or recused item is never counted).
 *  - M3: decisions whose outcome is final (not DEFERRED or REFERRED), from the first submission of their chain (so a
 *    deferral re-queued or a referral keeps counting) to the decision, as days in the tenant's zone: calendar days (the
 *    industry's measure) and working days on the tenant's business calendar beside them; the median and the 90th
 *    percentile by nearest rank (the value at rank ⌈p·n⌉ of the sorted days, as Phase 3's cycle time). A decision without
 *    a known chain start is left out. Attributed to the committee that decided, and to the quarter of its meeting.
 *  - M4: every decision on a SUBMISSION item in the period is the basis; DEFERRED ones are counted, and an item whose chain
 *    had an earlier deferral when it was deferred again is counted once in [Deferrals.deferredTwice].
 */
object DecisionMeasure {
    private const val MEDIAN = 0.5
    private const val P90 = 0.9
    private val NOT_FINAL = setOf("DEFERRED", "REFERRED")

    /** [Timing] of [facts]: days counted in [zone], working days by [counter]. */
    fun timing(
        facts: List<DecisionFact>,
        zone: ZoneId,
        counter: WorkingDayCounter,
    ): Timing {
        val spans = facts.filter { it.outcomeKind !in NOT_FINAL }.mapNotNull { span(it, zone) }
        val calendar = spans.map { (from, to) -> ChronoUnit.DAYS.between(from, to).toInt() }.sorted()
        val working = spans.map { (from, to) -> counter.between(from, to) }.sorted()
        return Timing(
            decisions = spans.size,
            medianDays = nearestRank(calendar, MEDIAN),
            p90Days = nearestRank(calendar, P90),
            medianWorkingDays = nearestRank(working, MEDIAN),
            p90WorkingDays = nearestRank(working, P90),
        )
    }

    /** [timing] per quarter of the meetings' dates, oldest first. */
    fun timingByQuarter(
        facts: List<DecisionFact>,
        zone: ZoneId,
        counter: WorkingDayCounter,
    ): List<QuarterTiming> =
        facts
            .groupBy { quarter(it.meetingDate) }
            .toSortedMap()
            .map { (quarter, inQuarter) -> QuarterTiming(quarter, timing(inQuarter, zone, counter)) }
            .filter { it.timing.decisions > 0 }

    /** [timing] per memo type, the most decisions first. */
    fun timingByMemoType(
        facts: List<DecisionFact>,
        zone: ZoneId,
        counter: WorkingDayCounter,
    ): List<MemoTypeTiming> =
        facts
            .groupBy { it.memoTypeId }
            .map { (type, ofType) -> MemoTypeTiming(type, timing(ofType, zone, counter)) }
            .filter { it.timing.decisions > 0 }
            .sortedWith(compareByDescending<MemoTypeTiming> { it.timing.decisions }.thenBy { it.memoTypeId.toString() })

    /** [Deferrals] of [facts]. */
    fun deferrals(facts: List<DecisionFact>): Deferrals {
        val deferred = facts.filter { it.outcomeKind == "DEFERRED" }
        val twice = deferred.filter { it.priorDeferrals >= 1 }.distinctBy(::chainOf).size
        return Deferrals(facts.size, deferred.size, twice)
    }

    /** [deferrals] per quarter of the meetings' dates, oldest first. */
    fun deferralsByQuarter(facts: List<DecisionFact>): List<QuarterDeferrals> =
        facts
            .groupBy { quarter(it.meetingDate) }
            .toSortedMap()
            .map { (quarter, inQuarter) -> QuarterDeferrals(quarter, deferrals(inQuarter)) }

    /** "2026-Q1". */
    fun quarter(day: LocalDate): String = "${day.year}-Q${day.get(IsoFields.QUARTER_OF_YEAR)}"

    /** The value at rank ⌈[p]·n⌉ of [sorted] (nearest rank); null when it is empty. */
    fun nearestRank(
        sorted: List<Int>,
        p: Double,
    ): Int? = if (sorted.isEmpty()) null else sorted[(ceil(p * sorted.size).toInt() - 1).coerceIn(0, sorted.size - 1)]

    /** The days of the chain's first submission and of the decision in [zone]; null without a chain start. */
    private fun span(
        fact: DecisionFact,
        zone: ZoneId,
    ): Pair<LocalDate, LocalDate>? {
        val start = fact.chainSubmittedAt?.atZone(zone)?.toLocalDate() ?: return null
        val end = fact.decidedAt.atZone(zone).toLocalDate()
        return if (end.isBefore(start)) end to end else start to end
    }

    /**
     * The item's chain: its memo (or its submission when it has none) with the chain's first submission. A chain is
     * re-queued through new submissions of one memo, all carrying the same start, so this names it across deferrals.
     */
    private fun chainOf(fact: DecisionFact): Pair<UUID, java.time.Instant?> = (fact.memoId ?: fact.submissionId) to fact.chainSubmittedAt
}
