package com.memo.actions.internal

import com.memo.common.concurrency.ConcurrentModificationException
import com.memo.common.web.FieldProblem
import com.memo.common.web.refuseIf
import com.memo.forum.ActionsPolicy
import com.memo.forum.ForumPolicies
import com.memo.forum.StandingKind
import com.memo.meeting.MeetingItems
import com.memo.meeting.MeetingRef
import com.memo.meeting.MeetingViewers
import com.memo.meeting.Meetings
import com.memo.workflow.TaskRole
import com.memo.workflow.WorkflowHandOns
import com.memo.workflow.WorkflowQueries
import com.memo.workflow.Workflows
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/** The verifier's decision (design E07 §1.9, `POST /actions/{id}/verify`). */
enum class VerifyDecision { CLOSE, SEND_BACK }

/**
 * Closure verified by someone else (ADR-0067, design E07 §1.9):
 * - **Mark as done** (`POST …/done`): whoever holds the owner's task (the person, the position's holder, a substitute),
 *   with what was done (1 to 2,000 characters) and evidence files, required when the committee type says so (422
 *   `EVIDENCE_REQUIRED`). It withdraws a pending date request, writes the DONE entry and runs the owner's step's
 *   RECOMMEND with the note; the engine's next task makes the action DONE_PENDING ([ActionWorkflowListener]) and its
 *   verifiers are told.
 * - **Verify** (`POST …/verify`): a member of the verify step's pool, never the owner, holder or a contributor (403
 *   `NOT_VERIFIER`), nor anyone recused from the item (403 `RECUSED`); the shared step is claimed first (409 `CLAIMED` when
 *   another verifier has it). CLOSE approves the last step (the instance ends APPROVED: CLOSED), with an optional remark;
 *   SEND_BACK, with a remark required, returns the work to the owner's step, due on the same date (IN_PROGRESS).
 * - **Close the actions the committee agreed** at a meeting ([closeAgreed]): each one verified as above, with the remark
 *   "Agreed at BRC 4/2026, item 3", one transaction per action (the controller's).
 */
@Service
class ActionClosure(
    private val store: ActionStore,
    private val lines: ActionLines,
    private val scenes: ActionScenes,
    private val progress: ActionProgress,
    private val requests: ActionDueRequests,
    private val dates: ActionDates,
    private val policies: ForumPolicies,
    private val workflows: Workflows,
    private val handOns: WorkflowHandOns,
    private val queries: WorkflowQueries,
    private val meetings: Meetings,
    private val meetingViewers: MeetingViewers,
    private val items: MeetingItems,
    private val effects: ActionClosureEffects,
    private val clock: Clock,
) {
    /** Marks [actionId] (seen at [version]) as done, with [note] and the evidence [fileIds]. */
    fun done(
        actionId: UUID,
        version: Long,
        note: String?,
        fileIds: List<UUID>,
    ): ActionRow {
        val scene = scenes.scene(actionId)
        val row = scene.row
        refuseIf(
            row.status !in ActionChecks.UNDERWAY,
            ActionRules.WRONG_STATE,
        ) { "Only an open action not yet done can be marked as done." }
        forbidUnless(scene.owner)
        val task = queries.myTask(ActionsPolicy.SUBJECT, row.id, scene.viewer.userId)?.takeIf { it.role == TaskRole.DECIDE }
        forbidUnless(task != null, reason = NOT_HOLDER, detail = "Whoever holds the owner's task marks the action as done.")
        val text = note?.trim().orEmpty()
        val policy = policies.current(row.forumId)?.actions ?: ActionsPolicy()
        val files = fileIds.distinct()
        requireNoProblems(
            listOfNotNull(
                FieldProblem("note", "REQUIRED", "Say what was done.").takeIf { text.isEmpty() },
                FieldProblem("note", "LENGTH", "At most $NOTE_MAX characters.").takeIf { text.length > NOTE_MAX },
                FieldProblem("fileIds", EVIDENCE_REQUIRED, "This committee asks for evidence: attach the files.")
                    .takeIf { policy.evidenceRequired && files.isEmpty() },
            ) + progress.evidence(row.id, scene.viewer.userId, files),
        )
        if (version != row.version) throw ConcurrentModificationException("action ${row.id}", version)
        requests.pending(row.id)?.let { dates.withdraw(row, it, DONE) }
        val details = mapOf("instanceId" to row.workflowInstanceId?.toString())
        val seq = lines.entry(row.id, DONE, scene.viewer.userId, details, now(), EntryMore(text = text, fileIds = files))
        // RECOMMEND on the owner's step: the engine's next task makes the action DONE_PENDING and tells its verifiers.
        workflows.approve(checkNotNull(task).id, text)
        effects.done(row, seq, text, files)
        return checkNotNull(store.find(row.id))
    }

    /**
     * The verifier's [decision] on [actionId], seen at [version] (the current one when null), with [remark]; [agreedAt] is
     * the meeting whose committee agreed to close it.
     */
    fun verify(
        actionId: UUID,
        version: Long?,
        decision: VerifyDecision?,
        remark: String?,
        agreedAt: MeetingRef? = null,
    ): ActionRow {
        val scene = scenes.scene(actionId)
        val row = scene.row
        refuseIf(agreedAt != null && agreedAt.forumId != row.forumId, COMMITTEE) { "The action is not this committee's." }
        refuseIf(row.status != ActionStatus.DONE_PENDING, ActionRules.WRONG_STATE) { "Only an action marked as done is verified." }
        forbidUnless(
            !scene.owner && !scene.contributor,
            reason = NOT_VERIFIER,
            detail = "Someone other than the owner verifies the action.",
        )
        val task = queries.myTask(ActionsPolicy.SUBJECT, row.id, scene.viewer.userId)?.takeIf { it.role == TaskRole.DECIDE }
        forbidUnless(task != null, reason = NOT_VERIFIER, detail = "You are not asked to verify this action.")
        scenes.requireNotRecused(scene)
        checkNotNull(task)
        val text = remark?.trim()?.takeIf { it.isNotEmpty() }
        requireNoProblems(
            listOfNotNull(
                FieldProblem("decision", "REQUIRED", "Close the action or send it back.").takeIf { decision == null },
                FieldProblem("remark", "REQUIRED", "Say why it goes back to the owner.")
                    .takeIf { decision == VerifyDecision.SEND_BACK && text == null },
                FieldProblem("remark", "LENGTH", "At most $NOTE_MAX characters.").takeIf { (text?.length ?: 0) > NOTE_MAX },
            ),
        )
        if (version != null && version != row.version) throw ConcurrentModificationException("action ${row.id}", version)
        val me = scene.viewer.userId
        refuseIf(task.claimedBy != null && task.claimedBy != me, CLAIMED) { "Another verifier has taken this action." }
        if (task.shared && task.claimedBy == null) handOns.claim(task.id)
        val details = mapOf("instanceId" to row.workflowInstanceId?.toString(), "agreedAt" to agreedAt?.id?.toString())
        if (decision == VerifyDecision.CLOSE) {
            val seq = lines.entry(row.id, CLOSED, me, details, now(), EntryMore(text = text))
            workflows.approve(task.id, text)
            effects.closed(row, seq, text, agreedAt?.id, me)
        } else {
            val target =
                task.sendBackTargets.firstOrNull { it.holderId == row.holderUserId } ?: task.sendBackTargets.firstOrNull()
            refuseIf(target == null, ActionRules.WRONG_STATE) { "There is no owner's step to send it back to." }
            val seq = lines.entry(row.id, SENT_BACK, me, details, now(), EntryMore(text = text))
            handOns.sendBack(task.id, checkNotNull(target).stepId, text)
            effects.sentBack(checkNotNull(store.find(row.id)), seq, checkNotNull(text))
        }
        return checkNotNull(store.find(row.id))
    }

    /**
     * The meeting [meetingId] the committee agreed closures at (design E07 §1.9): one the actor sees, running, held or with
     * its minutes approved (409 `WRONG_STATE` otherwise), and the remark each closure gets, [remark] or "Agreed at BRC
     * 4/2026, item 3" (the matters arising item, when the agenda has one).
     */
    fun agreedAt(
        meetingId: UUID,
        remark: String?,
    ): Pair<MeetingRef, String> {
        notFoundUnless(meetingViewers.find(meetingId) != null, "meeting")
        val meeting = meetings.find(meetingId) ?: throw ActionNotFoundException("meeting")
        ActionRules.requireRaiseWindow(meeting.status)
        rejectIf(FieldProblem("remark", "LENGTH", "At most $NOTE_MAX characters.").takeIf { (remark?.trim()?.length ?: 0) > NOTE_MAX })
        val given = remark?.trim()?.takeIf { it.isNotEmpty() }
        if (given != null) return meeting to given
        val item =
            items
                .agenda(meetingId)
                .items
                .firstOrNull { it.standingKind == StandingKind.MATTERS_ARISING && it.published }
                ?.number
        val label = meeting.label ?: meeting.title.orEmpty()
        return meeting to (if (item != null) "Agreed at $label, item $item" else "Agreed at $label")
    }

    private fun now(): Instant = clock.instant().truncatedTo(ChronoUnit.MICROS)

    companion object {
        const val DONE = "DONE"
        const val CLOSED = "CLOSED"
        const val SENT_BACK = "SENT_BACK"
        const val NOTE_MAX = 2000
        const val EVIDENCE_REQUIRED = "EVIDENCE_REQUIRED"
        const val NOT_VERIFIER = "NOT_VERIFIER"
        const val NOT_HOLDER = "NOT_HOLDER"
        const val CLAIMED = "CLAIMED"
        const val COMMITTEE = "COMMITTEE"

        /** At most this many actions closed in one request at a meeting. */
        const val CLOSE_AGREED_MAX = 100
    }
}
