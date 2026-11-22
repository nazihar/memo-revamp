package com.memo.meeting

import com.memo.forum.StandingKind
import java.time.Instant
import java.util.UUID

// E07 T7 adds the item-links port of E06 T6 (PR #229) as that PR defines it, because #229 is not merged yet: the same
// declarations, which #229 keeps in MeetingMinutes.kt. When both are on master, this file goes (its declarations would
// be there twice).

/** A link a standing item of the agenda carries for its readers (design E06 §2): "Minutes of BRC 3/2026 — read your copy". */
data class ItemLink(
    /** What the link opens, for the client: `MINUTES` (the minutes of a meeting), `ACTION_REPORT` (E07). */
    val kind: String,
    /** The meeting the link is about (the earlier meeting whose minutes these are). */
    val meetingId: UUID,
    /** The words to show, without any item's content ("Minutes of BRC 3/2026"). */
    val label: String,
    /** Where the linked thing stands, for the client's wording (the minutes' stage, for example `CIRCULATED`). */
    val state: String?,
    /** For minutes approved by circulation: when ("approved by circulation on …, for noting"). */
    val at: Instant? = null,
)

/**
 * Links shown on a standing item of the members' agenda (design E06 §2, E07 §11): the meeting module knows neither the
 * minutes nor the actions, so each module that has something to show on a kind of standing item provides one of these
 * (several may: minutes for PREVIOUS_MINUTES, the action taken report for MATTERS_ARISING). Called inside the reading
 * transaction for the signed-in actor, only for an item the actor sees in full; the implementation decides what the actor
 * may be shown.
 */
interface ItemLinks {
    /** The kind of standing item this provider links from. */
    val standingKind: StandingKind

    /** The links item [itemId] of [meetingId]'s published agenda carries for the actor; empty when none. */
    fun links(
        meetingId: UUID,
        itemId: UUID,
    ): List<ItemLink>
}
