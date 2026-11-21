package com.memo.minutes.internal

import org.springframework.stereotype.Component
import java.util.UUID

/** Where a meeting's minutes stand (design E06 §1.5, ADR-0066). The `minutes` table comes with T5. */
enum class MinutesStage(
    /** Out of the draft and not approved: a decision change sends them back to the draft. */
    val inReview: Boolean,
) {
    DRAFT(inReview = false),
    CHAIR_REVIEW(inReview = true),
    CIRCULATED(inReview = true),
    AWAITING_CHAIR(inReview = true),
    APPROVED(inReview = false),
}

/**
 * The minutes as the decisions see them (design E06 §1.1 "Windows"): a decision changes while the minutes are in DRAFT; a
 * change once they are with the chair or circulated sends them back to the draft (the item's text must be regenerated);
 * nothing changes once they are approved. T5 creates the minutes and replaces [NoMinutesYet]; T6 moves them.
 */
interface MinutesStages {
    /** The stage of [meetingId]'s minutes; null while there are none (before the meeting is held, or before T5). */
    fun stage(meetingId: UUID): MinutesStage?

    /** Sends [meetingId]'s minutes back to the draft because the decision on [itemId] changed, in the caller's transaction. */
    fun returnToDraft(
        meetingId: UUID,
        itemId: UUID,
    )
}

/** No minutes exist before T5: every decision change is a change to the draft. */
@Component
class NoMinutesYet : MinutesStages {
    override fun stage(meetingId: UUID): MinutesStage? = null

    override fun returnToDraft(
        meetingId: UUID,
        itemId: UUID,
    ) = Unit
}
