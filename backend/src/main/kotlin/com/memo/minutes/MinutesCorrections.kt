package com.memo.minutes

import java.time.Instant
import java.util.UUID

/**
 * One approved correction to a meeting's approved minutes (design E06 §1.6.5), as other modules read it.
 *
 * [text] is the correction as approved: item content when [itemId] is set, so a caller shows it only to people who see
 * that item in full (E07's report rows keep the item's columns for this, design E07 §1.14); a general correction
 * ([itemId] null) concerns the minutes as a whole. Never shown in notifications or audit details.
 */
data class MinutesCorrectionRef(
    val id: UUID,
    /** The meeting whose minutes were corrected. */
    val meetingId: UUID,
    val forumId: UUID,
    /** The agenda item it corrects; null for the minutes in general. */
    val itemId: UUID?,
    val text: String,
    /** When the chair (or vice-chair) approved it. */
    val approvedAt: Instant,
    /** The version of the minutes' record that carries it in its "Corrections" annex. */
    val recordVersion: Int,
)

/**
 * Approved corrections to a committee's minutes, for E07's action taken report ("Corrections to earlier minutes", design
 * E07 §1.12 section 6). Inside a tenant transaction; callers decide access themselves.
 */
interface MinutesCorrections {
    /** [forumId]'s corrections approved at or after [since], oldest first; proposed and declined ones are never listed. */
    fun since(
        forumId: UUID,
        since: Instant,
    ): List<MinutesCorrectionRef>
}
