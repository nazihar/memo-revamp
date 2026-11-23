package com.memo.search.internal.decision

import com.memo.common.authz.Clearances
import com.memo.forum.ForumDirectory
import com.memo.forum.OutcomeKind
import com.memo.forum.SubmissionChains
import com.memo.meeting.AgendaItemRef
import com.memo.meeting.MeetingAgendaRef
import com.memo.meeting.MeetingItems
import com.memo.meeting.MeetingRef
import com.memo.meeting.Meetings
import com.memo.memo.MemoSearchSource
import com.memo.minutes.DecisionRef
import com.memo.minutes.Decisions
import com.memo.minutes.Resolutions
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
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

    /**
     * Each of [decisionIds]' rows as [row] makes it (a backfill chunk), their meetings' agendas read together (seven
     * queries for them all) instead of once a meeting; null for one with no row.
     */
    fun rows(decisionIds: List<UUID>): Map<UUID, DecisionRow?> {
        val refs = decisionIds.associateWith { decisions.find(it) }
        TransactionReads.current().agendas(refs.values.mapNotNull { it?.meetingId }) { items.agendas(it) }
        return refs.mapValues { (_, ref) -> ref?.let(::row) }
    }

    private fun row(decision: DecisionRef): DecisionRow? {
        val read = TransactionReads.current()
        val meeting = read.meeting(decision.meetingId) { meetings.find(it) }
        val forumType = directory.forum(decision.forumId)?.typeId
        if (meeting == null || forumType == null) return null
        val ranks = read.ranks { ranks() }
        val chain = decision.submissionId?.let { chains.chain(it) }.orEmpty()
        return read.agenda(meeting.id) { items.agenda(it) }.item(decision.itemId)?.let { item ->
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
        // The agenda or the meeting changed in this transaction: what an earlier decision's row read of them is stale.
        TransactionReads.current().forget(meetingId)
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

/**
 * What one transaction's rows read once (design E08 §8, E06 T9's consent section): a meeting's agenda and row and the
 * classification levels, which recording a decision does not change, so a consent section's dozen decisions (or a
 * backfill chunk's decisions of one meeting) read them once instead of once each. Bound to the transaction and dropped
 * when it completes; an agenda revision or a meeting change in the same transaction forgets its meeting
 * ([RegisterSource.meetingColumns]). Outside a transaction nothing is kept.
 */
private class TransactionReads {
    private val meetings = HashMap<UUID, MeetingRef?>()
    private val agendas = HashMap<UUID, MeetingAgendaRef>()
    private var levels: Map<UUID, Int>? = null

    fun meeting(
        id: UUID,
        load: (UUID) -> MeetingRef?,
    ): MeetingRef? = meetings.getOrPut(id) { load(id) }

    fun agenda(
        id: UUID,
        load: (UUID) -> MeetingAgendaRef,
    ): MeetingAgendaRef = agendas.getOrPut(id) { load(id) }

    /** Reads the agendas of [ids] not read yet, all together. */
    fun agendas(
        ids: Collection<UUID>,
        load: (Collection<UUID>) -> Map<UUID, MeetingAgendaRef>,
    ) {
        val missing = ids.filter { it !in agendas }.distinct()
        if (missing.isNotEmpty()) agendas.putAll(load(missing))
    }

    fun ranks(load: () -> Map<UUID, Int>): Map<UUID, Int> = levels ?: load().also { levels = it }

    fun forget(meetingId: UUID) {
        meetings.remove(meetingId)
        agendas.remove(meetingId)
    }

    companion object {
        private val KEY = Any()

        /** This transaction's reads, bound on first use; a fresh, unbound one outside a transaction. */
        fun current(): TransactionReads {
            if (!TransactionSynchronizationManager.isSynchronizationActive()) return TransactionReads()
            (TransactionSynchronizationManager.getResource(KEY) as TransactionReads?)?.let { return it }
            val reads = TransactionReads()
            TransactionSynchronizationManager.bindResource(KEY, reads)
            TransactionSynchronizationManager.registerSynchronization(
                object : TransactionSynchronization {
                    override fun afterCompletion(status: Int) {
                        TransactionSynchronizationManager.unbindResourceIfPossible(KEY)
                    }
                },
            )
            return reads
        }
    }
}

/** A meeting's columns and its decided items' columns with each item's effective rank (the higher of item and meeting). */
data class MeetingColumns(
    val meeting: MeetingFacts,
    val items: List<Pair<ItemFacts, Int>>,
)
