package com.memo.forum

import com.memo.common.web.refuseIf
import com.memo.forum.internal.ForumNotFoundException
import com.memo.forum.internal.ForumSubmissions
import com.memo.forum.internal.SubmissionPlacementStore
import com.memo.forum.internal.SubmissionRow
import com.memo.forum.internal.SubmissionStore
import com.memo.forum.internal.SubmissionSupport
import com.memo.forum.internal.SubmissionTriage
import com.memo.forum.internal.actor
import com.memo.workflow.ForumOutcome
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.util.UUID

/**
 * A committee's decision at a meeting sent to the submission it was about (ADR-0065 "Back to the memo", design E06 §1.4):
 * the minutes module records decisions and calls these when it releases one. Each takes the submission's row first, as
 * every change that may place or unplace a submission does (design E03 §1.8), then moves it:
 *  - [decide]: DECIDED, and the memo waiting at the forum step resumes with the outcome, the remark and [details];
 *  - [deferAtMeeting]: DEFERRED with a target, off the meeting's agenda (back to the secretaries' queue); the memo waits;
 *  - [referAtMeeting]: REFERRED, with a new submission to the other committee; the memo waits for that one.
 * Only a QUEUED or ACCEPTED submission moves (409 `WRONG_STATE` otherwise), so a second release changes nothing. The caller
 * has checked who may decide. Inside the caller's tenant transaction, with a signed-in user as the actor.
 */
@Component
class ForumDecisions(
    private val store: SubmissionStore,
    private val placements: SubmissionPlacementStore,
    private val submissions: ForumSubmissions,
    private val triage: SubmissionTriage,
    private val support: SubmissionSupport,
) {
    /**
     * [outcome] (APPROVED, APPROVED_WITH_CONDITIONS, NOTED, REJECTED or RETURNED; a decision withdrawn at the meeting is
     * sent as RETURNED by the caller) with [remark] (at most 4,000 characters) and [details] for the FORUM_DECIDED action.
     * A submission that does not hold its memo ("for information") is closed the same way; nothing waits on it.
     */
    fun decide(
        submissionId: UUID,
        outcome: OutcomeKind,
        remark: String?,
        details: Map<String, Any?> = emptyMap(),
    ): SubmissionRef {
        val decided = DECIDED[outcome]
        require(decided != null) { "$outcome is sent with deferAtMeeting or referAtMeeting, or as RETURNED" }
        placements.lock(listOf(submissionId))
        return submissions.decide(submissionId, decided, remark, details).ref()
    }

    /** Deferred at the meeting with [reason], for a meeting on or after [targetDate] (null: the next one). */
    fun deferAtMeeting(
        submissionId: UUID,
        reason: String,
        targetDate: LocalDate?,
    ): SubmissionRef {
        val row = locked(submissionId)
        val text = support.requiredReason(reason)
        val fields =
            SubmissionStore.triaged(actor(), store.now(), text) + SubmissionStore.target(targetDate) + SubmissionStore.unplaced()
        support.move(row, SubmissionStatus.DEFERRED, row.version, fields)
        return support.done("forum.submission.deferred", row).ref()
    }

    /** Referred at the meeting to [toForumId] with [reason]: 422 for a committee that is not active, 409 `SAME_FORUM`. */
    fun referAtMeeting(
        submissionId: UUID,
        toForumId: UUID,
        reason: String,
    ): SubmissionRef {
        val row = locked(submissionId)
        return triage.referred(row, row.version, toForumId, support.requiredReason(reason)).ref()
    }

    private fun locked(submissionId: UUID): SubmissionRow {
        placements.lock(listOf(submissionId))
        val row = store.find(submissionId) ?: throw ForumNotFoundException("submission")
        refuseIf(row.status != SubmissionStatus.ACCEPTED && row.status != SubmissionStatus.QUEUED, "WRONG_STATE") {
            "Only a queued or accepted submission can be decided at a meeting."
        }
        return row
    }

    private companion object {
        val DECIDED =
            mapOf(
                OutcomeKind.APPROVED to ForumOutcome.APPROVED,
                OutcomeKind.APPROVED_WITH_CONDITIONS to ForumOutcome.APPROVED_WITH_CONDITIONS,
                OutcomeKind.NOTED to ForumOutcome.NOTED,
                OutcomeKind.REJECTED to ForumOutcome.REJECTED,
                OutcomeKind.RETURNED to ForumOutcome.RETURNED,
            )
    }
}
