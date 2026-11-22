package com.memo.analytics.measures

import com.memo.actions.ClosedOnTime
import com.memo.actions.OverdueActions
import java.util.UUID

/** Overdue actions now, with ageing bands in days past the due date (design E08 §2.1 M2). */
data class Overdue(
    val count: Int,
    val days1To30: Int,
    val days31To90: Int,
    val over90: Int,
    /** Those whose date was changed at least once. */
    val revised: Int,
) {
    operator fun plus(other: Overdue) =
        Overdue(
            count + other.count,
            days1To30 + other.days1To30,
            days31To90 + other.days31To90,
            over90 + other.over90,
            revised + other.revised,
        )

    companion object {
        val NONE = Overdue(0, 0, 0, 0, 0)

        fun of(row: OverdueActions) = Overdue(row.count, row.days1To30, row.days31To90, row.over90, row.revised)
    }
}

/** Actions closed in the period, and how many by their original and by their agreed (current) date. */
data class Closed(
    val closed: Int,
    val byOriginalDate: Int,
    val byAgreedDate: Int,
) {
    val byOriginalPercent: Int? get() = percent(byOriginalDate, closed)
    val byAgreedPercent: Int? get() = percent(byAgreedDate, closed)

    companion object {
        val NONE = Closed(0, 0, 0)
    }
}

/** One owner's overdue actions: a person, or a position with whoever holds the owner's task now. */
data class OwnerOverdue(
    val ownerUserId: UUID?,
    val ownerPositionId: UUID?,
    val holderUserId: UUID?,
    val overdue: Overdue,
)

/** A committee's M2. */
data class CommitteeActions(
    val overdue: Overdue,
    val closed: Closed,
)

/**
 * Measure M2, actions (ADR-0070, design E08 §2.1), over the counts the actions module gives for the viewer
 * (`ActionStats`, the one rule `ActionAccess`: an action the viewer may not see is not in them):
 *  - overdue now: issued actions OPEN or IN_PROGRESS with today after their due date (a DONE_PENDING action waiting for
 *    verification is not overdue), in bands of 1–30, 31–90 and over 90 days past the date, and how many were re-dated;
 *  - completed on time: of the actions closed in the period, those done by the end of their original due date and those
 *    done by the end of their agreed (current) due date, shown together so a late closure cannot look on time.
 */
object ActionMeasure {
    /** Each of [forumIds]' totals: [overdue] summed over owners, [closed] as counted. */
    fun committees(
        forumIds: Collection<UUID>,
        overdue: List<OverdueActions>,
        closed: List<ClosedOnTime>,
    ): Map<UUID, CommitteeActions> {
        val late = overdue.groupBy { it.forumId }
        val done = closed.associateBy { it.forumId }
        return forumIds.associateWith { forum ->
            CommitteeActions(
                overdue = late[forum].orEmpty().map(Overdue::of).fold(Overdue.NONE, Overdue::plus),
                closed = done[forum]?.let { Closed(it.closed, it.byOriginalDate, it.byAgreedDate) } ?: Closed.NONE,
            )
        }
    }

    /** [forumId]'s overdue actions by owner, the most overdue first (then the oldest band), ties by id. */
    fun owners(
        forumId: UUID,
        overdue: List<OverdueActions>,
    ): List<OwnerOverdue> =
        overdue
            .filter { it.forumId == forumId }
            .groupBy { Triple(it.ownerUserId, it.ownerPositionId, it.holderUserId) }
            .map { (owner, rows) ->
                OwnerOverdue(owner.first, owner.second, owner.third, rows.map(Overdue::of).fold(Overdue.NONE, Overdue::plus))
            }.sortedWith(
                compareByDescending<OwnerOverdue> { it.overdue.count }
                    .thenByDescending { it.overdue.over90 }
                    .thenBy { (it.ownerPositionId ?: it.ownerUserId).toString() }
                    .thenBy { it.holderUserId.toString() },
            )
}
