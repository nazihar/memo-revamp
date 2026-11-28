package com.memo.minutes.internal

import com.memo.audit.AuditLog
import com.memo.forum.MinutesApproval
import com.memo.meeting.MeetingSweepStep
import com.memo.notification.NotificationKind
import com.memo.workflow.WorkingDays
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.LocalTime
import java.util.UUID

/**
 * The minutes' part of the hourly meeting sweep (design E06 §9 (i)–(l)), in the sweep's tenant transaction, each step
 * idempotent (a state change under the minutes' lock, or a reminder marked in `minutes_reminder` before it is sent):
 *  - (i) BY_CIRCULATION minutes whose comment deadline has passed go to the chair, who is told;
 *  - (j) from 09:00 on the deadline's day, the readers who have not answered are reminded, once per deadline;
 *  - (k) minutes not circulated `draftWorkingDays` working days after the meeting: the secretaries are told, once;
 *  - (l) minutes with the chair for three working days: the confirmer is reminded, once per wait;
 *  - (m) approved minutes whose record, or a reader's approved copy of it, failed after its job's last try: asked for
 *    again once a day with backoff, in the tenant's zone ([RecordRetries.sweep]; a guarded update FAILED → PENDING).
 * Each is audited with the minutes; nothing of the minutes is in any notice.
 */
@Component
class MinutesSweep(
    private val store: MinutesStore,
    private val queries: MinutesQueries,
    private val reminders: MinutesReminderStore,
    private val review: MinutesReview,
    private val people: MinutesPeople,
    private val comments: MinutesCommentStore,
    private val notifier: MinutesNotifier,
    private val retries: RecordRetries,
    private val workingDays: WorkingDays,
    private val audit: AuditLog,
) : MeetingSweepStep {
    override fun sweep(now: Instant): Int = deadlines(now) + closing(now) + overdue(now) + confirming(now) + retries.sweep(now)

    /** (i) */
    private fun deadlines(now: Instant): Int =
        queries
            .deadlinePassed(MinutesStage.CIRCULATED, now, BATCH)
            .filter { it.policy.approval == MinutesApproval.BY_CIRCULATION }
            .count { found ->
                val row = store.lock(found.meetingId)
                val due = row != null && row.state == MinutesStage.CIRCULATED && row.commentsUntil?.isBefore(now) == true
                if (due) review.toChair(checkNotNull(row), MinutesReview.CloseTrigger.DEADLINE, at = now)
                due
            }

    /** (j) */
    private fun closing(now: Instant): Int =
        queries.inStates(listOf(MinutesStage.CIRCULATED), BATCH).count { row ->
            val until = row.commentsUntil ?: return@count false
            val day = workingDays.dayOf(until)
            val today = workingDays.dayOf(now)
            if (day != today || now.isBefore(workingDays.at(today, FROM)) || !now.isBefore(until)) return@count false
            val secretaries = people.secretaries(row.forumId)
            val answered = comments.answered(listOf(row.meetingId))[row.meetingId].orEmpty()
            val due =
                people
                    .readers(row)
                    .filter { it.value != ReaderKind.ASKED && it.key !in secretaries && it.key !in answered }
                    .keys
                    .filter { reminders.mark(row.meetingId, MinutesReminderKind.COMMENTS_CLOSING, it, day.toString(), now) }
            if (due.isEmpty()) return@count false
            notifier.notify(due, NotificationKind.MINUTES_COMMENTS_CLOSING, row.meetingId, until = until)
            reminded("minutes.reminded.comments_closing", row.meetingId, due, day.toString())
            true
        }

    /** (k) */
    private fun overdue(now: Instant): Int =
        queries.inStates(listOf(MinutesStage.DRAFT, MinutesStage.CHAIR_REVIEW), BATCH).count { row ->
            if (row.circulatedAt != null || workingDays.between(row.heldAt, now) < row.policy.draftWorkingDays) return@count false
            val due =
                people.secretaries(row.forumId).filter {
                    reminders.mark(row.meetingId, MinutesReminderKind.DRAFT_OVERDUE, it, KEY_ONCE, now)
                }
            if (due.isEmpty()) return@count false
            notifier.notify(due, NotificationKind.MINUTES_DRAFT_OVERDUE, row.meetingId)
            reminded("minutes.reminded.draft_overdue", row.meetingId, due, KEY_ONCE)
            true
        }

    /** (l) */
    private fun confirming(now: Instant): Int =
        queries.inStates(listOf(MinutesStage.AWAITING_CHAIR), BATCH).count { row ->
            val since = row.awaitingSince ?: return@count false
            if (workingDays.between(since, now) < CONFIRM_AFTER_DAYS) return@count false
            val confirmer = people.confirmer(row)?.userId ?: return@count false
            val key = since.toString()
            if (!reminders.mark(row.meetingId, MinutesReminderKind.CONFIRMATION, confirmer, key, now)) return@count false
            notifier.notify(listOf(confirmer), NotificationKind.MINUTES_CONFIRMATION_REQUESTED, row.meetingId)
            reminded("minutes.reminded.confirmation", row.meetingId, listOf(confirmer), key)
            true
        }

    private fun reminded(
        action: String,
        meetingId: UUID,
        users: Collection<UUID>,
        key: String,
    ) = audit.record(
        action,
        MinutesDrafts.OBJECT_TYPE,
        meetingId.toString(),
        after = mapOf("recipients" to users.map { it.toString() }.sorted(), "key" to key),
    )

    private companion object {
        const val BATCH = 500
        const val KEY_ONCE = "once"
        const val CONFIRM_AFTER_DAYS = 3
        val FROM: LocalTime = LocalTime.of(9, 0)
    }
}
