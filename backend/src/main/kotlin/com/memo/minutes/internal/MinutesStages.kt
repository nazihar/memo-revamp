package com.memo.minutes.internal

import com.memo.audit.AuditLog
import com.memo.common.id.IdGenerator
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.temporal.ChronoUnit
import java.util.UUID

/** Where a meeting's minutes stand (design E06 §1.5, ADR-0066). */
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
 * change once they are with the chair or circulated sends them back to the draft (the item's text must be filled again);
 * nothing changes once they are approved. T6 moves them out of the draft.
 */
interface MinutesStages {
    /** The stage of [meetingId]'s minutes; null while there are none (before the meeting is held). */
    fun stage(meetingId: UUID): MinutesStage?

    /** Sends [meetingId]'s minutes back to the draft because the decision on [itemId] changed, in the caller's transaction. */
    fun returnToDraft(
        meetingId: UUID,
        itemId: UUID,
    )
}

/**
 * [MinutesStages] on the `minutes` row (T5, replacing T3's stand-in). [returnToDraft] locks the row after the decision,
 * submission and meeting rows the caller holds (the lock order of design E06 §1.4), and records the return in the history
 * and the audit: minutes in review or circulated go back to DRAFT; a draft stays as it is.
 */
@Component
class StoredMinutesStages(
    private val store: MinutesStore,
    private val ids: IdGenerator,
    private val audit: AuditLog,
    private val clock: Clock,
) : MinutesStages {
    override fun stage(meetingId: UUID): MinutesStage? = store.find(meetingId)?.state

    override fun returnToDraft(
        meetingId: UUID,
        itemId: UUID,
    ) {
        val row = store.lock(meetingId)?.takeIf { it.state.inReview } ?: return
        check(store.update(row, row.copy(state = MinutesStage.DRAFT))) { "the locked minutes moved on" }
        store.insertEvent(
            MinutesEventRow(
                id = ids.next(),
                meetingId = meetingId,
                kind = MinutesEventKind.RETURNED_TO_DRAFT,
                itemId = itemId,
                actor = actorOrNull(),
                fileVersionId = null,
                sha256 = null,
                at = clock.instant().truncatedTo(ChronoUnit.MICROS),
            ),
        )
        audit.record(
            "minutes.returned_to_draft",
            MinutesDrafts.OBJECT_TYPE,
            meetingId.toString(),
            before = mapOf("state" to row.state.name),
            after = mapOf("state" to MinutesStage.DRAFT.name, "itemId" to itemId.toString()),
        )
    }
}
