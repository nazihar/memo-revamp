package com.memo.minutes

import com.memo.common.events.TenantScopedEvent
import com.memo.forum.OutcomeKind
import com.memo.minutes.internal.CurrentDecision
import com.memo.minutes.internal.DecisionReads
import com.memo.minutes.internal.EntryRow
import com.memo.minutes.internal.ResolutionRow
import com.memo.minutes.internal.ResolutionStore
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Where a decision stands (design E06 §1.1), derived from its number, its release and the minutes' approval. */
enum class DecisionState {
    RECORDED,

    /** Its outcome is a resolution with a number in force (given when the meeting is marked as held, or at once after). */
    NUMBERED,

    /** Sent to its memo (T4). */
    RELEASED,

    /** The minutes were approved (T6). */
    FINAL,
    ;

    companion object {
        fun of(
            releasedAt: Instant?,
            finalAt: Instant?,
            numbered: Boolean = false,
        ): DecisionState =
            when {
                finalAt != null -> FINAL
                releasedAt != null -> RELEASED
                numbered -> NUMBERED
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
    /** CONSENSUS or VOTE (E08's register shows the mode, never the names). */
    val voteMode: String = "CONSENSUS",
    /** Someone dissented by name (the names stay with the decision; E08 indexes only that there was dissent). */
    val dissent: Boolean = false,
    /** The earlier decision this one ratifies (taken without a quorum), if any. */
    val ratifiesDecisionId: UUID? = null,
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
    /** Its resolution number in force ("BRC/2026/014"); null when it has none (yet). */
    val resolutionNumber: String? = null,
    val resolutionId: UUID? = null,
)

/**
 * The decisions for other modules (design E06 §2, E07 design §11): one by id, a meeting's, an item's, and one entry of a
 * decision. Inside a tenant transaction; no access decision is made here. Every new entry (a first record or a
 * correction) is told by [DecisionRecorded].
 */
@Component
class Decisions(
    private val reads: DecisionReads,
    private val resolutions: ResolutionStore,
) {
    /** The decision [decisionId] with its current entry; null when there is none. */
    fun find(decisionId: UUID): DecisionRef? = reads.currentById(decisionId)?.let { refs(listOf(it)).single() }

    /** [meetingId]'s current decisions. */
    fun of(meetingId: UUID): List<DecisionRef> = refs(reads.currentOf(meetingId))

    /** The current decision on [itemId]; null when it has none. */
    fun ofItem(itemId: UUID): DecisionRef? = reads.current(itemId)?.let { refs(listOf(it)).single() }

    /** The current decisions on the submissions of [submissionIds] (a deferred submission is decided again later). */
    fun ofSubmissions(submissionIds: Collection<UUID>): List<DecisionRef> = refs(reads.currentOfSubmissions(submissionIds))

    /** The decisions whose current entry ratifies [decisionId] (taken without a quorum), in id order. */
    fun ratifiers(decisionId: UUID): List<UUID> = reads.ratifiers(listOf(decisionId))[decisionId].orEmpty().sorted()

    /** Up to [limit] decision ids after [after] (from the first when null), in id order: E08's rebuild walks them. */
    fun ids(
        after: UUID?,
        limit: Int,
    ): List<UUID> = reads.ids(after, limit)

    /** Entry [seq] of [decisionId] (a correction's earlier entries stay readable); null when there is none. */
    fun entry(
        decisionId: UUID,
        seq: Int,
    ): DecisionEntryRef? = reads.entries(decisionId).firstOrNull { it.seq == seq }?.ref()

    private fun refs(decisions: List<CurrentDecision>): List<DecisionRef> {
        val numbers = resolutions.inForce(decisions.map { it.decision.id })
        return decisions.map { it.ref(numbers[it.decision.id]) }
    }

    private fun CurrentDecision.ref(number: ResolutionRow?) =
        DecisionRef(
            id = decision.id,
            meetingId = decision.meetingId,
            itemId = decision.itemId,
            forumId = decision.forumId,
            submissionId = decision.submissionId,
            memoId = decision.memoId,
            state = DecisionState.of(decision.releasedAt, decision.finalAt, number != null),
            current = entry.ref(),
            ratificationRequired = entry.ratificationRequired,
            releasedAt = decision.releasedAt,
            finalAt = decision.finalAt,
            resolutionNumber = number?.number,
            resolutionId = number?.id,
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
            voteMode = voteMode.name,
            dissent = dissent.isNotEmpty(),
            ratifiesDecisionId = ratifiesDecisionId,
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
