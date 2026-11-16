package com.memo.memo

import java.math.BigInteger
import java.util.UUID

/** When a series starts counting again (ADR-0044): never, every calendar year, or every financial year. */
enum class LiveSeriesReset { NEVER, YEARLY, FINANCIAL_YEAR }

/**
 * A number series as a published memo-type version uses it (ADR-0044, design E06 §5.2), for the import's check that a
 * legacy number is not one the series may still give: the format as a [pattern] over whole numbers (text and the type's
 * code literal, a unit code, the year or financial year, the sequence as digits), the period the series counts in now,
 * and the last number taken by each of its counters.
 */
class LiveSeries(
    val series: String,
    val typeId: UUID,
    /** The format's shape, values that vary in braces (`M-{year4}-{sequence}`). */
    val format: String,
    /** The whole number, case-insensitive, with the groups [YEAR], [FY], [FY_SHORT], [UNIT] and [SEQUENCE] where present. */
    val pattern: Regex,
    val reset: LiveSeriesReset,
    /** The month the financial year starts in, 1 to 12. */
    val fyStartMonth: Int,
    /** The period counted in today: `ALL`, `2026` or `FY2026-27`. */
    val currentPeriod: String,
    /** The last number taken, by counter: `{period}/{scope}`, the scope a unit id or empty. */
    val lastNumbers: Map<String, Long>,
    /** Counting per unit: the scope of each unit code (upper case). Null when the series counts as a whole. */
    val unitScopes: Map<String, String>?,
) {
    /**
     * Whether [number] has this series' format, falls in the current period or a later one (any period when the series
     * never restarts), and is above its counter: a memo raised here could then be given it later. A number of an
     * earlier period, or one the counter has passed, cannot be given again.
     */
    fun mayStillGive(number: String): Boolean {
        val match = pattern.matchEntire(number.trim()) ?: return false
        val sequence = match.valueOf(SEQUENCE)?.toBigIntegerOrNull()
        val period = periodOf(match)?.takeIf { order(it) >= order(currentPeriod) }
        return sequence != null && period != null && sequence > lastTaken(match, period)
    }

    /**
     * The counter's last number in [period] for the number's unit. A unit code no unit has yet could still be given to a
     * new unit, so it counts from zero, as does a counter not started.
     */
    private fun lastTaken(
        match: MatchResult,
        period: String,
    ): BigInteger {
        val scope = if (unitScopes == null) "" else match.valueOf(UNIT)?.uppercase()?.let(unitScopes::get)
        val last = scope?.let { lastNumbers["$period/$it"] } ?: 0L
        return BigInteger.valueOf(last)
    }

    private fun periodOf(match: MatchResult): String? =
        when (reset) {
            LiveSeriesReset.NEVER -> {
                ALL
            }

            LiveSeriesReset.YEARLY -> {
                match
                    .valueOf(YEAR)
                    ?.let(::year)
                    ?.toString()
            }

            LiveSeriesReset.FINANCIAL_YEAR -> {
                val start =
                    match.valueOf(FY)?.let { year(it.substringBefore('-')) }
                        ?: match.valueOf(FY_SHORT)?.let { short -> year(short).let { if (fyStartMonth == 1) it else it - 1 } }
                start?.let { "FY$it-${two(if (fyStartMonth == 1) it else it + 1)}" }
            }
        }

    /** The text of the group [name]; null when the format has no such group or it took no part. */
    private fun MatchResult.valueOf(name: String): String? = runCatching { groups[name]?.value }.getOrNull()

    companion object {
        const val YEAR = "year"
        const val FY = "fy"
        const val FY_SHORT = "fyshort"
        const val UNIT = "unit"
        const val SEQUENCE = "seq"
        const val ALL = "ALL"
        private const val CENTURY = 2000
        private const val TWO_DIGITS = 2
        private const val FY_PREFIX = 2
        private const val HUNDRED = 100
        private const val YEAR_DIGITS = 4

        /** A year written with 2 digits is in this century, as numbering writes it. */
        private fun year(digits: String): Int = digits.toInt().let { if (digits.length == TWO_DIGITS) CENTURY + it else it }

        private fun two(year: Int): String = (year % HUNDRED).toString().padStart(TWO_DIGITS, '0')

        /** Periods in time order: the start year (`2026`, `FY2026-27`); `ALL` is always now. */
        private fun order(period: String): Int =
            when {
                period == ALL -> 0
                period.startsWith("FY") -> period.substring(FY_PREFIX, FY_PREFIX + YEAR_DIGITS).toInt()
                else -> period.toInt()
            }
    }
}
