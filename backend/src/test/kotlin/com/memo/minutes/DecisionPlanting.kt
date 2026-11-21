package com.memo.minutes

import com.memo.common.tenant.TenantId
import com.memo.common.tenant.TenantTransactions
import com.memo.forum.OutcomeKind
import com.memo.minutes.internal.DecisionRow
import com.memo.minutes.internal.DecisionStore
import com.memo.minutes.internal.Dissent
import com.memo.minutes.internal.EntryRow
import com.memo.minutes.internal.NoteRow
import com.memo.minutes.internal.NoteStore
import com.memo.minutes.internal.VoteMode
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Decisions and notes written straight through the stores, for the leak suites (E04 T5's `PaperLeakIT` and
 * `RecusalLeakIT`): their planted meetings are scheduled weeks ahead, where no decision can be recorded, so the rows a
 * real meeting would have are set up as the app role in the tenant (as `PaperLeakIT` does for a quorum snapshot).
 */
class DecisionPlanting(
    private val transactions: TenantTransactions,
    private val store: DecisionStore,
    private val notes: NoteStore,
) {
    /** A decision APPROVED on [itemId] with a vote 3–1–1, [dissent] with a reason, [abstaining] and [tookNoPart]. */
    fun decision(
        tenant: TenantId,
        meetingId: UUID,
        forumId: UUID,
        itemId: UUID,
        by: UUID,
        text: String,
        dissent: Dissent? = null,
        abstaining: UUID? = null,
        tookNoPart: List<UUID> = emptyList(),
    ): UUID {
        val id = UUID.randomUUID()
        val now = Instant.now().truncatedTo(ChronoUnit.MICROS)
        val head = DecisionRow(id, meetingId, itemId, forumId, null, null, 1, null, null, null, 0)
        val entry =
            EntryRow(
                decisionId = id,
                seq = 1,
                outcomeKind = OutcomeKind.APPROVED,
                outcomeLabel = "Approved",
                resolution = true,
                text = text,
                conditions = listOf("Security perfected before disbursement"),
                validUntil = null,
                deferTo = null,
                referToForumId = null,
                referReason = null,
                voteMode = VoteMode.VOTE,
                votesFor = 3,
                votesAgainst = 1,
                votesAbstain = 1,
                dissent = listOfNotNull(dissent),
                abstentions = listOfNotNull(abstaining),
                tookNoPart = tookNoPart,
                snapshotId = UUID.randomUUID(),
                ratificationRequired = false,
                ratifiesDecisionId = null,
                decidedAt = now,
                consentBatchId = null,
                correction = false,
                reason = null,
                recordedBy = by,
                recordedAt = now,
            )
        transactions.inTenant(tenant) { check(store.insert(head, entry)) }
        return id
    }

    /** The secretary's notes on [itemId]: [text] as version 1. */
    fun note(
        tenant: TenantId,
        meetingId: UUID,
        itemId: UUID,
        by: UUID,
        text: String,
    ) {
        transactions.inTenant(tenant) {
            check(notes.insert(NoteRow(meetingId, itemId, 1, text, by, Instant.now().truncatedTo(ChronoUnit.MICROS))))
        }
    }
}
