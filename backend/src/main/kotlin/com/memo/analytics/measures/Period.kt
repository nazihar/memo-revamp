package com.memo.analytics.measures

import com.memo.common.web.FieldProblem
import java.time.LocalDate

/** Well-formed input the analytics refuse (422 `validation`, one entry per field). */
class AnalyticsRejectedException(
    val problems: List<FieldProblem>,
) : RuntimeException("analytics request rejected: ${problems.joinToString { "${it.field}:${it.code}" }}")

/**
 * The period of a measure (design E08 §2.1): days in the tenant's zone, both included. By default the last 12 months up
 * to today; at most ten years, five for per-member attendance (its export refuses more; the committee's detail leaves its
 * member rows out above five).
 */
data class Period(
    val from: LocalDate,
    val to: LocalDate,
) {
    /** The period is no longer than [years] years. */
    fun within(years: Long): Boolean = to.isBefore(from.plusYears(years))

    /** Whether [day] lies in the period. */
    operator fun contains(day: LocalDate): Boolean = !day.isBefore(from) && !day.isAfter(to)

    companion object {
        const val DEFAULT_MONTHS = 12L
        const val MAX_YEARS = 10L
        const val MAX_MEMBER_YEARS = 5L

        /**
         * The period from the request's [from] and [to] (either may be missing) on [today]: 422 `to RANGE` when it ends
         * before it starts, `from RANGE` when it is longer than [maxYears].
         */
        fun of(
            from: LocalDate?,
            to: LocalDate?,
            today: LocalDate,
            maxYears: Long = MAX_YEARS,
        ): Period {
            val end = to ?: maxOf(today, from ?: today)
            val start = from ?: end.minusMonths(DEFAULT_MONTHS).plusDays(1)
            val period = Period(start, end)
            val problems =
                listOfNotNull(
                    FieldProblem("to", "RANGE", "The period ends on or after it starts.").takeIf { end.isBefore(start) },
                    FieldProblem("from", "RANGE", "A period of at most $maxYears years.")
                        .takeIf { !end.isBefore(start) && !period.within(maxYears) },
                )
            if (problems.isNotEmpty()) throw AnalyticsRejectedException(problems)
            return period
        }
    }
}

/** A share in whole percent, rounded half up; null when there is nothing to share ([of] is 0). */
fun percent(
    part: Int,
    of: Int,
): Int? = if (of <= 0) null else ((part * PERCENT * 2 + of) / (of * 2))

private const val PERCENT = 100
