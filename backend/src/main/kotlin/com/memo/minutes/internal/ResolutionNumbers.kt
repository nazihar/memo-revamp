package com.memo.minutes.internal

import com.memo.audit.AuditLog
import com.memo.common.id.IdGenerator
import com.memo.common.tenant.TenantContext
import com.memo.common.web.refuseIf
import com.memo.forum.ForumDirectory
import com.memo.forum.ForumPolicies
import com.memo.forum.ResolutionFormats
import com.memo.forum.ResolutionNumbering
import com.memo.forum.ResolutionPartKind
import com.memo.meeting.MeetingRef
import com.memo.meeting.Meetings
import com.memo.minutes.ResolutionChange
import com.memo.minutes.ResolutionChanged
import com.memo.minutes.ResolutionState
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Taking and voiding resolution numbers (ADR-0065, design E06 §1.3), inside the business transaction, the decision's row
 * locked by the caller. The number comes from the committee's counter for the meeting's period (its date in its zone, by
 * the committee type's format pinned on the meeting), locked to commit, so numbers are gapless and a rollback gives the
 * number back. A number belongs to its decision for ever: a correction to an outcome that is not a resolution voids it, and
 * a decision that becomes a resolution again takes a new one. Audited on the meeting; [ResolutionChanged] for E08.
 */
@Service
class ResolutionNumbers(
    private val store: ResolutionStore,
    private val counters: ResolutionCounters,
    private val meetings: Meetings,
    private val directory: ForumDirectory,
    private val policies: ForumPolicies,
    private val audit: AuditLog,
    private val events: ApplicationEventPublisher,
    private val ids: IdGenerator,
    private val clock: Clock,
) {
    /**
     * Gives [decision] its number when its current outcome is a resolution and it has none in force (its number otherwise;
     * null when it needs none). 409 `NUMBER_TAKEN` when the format renders a number the tenant has (only after a format
     * change made two formats write the same text): nothing commits.
     */
    fun assign(decision: CurrentDecision): ResolutionRow? {
        if (!decision.entry.resolution) return null
        return store.inForce(decision.decision.id) ?: take(decision)
    }

    /** A new number for [decision] from its committee's counter, written and audited. */
    private fun take(decision: CurrentDecision): ResolutionRow {
        val meeting = checkNotNull(meetings.find(decision.decision.meetingId)) { "meeting ${decision.decision.meetingId}" }
        val forum = checkNotNull(directory.forum(decision.decision.forumId)) { "forum ${decision.decision.forumId}" }
        val numbering = numbering(forum.typeId, meeting)
        refuseIf(meeting.number == null && numbering.parts.any { it.kind == ResolutionPartKind.MEETING_NUMBER }, "WRONG_STATE") {
            "The meeting has no number yet: its agenda was never published."
        }
        val date = meeting.date()
        val period = ResolutionFormats.period(numbering, date)
        val sequence = counters.next(forum.id, period)
        val number = ResolutionFormats.render(numbering, forum.code, meeting.number, date, sequence)
        refuseIf(store.taken(number), "NUMBER_TAKEN") {
            "Resolution number $number is already taken in the organisation: check the committee type's numbering."
        }
        val row =
            ResolutionRow(
                id = ids.next(),
                decisionId = decision.decision.id,
                forumId = forum.id,
                meetingId = meeting.id,
                period = period,
                sequence = sequence,
                number = number,
                numberedAt = clock.instant().truncatedTo(ChronoUnit.MICROS),
                state = ResolutionState.IN_FORCE,
                voidReason = null,
                voidedAt = null,
                voidedByEntrySeq = null,
            )
        store.insert(row)
        audit.record(
            "minutes.resolution.numbered",
            DecisionEffects.OBJECT_TYPE,
            meeting.id.toString(),
            after =
                mapOf(
                    "resolutionId" to row.id.toString(),
                    "decisionId" to decision.decision.id.toString(),
                    "itemId" to decision.decision.itemId.toString(),
                    "number" to number,
                    "period" to period,
                    "sequence" to sequence,
                ),
        )
        changed(decision.decision.id, row.id, ResolutionChange.NUMBERED)
        return row
    }

    /**
     * Voids [decision]'s number in force when its current entry (a correction) is not a resolution, with the correction's
     * reason; the number is never given again. Null when there was nothing to void.
     */
    fun voidIfNeeded(decision: CurrentDecision): ResolutionRow? {
        val entry = decision.entry
        val row = store.inForce(decision.decision.id)?.takeIf { !entry.resolution && entry.correction } ?: return null
        val reason = checkNotNull(entry.reason) { "a correction has a reason" }
        val at = clock.instant().truncatedTo(ChronoUnit.MICROS)
        // The caller holds the decision's row: the number just read in force is still so.
        check(store.void(row.id, reason, at, entry.seq)) { "resolution ${row.number} was in force" }
        audit.record(
            "minutes.resolution.voided",
            DecisionEffects.OBJECT_TYPE,
            decision.decision.meetingId.toString(),
            before = mapOf("state" to ResolutionState.IN_FORCE.name),
            after =
                mapOf(
                    "resolutionId" to row.id.toString(),
                    "decisionId" to decision.decision.id.toString(),
                    "number" to row.number,
                    "seq" to entry.seq,
                    "kind" to entry.outcomeKind.name,
                    "reasonSha256" to DecisionRules.sha256(reason),
                ),
        )
        changed(decision.decision.id, row.id, ResolutionChange.VOIDED)
        return store.find(row.id)
    }

    /** The format pinned on [meeting] (the committee type's version), or the default. */
    fun numbering(
        typeId: UUID,
        meeting: MeetingRef,
    ): ResolutionNumbering = policies.version(typeId, meeting.typeVersion)?.minutes?.resolutionNumbering ?: ResolutionNumbering()

    private fun changed(
        decisionId: UUID,
        resolutionId: UUID,
        change: ResolutionChange,
    ) = events.publishEvent(ResolutionChanged(TenantContext.current().value, decisionId, resolutionId, change))
}
