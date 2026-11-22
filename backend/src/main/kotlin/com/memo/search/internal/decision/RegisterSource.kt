package com.memo.search.internal.decision

import com.memo.common.authz.Clearances
import com.memo.forum.ForumDirectory
import com.memo.forum.OutcomeKind
import com.memo.forum.SubmissionChains
import com.memo.meeting.AgendaItemRef
import com.memo.meeting.MeetingItems
import com.memo.meeting.MeetingRef
import com.memo.meeting.Meetings
import com.memo.memo.MemoSearchSource
import com.memo.minutes.DecisionRef
import com.memo.minutes.Decisions
import com.memo.minutes.Resolutions
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Reads what a decision's register row is made of (design E08 §1.1, §11) from the owning modules' published APIs, inside
 * the caller's tenant transaction (the change's, or the backfill's): the decision and its current entry (minutes), its
 * number, the meeting and the decided item as the latest revision carries it (meeting), the committee's type and the
 * submission's chain (forum), the memo's type and number (memo). No access decision is made here.
 */
@Component
class RegisterSource(
    private val decisions: Decisions,
    private val resolutions: Resolutions,
    private val meetings: Meetings,
    private val items: MeetingItems,
    private val directory: ForumDirectory,
    private val clearances: Clearances,
    private val memos: MemoSearchSource,
    private val chains: SubmissionChains,
) {
    /** [decisionId]'s row as it is now; null when there is no such decision (or its meeting or item is gone). */
    fun row(decisionId: UUID): DecisionRow? = decisions.find(decisionId)?.let(::row)

    private fun row(decision: DecisionRef): DecisionRow? {
        val meeting = meetings.find(decision.meetingId)
        val forumType = directory.forum(decision.forumId)?.typeId
        if (meeting == null || forumType == null) return null
        val ranks = ranks()
        val chain = decision.submissionId?.let { chains.chain(it) }.orEmpty()
        return items.agenda(meeting.id).item(decision.itemId)?.let { item ->
            DecisionRows.of(
                DecisionFacts(
                    decision = decision,
                    resolution = DecisionRows.shown(resolutions.ofDecision(decision.id)),
                    forumTypeId = forumType,
                    meeting = meetingFacts(meeting, ranks),
                    item = itemFacts(item, ranks),
                    memo = decision.memoId?.let { memos.facts(it) }?.let { MemoFacts(it.typeId, it.reference) },
                    chainSubmittedAt = decision.submissionId?.let { chains.chainStart(it) },
                    priorDeferrals = priorDeferrals(decision, chain),
                    ratifiedBy = decisions.ratifiers(decision.id).firstOrNull(),
                ),
            )
        }
    }

    /**
     * [meetingId]'s columns and its decided items' columns as the latest revision carries them (for an agenda revision or a
     * meeting change); null when the meeting has no decision.
     */
    fun meetingColumns(meetingId: UUID): MeetingColumns? {
        val decided = decisions.of(meetingId).map { it.itemId }.toSet()
        val meeting = meetings.find(meetingId)?.takeIf { decided.isNotEmpty() } ?: return null
        val ranks = ranks()
        val facts = meetingFacts(meeting, ranks)
        val decidedItems =
            items
                .agenda(meetingId)
                .items
                .filter { it.id in decided }
                .map { itemFacts(it, ranks) }
        return MeetingColumns(facts, decidedItems.map { it to maxOf(it.rank, facts.rank) })
    }

    /** How many decisions taken before [decision] on its chain (its submission and those before it) were DEFERRED. */
    private fun priorDeferrals(
        decision: DecisionRef,
        chain: List<UUID>,
    ): Int =
        if (chain.isEmpty()) {
            0
        } else {
            decisions.ofSubmissions(chain).count {
                it.id != decision.id && it.current.outcome == OutcomeKind.DEFERRED && it.current.decidedAt < decision.current.decidedAt
            }
        }

    private fun ranks(): Map<UUID, Int> = clearances.levels().associate { it.id to it.rank }

    private fun meetingFacts(
        meeting: MeetingRef,
        ranks: Map<UUID, Int>,
    ) = MeetingFacts(
        meetingId = meeting.id,
        label = meeting.label,
        date = meeting.startsAt.atZone(meeting.timeZone).toLocalDate(),
        rank = rank(ranks, meeting.classificationId),
    )

    private fun itemFacts(
        item: AgendaItemRef,
        ranks: Map<UUID, Int>,
    ) = ItemFacts(
        itemId = item.id,
        parentId = item.parentId,
        number = item.number,
        title = item.title,
        rank = rank(ranks, item.classificationId),
        restrictedTo = item.restrictedTo,
        titleHidden = item.titleHidden,
        membersOnly = item.membersOnly,
    )

    /** A level's rank; an unknown level ranks above every clearance (fail closed: nobody sees the row). */
    private fun rank(
        ranks: Map<UUID, Int>,
        level: UUID,
    ): Int = ranks[level] ?: UNKNOWN_LEVEL

    private companion object {
        const val UNKNOWN_LEVEL = 1_000_000
    }
}

/** A meeting's columns and its decided items' columns with each item's effective rank (the higher of item and meeting). */
data class MeetingColumns(
    val meeting: MeetingFacts,
    val items: List<Pair<ItemFacts, Int>>,
)
