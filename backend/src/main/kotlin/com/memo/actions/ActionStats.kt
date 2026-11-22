package com.memo.actions

import java.time.LocalDate
import java.util.UUID

/**
 * Overdue actions of one committee and one owner (design E08 §1, M2), as the viewer may see them: issued actions OPEN or
 * IN_PROGRESS past their due date, grouped by owner (a person, or a position with whoever holds the owner's task), with
 * ageing bands in calendar days past the date and how many of them had their date revised.
 */
data class OverdueActions(
    val forumId: UUID,
    /** A person owner; null for a position owner. */
    val ownerUserId: UUID?,
    /** A position owner ("Head of Treasury"); null for a person owner. */
    val ownerPositionId: UUID?,
    /** Who holds the owner's task now (the person, the position's holder or a substitute); null while it waits. */
    val holderUserId: UUID?,
    val count: Int,
    /** 1 to 30 days past the due date. */
    val days1To30: Int,
    val days31To90: Int,
    val over90: Int,
    /** Those whose date was changed at least once. */
    val revised: Int,
)

/**
 * The actions of one committee closed in a period (design E08 §1, M2), as the viewer may see them: how many, and how many
 * were done by the end of their original due date and by the end of their current (agreed) due date, in the tenant's zone.
 */
data class ClosedOnTime(
    val forumId: UUID,
    val closed: Int,
    val byOriginalDate: Int,
    val byAgreedDate: Int,
)

/**
 * Counts of action items for E08's analytics (design E08 §2.2, M2), each from the one rule (`ActionAccess`, design E07
 * §1.14) in one query: an action the viewer may not see, such as one of an item they are recused from, is not counted.
 * The viewer is the signed-in person (their committees, permissions and clearance today), so nobody counts for someone
 * else; with nobody signed in every answer is empty. It replaces the viewer-less `ActionCounts.overdue(forumId)` of the
 * E07 design, which was never built.
 */
interface ActionStats {
    /** The overdue actions of [forumIds] [today] (the tenant's date), by committee and owner. */
    fun overdue(
        forumIds: Set<UUID>,
        today: LocalDate,
    ): List<OverdueActions>

    /** The actions of [forumIds] closed from [from] to [to] (days in the tenant's zone, both included), by committee. */
    fun closedOnTime(
        forumIds: Set<UUID>,
        from: LocalDate,
        to: LocalDate,
    ): List<ClosedOnTime>
}
