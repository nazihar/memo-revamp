package com.memo.minutes.internal

import com.memo.audit.AuditLog
import com.memo.common.authz.ForbiddenException
import com.memo.common.concurrency.ConcurrentModificationException
import com.memo.common.id.IdGenerator
import com.memo.common.web.FieldProblem
import com.memo.common.web.RefusedException
import com.memo.common.web.refuseIf
import com.memo.files.Files
import com.memo.forum.MinutesApproval
import com.memo.meeting.MeetingView
import com.memo.meeting.MeetingViewers
import com.memo.meeting.Meetings
import com.memo.notification.NotificationKind
import com.memo.workflow.WorkingDays
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.HexFormat
import java.util.UUID

/** The reviewer's answer (design E06 §1.6.2): ready to circulate, or back to the secretary with comments. */
enum class ReviewDecision { AGREE, RETURN }

/** How the chair's agreement given outside the app reached the secretary (as E03's chair agreement). */
enum class AgreedHow { EMAIL, PHONE, IN_PERSON, OTHER }

/**
 * The chair's review and the circulation for comments (ADR-0066, design E06 §1.6.2, §1.6.3), inside a tenant
 * transaction, the minutes' row locked after the meeting's (design E06 §1.4's lock order). Who may: the committee's
 * secretaries and `forums.manage` (not recused from any item to send, circulate or answer an item's comment), the reviewer
 * for the review, the readers for their comments. Each step is audited and its people told; free text is never audited,
 * only its SHA-256. The structure check ([MinutesStructureChecks.requireReady]) runs before the transaction, and the
 * stored result must still be the current version's.
 */
@Service
class MinutesReview(
    private val steps: MinutesSteps,
    private val store: MinutesStore,
    private val comments: MinutesCommentStore,
    private val drafts: MinutesDrafts,
    private val people: MinutesPeople,
    private val viewers: MeetingViewers,
    private val meetings: Meetings,
    private val files: Files,
    private val copies: MinutesCopies,
    private val previous: PreviousMinutes,
    private val reading: MinutesReading,
    private val notifier: MinutesNotifier,
    private val workingDays: WorkingDays,
    private val ids: IdGenerator,
    private val audit: AuditLog,
    private val clock: Clock,
) {
    /** DRAFT → CHAIR_REVIEW: the reviewer is chosen now (409 `NO_REVIEWER`: circulate with a reason instead). */
    fun sendForReview(
        meetingId: UUID,
        version: Long,
    ): MinutesRow {
        val view = drafts.keeper(meetingId)
        val row = steps.locked(view, version)
        val next = MinutesStateMachine.next(row.state, MinutesTransition.SEND_FOR_REVIEW, row.policy.approval)
        val checked = steps.checkedCurrent(row)
        val reviewer =
            people.reviewer(row) ?: throw RefusedException(
                "NO_REVIEWER",
                "Neither the chair nor a vice-chair can review these minutes (none in office, or each declared an interest): " +
                    "circulate them with a reason.",
            )
        val after = row.copy(state = next, reviewerId = reviewer.userId, reviewerRole = reviewer.role)
        steps.save(row, after)
        steps.event(row, MinutesEventKind.SENT_FOR_REVIEW, fileVersionId = checked)
        audit.record(
            "minutes.sent_for_review",
            MinutesDrafts.OBJECT_TYPE,
            meetingId.toString(),
            before = mapOf("state" to row.state.name),
            after =
                mapOf(
                    "state" to next.name,
                    "reviewerId" to reviewer.userId.toString(),
                    "role" to reviewer.role.name,
                    "versionId" to checked.toString(),
                ),
        )
        copies.request(after)
        notifier.notify(listOf(reviewer.userId), NotificationKind.MINUTES_REVIEW_REQUESTED, meetingId)
        return after
    }

    /**
     * The reviewer agrees or returns the draft; a secretary records the reviewer's agreement given outside the app
     * ([how], AGREE only). AGREE circulates the minutes at once when [ready] (no tracked changes or comments left), else
     * sends them back to the draft with the agreement standing, for the secretary to accept the suggested changes and
     * circulate. RETURN needs a comment.
     */
    fun review(
        meetingId: UUID,
        version: Long,
        decision: ReviewDecision,
        comment: String?,
        how: AgreedHow?,
        ready: Boolean,
    ): MinutesRow {
        val view = viewers.of(meetingId)
        val row = steps.locked(view, version)
        val actor = view.userId
        val onBehalf = requireReviewer(view, row, decision, how)
        val text = commentOf(decision, comment)
        val agree = decision == ReviewDecision.AGREE
        val next =
            MinutesStateMachine.next(
                row.state,
                if (agree) MinutesTransition.AGREE else MinutesTransition.RETURN,
                row.policy.approval,
                ready,
            )
        if (next == MinutesStage.CIRCULATED) steps.checkedCurrent(row)
        val reviewer = checkNotNull(row.reviewerId)
        val reviewed = row.copy(state = if (next == MinutesStage.CIRCULATED) MinutesStage.DRAFT else next)
        steps.save(row, reviewed)
        val kind = if (agree) MinutesEventKind.AGREED else MinutesEventKind.RETURNED
        steps.event(row, kind, actor = reviewer, onBehalfBy = actor.takeIf { onBehalf }, how = how?.name, note = text)
        audit.record(
            if (agree) "minutes.review.agreed" else "minutes.review.returned",
            MinutesDrafts.OBJECT_TYPE,
            meetingId.toString(),
            before = mapOf("state" to row.state.name),
            after =
                mapOf(
                    "state" to next.name,
                    "reviewerId" to reviewer.toString(),
                    "onBehalfBy" to actor.takeIf { onBehalf }?.toString(),
                    "how" to how?.name,
                    "commentSha256" to text?.let(MinutesSteps::sha256),
                ),
        )
        if (next == MinutesStage.CIRCULATED) return circulated(checkNotNull(store.lock(meetingId)), null, null)
        val told = if (agree) NotificationKind.MINUTES_AGREED else NotificationKind.MINUTES_RETURNED
        notifier.notify(people.secretaries(row.forumId), told, meetingId)
        return reviewed
    }

    /**
     * Whether [view]'s actor answers for the reviewer: the reviewer themselves (false), or a secretary recording the
     * reviewer's agreement with how it was given (true); 403 `NOT_REVIEWER` for anyone or anything else.
     */
    private fun requireReviewer(
        view: MeetingView,
        row: MinutesRow,
        decision: ReviewDecision,
        how: AgreedHow?,
    ): Boolean {
        val onBehalf = view.userId != row.reviewerId
        val why =
            when {
                !onBehalf -> null
                !view.manages -> "These minutes are with someone else for review."
                decision != ReviewDecision.AGREE || how == null -> "Only the chair's agreement is recorded for them, with how it was given."
                else -> null
            }
        if (why != null) throw ForbiddenException(null, "NOT_REVIEWER", why)
        return onBehalf
    }

    /** The reviewer's comment, required to send the draft back, at most 1,000 characters (422). */
    private fun commentOf(
        decision: ReviewDecision,
        comment: String?,
    ): String? {
        val text = comment?.trim()?.ifEmpty { null }
        requireNoProblems(
            listOfNotNull(
                FieldProblem("comment", "REASON_REQUIRED", "Say what should change.").takeIf {
                    decision == ReviewDecision.RETURN &&
                        text == null
                },
                FieldProblem("comment", "LENGTH", "At most ${MinutesSteps.MAX_NOTE} characters.").takeIf {
                    (text?.length ?: 0) > MinutesSteps.MAX_NOTE
                },
            ),
        )
        return text
    }

    /**
     * DRAFT → CIRCULATED by a secretary: after the reviewer agreed (the latest review step), or with [reason] when nobody is
     * able to review (409 `NO_REVIEWER` is what sending answered). [until] is the comment deadline's day (tomorrow at the
     * earliest); by default `circulationWorkingDays` working days from today.
     */
    fun circulate(
        meetingId: UUID,
        version: Long,
        until: LocalDate?,
        reason: String?,
    ): MinutesRow {
        val view = drafts.keeper(meetingId)
        val row = steps.locked(view, version)
        MinutesStateMachine.next(row.state, MinutesTransition.CIRCULATE, row.policy.approval)
        val why = reason?.trim()?.ifEmpty { null }
        val agreed = agreementStands(meetingId)
        if (!agreed) {
            refuseIf(
                people.reviewer(row) != null,
                "NOT_AGREED",
            ) { "Send the draft to the chair first: they agree before it is circulated." }
            requireNoProblems(
                listOfNotNull(FieldProblem("reason", "REASON_REQUIRED", "Say why nobody reviews the draft.").takeIf { why == null }),
            )
        }
        requireNoProblems(
            listOfNotNull(
                FieldProblem("reason", "LENGTH", "At most ${MinutesSteps.MAX_NOTE} characters.").takeIf {
                    (why?.length ?: 0) >
                        MinutesSteps.MAX_NOTE
                },
            ),
        )
        steps.checkedCurrent(row)
        return circulated(row, until, why.takeIf { !agreed })
    }

    /** Circulates [row] (locked, its draft checked): the deadline, the readers told, the copies asked for, the next meeting linked. */
    private fun circulated(
        row: MinutesRow,
        until: LocalDate?,
        reason: String?,
    ): MinutesRow {
        val readers = people.readers(row)
        val sets = readers.map { (user, kind) -> people.setOf(row, user, kind) }.map { it.key }.toSet()
        refuseIf(sets.size > MinutesCopies.MAX_COPIES, "TOO_MANY_COPIES") {
            "These minutes would need ${sets.size} different copies for their readers; at most ${MinutesCopies.MAX_COPIES} are made."
        }
        val deadline = steps.deadline(row, until)
        val now = steps.now()
        val after = row.copy(state = MinutesStage.CIRCULATED, commentsUntil = deadline, circulatedAt = now)
        steps.save(row, after)
        steps.event(row, MinutesEventKind.CIRCULATED, how = "NO_REVIEWER".takeIf { reason != null }, note = reason)
        audit.record(
            "minutes.circulated",
            MinutesDrafts.OBJECT_TYPE,
            row.meetingId.toString(),
            before = mapOf("state" to row.state.name),
            after =
                mapOf(
                    "state" to MinutesStage.CIRCULATED.name,
                    "commentsUntil" to deadline.toString(),
                    "readers" to readers.size,
                    "copies" to sets.size,
                    "withoutReview" to (reason != null),
                    "reasonSha256" to reason?.let(MinutesSteps::sha256),
                ),
        )
        val saved = previous.linkOnCirculation(after)
        copies.request(saved)
        notifier.notify(readers.keys, NotificationKind.MINUTES_CIRCULATED, row.meetingId, until = deadline)
        return saved
    }

    /** CIRCULATED → DRAFT by a secretary, with a reason (the readers' comments stay). */
    fun withdraw(
        meetingId: UUID,
        version: Long,
        reason: String?,
    ): MinutesRow {
        val view = steps.manager(meetingId)
        val row = steps.locked(view, version)
        val next = MinutesStateMachine.next(row.state, MinutesTransition.WITHDRAW, row.policy.approval)
        val why = steps.reasonOf(reason)
        val after = row.copy(state = next)
        steps.save(row, after)
        steps.event(row, MinutesEventKind.WITHDRAWN, note = why)
        audit.record(
            "minutes.withdrawn",
            MinutesDrafts.OBJECT_TYPE,
            meetingId.toString(),
            before = mapOf("state" to row.state.name),
            after = mapOf("state" to next.name, "reasonSha256" to MinutesSteps.sha256(why)),
        )
        return after
    }

    /** BY_CIRCULATION: the secretary ends the comment period early, with a reason; the minutes go to the chair. */
    fun endCirculation(
        meetingId: UUID,
        version: Long,
        reason: String?,
    ): MinutesRow {
        val view = steps.manager(meetingId)
        val row = steps.locked(view, version)
        MinutesStateMachine.next(row.state, MinutesTransition.END_CIRCULATION, row.policy.approval)
        return toChair(row, CloseTrigger.EARLY, steps.reasonOf(reason))
    }

    /** Why a circulation ended (design E06 §1.6.3). */
    enum class CloseTrigger { EARLY, DEADLINE, ALL_ANSWERED }

    /**
     * BY_CIRCULATION: CIRCULATED → AWAITING_CHAIR ([row] locked): early with a reason, when the deadline passed (the
     * sweep), or when the last reader answered. The confirmer is told.
     */
    fun toChair(
        row: MinutesRow,
        trigger: CloseTrigger,
        reason: String? = null,
        at: Instant = steps.now(),
    ): MinutesRow {
        val next = MinutesStateMachine.next(row.state, MinutesTransition.CLOSE_CIRCULATION, row.policy.approval)
        val after = row.copy(state = next, awaitingSince = at.truncatedTo(ChronoUnit.MICROS))
        steps.save(row, after)
        val kind = if (trigger == CloseTrigger.DEADLINE) MinutesEventKind.DEADLINE_PASSED else MinutesEventKind.CIRCULATION_ENDED
        steps.event(row, kind, actor = if (trigger == CloseTrigger.EARLY) actorOrNull() else null, how = trigger.name, note = reason)
        audit.record(
            "minutes.circulation_ended",
            MinutesDrafts.OBJECT_TYPE,
            row.meetingId.toString(),
            before = mapOf("state" to row.state.name),
            after = mapOf("state" to next.name, "trigger" to trigger.name, "reasonSha256" to reason?.let(MinutesSteps::sha256)),
        )
        people.confirmer(after)?.let {
            notifier.notify(listOf(it.userId), NotificationKind.MINUTES_CONFIRMATION_REQUESTED, row.meetingId)
        }
        return after
    }

    /** A new comment deadline while circulated (tomorrow at the earliest). */
    fun changeDeadline(
        meetingId: UUID,
        version: Long,
        until: LocalDate,
    ): MinutesRow {
        val view = steps.manager(meetingId)
        val row = steps.locked(view, version)
        MinutesStateMachine.next(row.state, MinutesTransition.CHANGE_DEADLINE, row.policy.approval)
        val deadline = steps.deadline(row, until)
        val after = row.copy(commentsUntil = deadline)
        steps.save(row, after)
        steps.event(row, MinutesEventKind.DEADLINE_CHANGED)
        audit.record(
            "minutes.deadline_changed",
            MinutesDrafts.OBJECT_TYPE,
            meetingId.toString(),
            before = mapOf("commentsUntil" to row.commentsUntil?.toString()),
            after = mapOf("commentsUntil" to deadline.toString()),
        )
        return after
    }

    /** Whether the latest review step of [meetingId] is the reviewer's agreement (no return or decision change since). */
    fun agreementStands(meetingId: UUID): Boolean =
        store
            .events(meetingId)
            .lastOrNull { it.kind in MinutesSteps.REVIEW_STEPS }
            ?.kind == MinutesEventKind.AGREED
}
