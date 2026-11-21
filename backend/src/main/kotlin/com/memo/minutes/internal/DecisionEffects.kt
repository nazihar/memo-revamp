package com.memo.minutes.internal

import com.memo.audit.AuditLog
import com.memo.common.tenant.TenantContext
import com.memo.meeting.QuorumSnapshot
import com.memo.minutes.DecisionRecorded
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * What follows a decision change (design E06 §2, §5), in its transaction: the audit entry with ids, kinds, counts and the
 * SHA-256 of what people wrote (never the text), and [DecisionRecorded] for E07 and E08.
 */
@Component
class DecisionEffects(
    private val audit: AuditLog,
    private val events: ApplicationEventPublisher,
) {
    /** [recorded] was written; [before] is the entry it corrects (null for a first record), [snapshot] its quorum. */
    fun recorded(
        recorded: CurrentDecision,
        before: EntryRow?,
        snapshot: QuorumSnapshot,
    ) {
        val entry = recorded.entry
        val after =
            mapOf(
                "decisionId" to recorded.decision.id.toString(),
                "itemId" to recorded.decision.itemId.toString(),
                "seq" to entry.seq,
                "kind" to entry.outcomeKind.name,
                "resolution" to entry.resolution,
                "snapshotId" to entry.snapshotId.toString(),
                "quorate" to snapshot.quorate,
                "override" to (snapshot.override != null),
                "voteMode" to entry.voteMode.name,
                "votes" to mapOf("for" to entry.votesFor, "against" to entry.votesAgainst, "abstain" to entry.votesAbstain),
                "dissent" to entry.dissent.map { it.userId.toString() },
                "abstentions" to entry.abstentions.map { it.toString() },
                "tookNoPart" to entry.tookNoPart.map { it.toString() },
                "ratifiesDecisionId" to entry.ratifiesDecisionId?.toString(),
                "consentBatchId" to entry.consentBatchId?.toString(),
                "textSha256" to entry.text?.let(DecisionRules::sha256),
                "conditionsSha256" to
                    entry.conditions
                        .takeIf { it.isNotEmpty() }
                        ?.joinToString("\n")
                        ?.let(DecisionRules::sha256),
            )
        if (before == null) {
            audit.record("minutes.decision.recorded", OBJECT_TYPE, recorded.decision.meetingId.toString(), after = after)
        } else {
            audit.record(
                "minutes.decision.corrected",
                OBJECT_TYPE,
                recorded.decision.meetingId.toString(),
                before = mapOf("seq" to before.seq, "kind" to before.outcomeKind.name),
                after = after + ("reasonSha256" to entry.reason?.let(DecisionRules::sha256)),
            )
        }
        val tenant = TenantContext.current().value
        events.publishEvent(
            DecisionRecorded(tenant, recorded.decision.meetingId, recorded.decision.itemId, recorded.decision.id, entry.seq),
        )
    }

    /** A consent section was decided in one action (design E06 §1.1): the batch and its items, in agenda order. */
    fun consentDecided(
        meetingId: UUID,
        sectionId: UUID,
        batchId: UUID,
        itemIds: List<UUID>,
    ) = audit.record(
        "minutes.consent.decided",
        OBJECT_TYPE,
        meetingId.toString(),
        after = mapOf("sectionId" to sectionId.toString(), "batchId" to batchId.toString(), "items" to itemIds.map { it.toString() }),
    )

    /** A version of the notes on an item was saved (design E06 §1.2): its number and hash, never the text. */
    fun notesSaved(row: NoteRow) =
        audit.record(
            "minutes.notes.saved",
            OBJECT_TYPE,
            row.meetingId.toString(),
            after = mapOf("itemId" to row.itemId.toString(), "seq" to row.seq, "sha256" to DecisionRules.sha256(row.text)),
        )

    companion object {
        /** The decisions are audited on their meeting, as the rest of its record is. */
        const val OBJECT_TYPE = "meeting"
    }
}
