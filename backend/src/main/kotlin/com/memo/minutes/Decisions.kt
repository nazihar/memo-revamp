package com.memo.minutes

import com.memo.common.events.TenantScopedEvent
import com.memo.forum.OutcomeKind
import com.memo.minutes.internal.CurrentDecision
import com.memo.minutes.internal.DecisionReads
import com.memo.minutes.internal.EntryRow
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Where a decision stands (design E06 §1.1), derived: NUMBERED comes with T4's resolutions. */
enum class DecisionState {
    RECORDED,

    /** Sent to its memo (T4). */
    RELEASED,

    /** The minutes were approved (T6). */
    FINAL,
    ;

    companion object {
        fun of(
            releasedAt: Instant?,
            finalAt: Instant?,
        ): DecisionState =
            when {
                finalAt != null -> FINAL
                releasedAt != null -> RELEASED
                else -> RECORDED
            }
    }
}

/**
 * One entry of a decision as other modules read it (E07 raises actions from an entry's conditions, by their index): the
 * first is the decision as recorded, each later one a correction. Business content: the caller decides who reads it.
 */
data class DecisionEntryRef(
    val decisionId: UUID,
    val seq: Int,
    val outcome: OutcomeKind,
    val outcomeLabel: String,
    val resolution: Boolean,
    val text: String?,
    val conditions: List<String>,
    val validUntil: LocalDate?,
    val decidedAt: Instant,
    val correction: Boolean,
    val recordedBy: UUID,
    val recordedAt: Instant,
)

/**
 * An item's decision as other modules read it (design E06 §2; E07 design §11: actions are raised from a decision or one
 * of its conditions, E08 indexes it), with its current entry. The [current] entry's text and conditions are business
 * content: the caller decides who may read them.
 */
data class DecisionRef(
    val id: UUID,
    val meetingId: UUID,
    val itemId: UUID,
    val forumId: UUID,
    val submissionId: UUID?,
    val memoId: UUID?,
    val state: DecisionState,
    val current: DecisionEntryRef,
    /** Taken under E05's quorum override: to be ratified at a quorate meeting. */
    val ratificationRequired: Boolean,
    val releasedAt: Instant?,
    val finalAt: Instant?,
)

/**
 * The decisions for other modules (design E06 §2, E07 design §11): one by id, a meeting's, an item's, and one entry of a
 * decision. Inside a tenant transaction; no access decision is made here. Every new entry (a first record or a
 * correction) is told by [DecisionRecorded].
 */
@Component
class Decisions(
    private val reads: DecisionReads,
) {
    /** The decision [decisionId] with its current entry; null when there is none. */
    fun find(decisionId: UUID): DecisionRef? = reads.currentById(decisionId)?.ref()

    /** [meetingId]'s current decisions. */
    fun of(meetingId: UUID): List<DecisionRef> = reads.currentOf(meetingId).map { it.ref() }

    /** The current decision on [itemId]; null when it has none. */
    fun ofItem(itemId: UUID): DecisionRef? = reads.current(itemId)?.ref()

    /** Entry [seq] of [decisionId] (a correction's earlier entries stay readable); null when there is none. */
    fun entry(
        decisionId: UUID,
        seq: Int,
    ): DecisionEntryRef? = reads.entries(decisionId).firstOrNull { it.seq == seq }?.ref()

    private fun CurrentDecision.ref() =
        DecisionRef(
            id = decision.id,
            meetingId = decision.meetingId,
            itemId = decision.itemId,
            forumId = decision.forumId,
            submissionId = decision.submissionId,
            memoId = decision.memoId,
            state = DecisionState.of(decision.releasedAt, decision.finalAt),
            current = entry.ref(),
            ratificationRequired = entry.ratificationRequired,
            releasedAt = decision.releasedAt,
            finalAt = decision.finalAt,
        )

    private fun EntryRow.ref() =
        DecisionEntryRef(
            decisionId = decisionId,
            seq = seq,
            outcome = outcomeKind,
            outcomeLabel = outcomeLabel,
            resolution = resolution,
            text = text,
            conditions = conditions,
            validUntil = validUntil,
            decidedAt = decidedAt,
            correction = correction,
            recordedBy = recordedBy,
            recordedAt = recordedAt,
        )
}

/**
 * A decision got a new entry on an item (design E06 §2): recorded for the first time ([seq] 1) or corrected ([seq] > 1),
 * one event per entry (a consent section's items each), published in the recording transaction for E07 and E08. Ids only
 * (ADR-0017).
 */
data class DecisionRecorded(
    override val tenantId: UUID,
    val meetingId: UUID,
    val itemId: UUID,
    val decisionId: UUID,
    val seq: Int,
) : TenantScopedEvent
