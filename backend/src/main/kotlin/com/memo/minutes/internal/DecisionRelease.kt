package com.memo.minutes.internal

import com.memo.audit.AuditLog
import com.memo.common.concurrency.ConcurrentModificationException
import com.memo.common.tenant.TenantContext
import com.memo.forum.ForumDecisions
import com.memo.forum.SubmissionPlacements
import com.memo.forum.SubmissionStatus
import com.memo.meeting.Meetings
import com.memo.memo.MemoSystemNotes
import com.memo.minutes.DecisionReleased
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.UUID

/** What a release did. */
enum class ReleaseResult {
    /** The submission was decided, deferred or referred; the decision is marked released. */
    RELEASED,

    /** Released before: nothing changed (a retry, a second trigger). */
    ALREADY,

    /** Nothing to release to: an item without a submission (a secretary's or procedural item). */
    NOTHING,

    /** The submission no longer waits at the meeting (withdrawn or closed meanwhile): nothing changed. */
    NOT_WAITING,
}

/**
 * The way back to the memo (ADR-0065 "Back to the memo", design E06 §1.4): one decision sent to its submission through the
 * forum module's `ForumDecisions` ([ReleaseMapping]), in the caller's transaction, idempotent on `released_at`. The
 * decision's row is locked first, then `ForumDecisions` locks the submission's (E03's lock order: the decision row, the
 * submission rows, the meeting row). The remark carries the outcome's label, the resolution number and text and the
 * conditions; the FORUM_DECIDED action's details the number, the decision and the meeting, so the note sheet links the
 * resolution. The forum module acts for a signed-in user: the recorder of the decision when the `minutes-release` job runs
 * it. Audited `minutes.decision.released`; [DecisionReleased] for E08.
 */
@Service
class DecisionRelease(
    private val store: DecisionStore,
    private val reads: DecisionReads,
    private val resolutions: ResolutionStore,
    private val forum: ForumDecisions,
    private val placements: SubmissionPlacements,
    private val meetings: Meetings,
    private val notes: MemoSystemNotes,
    private val audit: AuditLog,
    private val events: ApplicationEventPublisher,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** Releases [decisionId] (its row locked here). */
    fun release(
        decisionId: UUID,
        trigger: ReleaseTrigger,
    ): ReleaseResult {
        val row = store.lockById(decisionId) ?: return ReleaseResult.NOTHING
        return releaseLocked(row, trigger)
    }

    /** Releases [row], whose decision row the caller holds locked. */
    fun releaseLocked(
        row: DecisionRow,
        trigger: ReleaseTrigger,
    ): ReleaseResult = skipped(row) ?: send(row, trigger)

    /** Why [row] is not sent: released already, no submission, or a submission no longer waiting at the meeting. */
    private fun skipped(row: DecisionRow): ReleaseResult? {
        val submission = row.submissionId?.let(placements::find)
        return when {
            row.releasedAt != null -> {
                ReleaseResult.ALREADY
            }

            row.submissionId == null -> {
                ReleaseResult.NOTHING
            }

            submission == null || submission.status !in WAITING -> {
                log.warn("Decision {} not released: its submission {} is {}", row.id, row.submissionId, submission?.status)
                ReleaseResult.NOT_WAITING
            }

            else -> {
                null
            }
        }
    }

    /** Sends [row] to its waiting submission and marks it released. */
    private fun send(
        row: DecisionRow,
        trigger: ReleaseTrigger,
    ): ReleaseResult {
        val submissionId = checkNotNull(row.submissionId)
        val submission = checkNotNull(placements.find(submissionId))
        val current = checkNotNull(reads.currentById(row.id)) { "decision ${row.id}" }
        val meeting = meetings.find(row.meetingId)
        val number = resolutions.inForce(row.id)
        val command = ReleaseMapping.command(released(current.entry), number?.number, submission.waits, meeting?.label)
        val details =
            mapOf(
                "resolution" to number?.number,
                "resolutionId" to number?.id?.toString(),
                "decisionId" to row.id.toString(),
                "meetingId" to row.meetingId.toString(),
                "meetingLabel" to meeting?.label,
                "outcomeLabel" to current.entry.outcomeLabel,
            ).filterValues { it != null }
        when (command) {
            is ReleaseCommand.Decide -> forum.decide(submissionId, command.outcome, command.remark, details)
            is ReleaseCommand.Defer -> forum.deferAtMeeting(submissionId, command.reason, command.targetDate)
            is ReleaseCommand.Refer -> forum.referAtMeeting(submissionId, command.forumId, command.reason)
        }
        val now = clock.instant().truncatedTo(ChronoUnit.MICROS)
        if (!store.released(row, command.kind, now)) throw ConcurrentModificationException("decision ${row.id}", row.version)
        audit.record(
            "minutes.decision.released",
            DecisionEffects.OBJECT_TYPE,
            row.meetingId.toString(),
            after =
                mapOf(
                    "decisionId" to row.id.toString(),
                    "itemId" to row.itemId.toString(),
                    "submissionId" to submissionId.toString(),
                    "seq" to current.entry.seq,
                    "kind" to current.entry.outcomeKind.name,
                    "sent" to command.kind.name,
                    "resolution" to number?.number,
                    "trigger" to trigger.name,
                ),
        )
        events.publishEvent(DecisionReleased(TenantContext.current().value, row.meetingId, row.id))
        return ReleaseResult.RELEASED
    }

    /**
     * A released decision was corrected (its wording, conditions, validity, vote or names; the outcome is fixed): its memo's
     * note sheet gets "The committee's decision was corrected on {date}: …" with the new wording, when the wording, the
     * conditions or the validity changed (ADR-0065).
     */
    fun corrected(
        decision: CurrentDecision,
        before: EntryRow,
    ) {
        val memoId = decision.decision.memoId
        val after = decision.entry
        val reworded = after.text != before.text || after.conditions != before.conditions || after.validUntil != before.validUntil
        if (memoId == null || decision.decision.releasedAt == null || !reworded) return
        val number = resolutions.inForce(decision.decision.id)?.number
        val date = DAY.format(after.recordedAt.atZone(ZoneOffset.UTC))
        val text = "The committee's decision was corrected on $date: " + ReleaseMapping.remark(released(after), number)
        notes.forumDecisionCorrected(memoId, ReleaseMapping.cut(text, ReleaseMapping.MAX_REMARK))
    }

    /** The entry as the release reads it: a deferral's target as a date (a named meeting's, or none for the next one). */
    private fun released(entry: EntryRow) =
        ReleaseMapping.Released(
            kind = entry.outcomeKind,
            label = entry.outcomeLabel,
            text = entry.text,
            conditions = entry.conditions,
            deferDate = entry.deferTo?.let { it.date ?: it.meetingId?.let(meetings::find)?.date() },
            referToForumId = entry.referToForumId,
            referReason = entry.referReason,
        )

    private companion object {
        /** `ForumDecisions` moves a queued or accepted submission only. */
        val WAITING = setOf(SubmissionStatus.QUEUED, SubmissionStatus.ACCEPTED)
        val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)
    }
}

/** Why a release runs: the meeting was marked as held, its minutes were approved, or a decision came after the end. */
enum class ReleaseTrigger {
    HELD,
    APPROVED,
    AT_ONCE,
}
