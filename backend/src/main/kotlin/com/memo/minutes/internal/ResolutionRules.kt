package com.memo.minutes.internal

import com.memo.minutes.ResolutionCheck
import com.memo.minutes.ResolutionState
import java.time.Instant
import java.util.UUID

/** A decision as numbering at the meeting's end sees it: its item, when it was taken, and whether it needs a number. */
data class NumberingCandidate(
    val decisionId: UUID,
    val itemId: UUID,
    val decidedAt: Instant,
    /** Its current entry's outcome is marked as a resolution. */
    val resolution: Boolean,
    /** It has a number in force already. */
    val numbered: Boolean,
)

/**
 * The order resolution numbers are given in at the meeting's end (ADR-0065, design E06 §1.3): the decisions whose current
 * outcome is a resolution and that have no number yet, in agenda order (the latest revision's items, then items tabled at
 * the meeting in the order they were tabled, as `MeetingItems.agenda` lists them); a decided item no longer on the agenda
 * comes after, in the order it was taken. Pure.
 */
object ResolutionOrder {
    fun toNumber(
        agenda: List<UUID>,
        decisions: List<NumberingCandidate>,
    ): List<NumberingCandidate> {
        val position = agenda.withIndex().associate { (i, id) -> id to i }
        return decisions
            .filter { it.resolution && !it.numbered }
            .sortedWith(
                compareBy<NumberingCandidate>({ position[it.itemId] ?: Int.MAX_VALUE }, { it.decidedAt }, { it.decisionId }),
            )
    }
}

/**
 * The register check (design E06 §1.3): for one committee and period, every number from the counter's first to its last
 * is given exactly once, in force or void, and none lies outside. Pure.
 */
object ResolutionChecks {
    /** How many missing numbers a check lists (the count is always given). */
    const val MAX_LISTED = 50

    fun of(
        forumId: UUID,
        counter: CounterRow,
        given: List<GivenNumber>,
    ): ResolutionCheck {
        val range = counter.firstNumber..counter.lastNumber
        val sequences = given.map { it.sequence }
        val seen = sequences.toSet()
        val duplicates =
            sequences
                .groupingBy { it }
                .eachCount()
                .filterValues { it > 1 }
                .keys
                .sorted()
        val missing = range.asSequence().filter { it !in seen }
        return ResolutionCheck(
            forumId = forumId,
            period = counter.period,
            firstNumber = counter.firstNumber,
            lastNumber = counter.lastNumber,
            inForce = given.count { it.state == ResolutionState.IN_FORCE },
            void = given.count { it.state == ResolutionState.VOID },
            missing = missing.take(MAX_LISTED).toList(),
            missingCount = missing.count().toLong(),
            duplicates = duplicates,
            outOfRange = seen.filter { it !in range }.sorted(),
        )
    }
}
