package com.memo.search.internal.decision

import com.memo.minutes.DecisionRef
import com.memo.minutes.ResolutionRef
import com.memo.minutes.ResolutionState
import com.memo.search.internal.SearchReferences
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** A meeting as its decisions' rows carry it: its label ("BRC 3/2026", once numbered), local date and level's rank. */
data class MeetingFacts(
    val meetingId: UUID,
    val label: String?,
    val date: LocalDate,
    val rank: Int,
)

/**
 * A decided item as its row carries it, from the latest published revision (or the live draft for an item tabled and not
 * published yet): what the register shows and what the item rule reads (ADR-0061). [rank] is the item's own level's.
 */
data class ItemFacts(
    val itemId: UUID,
    val parentId: UUID?,
    val number: String?,
    val title: String,
    val rank: Int,
    val restrictedTo: List<UUID>?,
    val titleHidden: Boolean,
    val membersOnly: Boolean,
)

/** The source memo's type and number (null while it has none). */
data class MemoFacts(
    val typeId: UUID,
    val number: String?,
)

/** Everything one decision's register row is made of, read from the owning modules' published APIs. */
data class DecisionFacts(
    val decision: DecisionRef,
    /** Its number in force, else the last one voided; null when it never had one. */
    val resolution: ResolutionRef?,
    val forumTypeId: UUID,
    val meeting: MeetingFacts,
    val item: ItemFacts,
    val memo: MemoFacts?,
    /** When the first submission of the item's chain was submitted (time to decision). */
    val chainSubmittedAt: Instant?,
    /** DEFERRED decisions taken earlier on the same chain. */
    val priorDeferrals: Int,
    /** The later decision that ratified this one (taken without a quorum), if any. */
    val ratifiedBy: UUID?,
)

/**
 * One row of `search_decision` (design E08 §1.1), as `search_put_decision` takes it. These fields are all the register
 * holds: never the secretary's notes, the minutes' discussion, papers, the natures of interests, the names of dissenters
 * or voters, or quorum override reasons (`DecisionRowTest` keeps the list).
 */
data class DecisionRow(
    val decisionId: UUID,
    val forumId: UUID,
    val forumTypeId: UUID,
    val meetingId: UUID,
    val meetingLabel: String?,
    val meetingDate: LocalDate,
    val itemId: UUID,
    val itemParentId: UUID?,
    val itemNumber: String?,
    /** The higher of the item's and the meeting's level. */
    val classificationRank: Int,
    val itemRestrictedTo: List<UUID>?,
    val itemTitleHidden: Boolean,
    val itemMembersOnly: Boolean,
    val title: String,
    val outcomeKind: String,
    val outcomeLabel: String,
    val isResolution: Boolean,
    val resolutionId: UUID?,
    val resolutionNumber: String?,
    val resolutionState: String?,
    val numberPeriod: String?,
    val numberSequence: Long?,
    val refNorm: String?,
    val refTail: String?,
    val state: String,
    val decidedAt: Instant,
    val finalAt: Instant?,
    val entrySeq: Int,
    val text: String?,
    val conditions: List<String>,
    val validUntil: LocalDate?,
    val hasDissent: Boolean,
    val voteMode: String,
    val ratificationRequired: Boolean,
    val ratifiedBy: UUID?,
    val submissionId: UUID?,
    val memoId: UUID?,
    val memoTypeId: UUID?,
    val memoNumber: String?,
    val memoRefNorm: String?,
    val chainSubmittedAt: Instant?,
    val priorDeferrals: Int,
) {
    /** What the function searches as the row's body (weight C): the outcome's label, the text and the conditions. */
    val body: String get() = DecisionRows.body(outcomeLabel, text, conditions)

    val corrected: Boolean get() = entrySeq > 1
}

/** Makes a register row from a decision's facts (design E08 §1.1). Pure: `DecisionRowTest` covers it. */
object DecisionRows {
    /** At most this many characters of body are searched (design E08 §1.1). */
    const val BODY_MAX = 16_000
    private const val TITLE_MAX = 2_000
    private const val TEXT_MAX = 4_000
    private const val LABEL_MAX = 200

    fun of(facts: DecisionFacts): DecisionRow {
        val d = facts.decision
        val e = d.current
        val number = facts.resolution
        return DecisionRow(
            decisionId = d.id,
            forumId = d.forumId,
            forumTypeId = facts.forumTypeId,
            meetingId = d.meetingId,
            meetingLabel = facts.meeting.label?.take(LABEL_MAX),
            meetingDate = facts.meeting.date,
            itemId = d.itemId,
            itemParentId = facts.item.parentId,
            itemNumber = facts.item.number,
            classificationRank = maxOf(facts.item.rank, facts.meeting.rank),
            itemRestrictedTo = facts.item.restrictedTo,
            itemTitleHidden = facts.item.titleHidden,
            itemMembersOnly = facts.item.membersOnly,
            title = facts.item.title.take(TITLE_MAX),
            outcomeKind = e.outcome.name,
            outcomeLabel = e.outcomeLabel.take(LABEL_MAX),
            isResolution = e.resolution,
            resolutionId = number?.id,
            resolutionNumber = number?.number,
            resolutionState = number?.state?.name,
            numberPeriod = number?.period,
            numberSequence = number?.sequence,
            refNorm = number?.number?.let(SearchReferences::norm),
            refTail = number?.number?.let(SearchReferences::tail),
            state = d.state.name,
            decidedAt = e.decidedAt,
            finalAt = d.finalAt,
            entrySeq = e.seq,
            text = e.text?.take(TEXT_MAX),
            conditions = e.conditions,
            validUntil = e.validUntil,
            hasDissent = e.dissent,
            voteMode = e.voteMode,
            ratificationRequired = d.ratificationRequired,
            ratifiedBy = facts.ratifiedBy,
            submissionId = d.submissionId,
            memoId = d.memoId,
            memoTypeId = facts.memo?.typeId,
            memoNumber = facts.memo?.number,
            memoRefNorm = facts.memo?.number?.let(SearchReferences::norm),
            chainSubmittedAt = facts.chainSubmittedAt,
            priorDeferrals = facts.priorDeferrals,
        )
    }

    /** The number a decision's row shows: the one in force, else the last voided; null when it never had one. */
    fun shown(numbers: List<ResolutionRef>): ResolutionRef? =
        numbers.firstOrNull { it.state == ResolutionState.IN_FORCE } ?: numbers.maxByOrNull { it.numberedAt }

    /** The row's body as the function writes it: label, text and conditions, one per line, at most [BODY_MAX]. */
    fun body(
        label: String,
        text: String?,
        conditions: List<String>,
    ): String = listOfNotNull(label, text, conditions.joinToString("\n").ifEmpty { null }).joinToString("\n").take(BODY_MAX)
}
