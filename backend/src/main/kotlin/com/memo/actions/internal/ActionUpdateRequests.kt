package com.memo.actions.internal

import com.memo.audit.AuditLog
import com.memo.common.authz.Permission
import com.memo.common.web.refuseIf
import com.memo.forum.ActionsPolicy
import com.memo.forum.ForumDirectory
import com.memo.forum.ForumPolicies
import com.memo.meeting.AgendaDeadlines
import com.memo.meeting.MeetingRef
import com.memo.meeting.Meetings
import com.memo.notification.NotificationKind
import com.memo.org.OrgDirectory
import com.memo.workflow.WorkingDays
import org.jooq.Condition
import org.jooq.impl.DSL
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Asking owners for an update before the committee's papers go out (design E07 §1.12, §9 (b)): the holders of the
 * committee's open actions (OPEN or IN_PROGRESS) with no update since the previous meeting started are asked
 * (`ACTION_UPDATE_REQUESTED`, in the app and by email at once, the reference and the due date only), and each action's
 * timeline records it (`UPDATE_REQUESTED`, with the meeting).
 * - **The daily task** (part (b) of `actions-daily`): for each committee whose type has `updateRequestWorkingDays`, from
 *   that many working days before its next meeting's agenda deadline ([AgendaDeadlines]) to the deadline itself, once
 *   per meeting and action (the timeline's marker), as the system.
 * - **"Ask owners for an update"** (a secretary or `forums.manage`): at once, at most once a day per committee (409
 *   `ALREADY_ASKED`), for the actions the asker may see by the rule (a recused secretary's leave out the item's).
 */
@Service
class ActionUpdateRequests(
    private val store: ActionStore,
    private val lines: ActionLines,
    private val reports: ActionReportStore,
    private val candidates: ActionReportCandidates,
    private val access: ActionAccess,
    private val viewers: ActionViewers,
    private val meetings: Meetings,
    private val deadlines: AgendaDeadlines,
    private val directory: ForumDirectory,
    private val policies: ForumPolicies,
    private val workingDays: WorkingDays,
    private val effects: ActionItemEffects,
    private val org: OrgDirectory,
    private val audit: AuditLog,
    private val clock: Clock,
) {
    /** The daily task's part (b) at [now]: how many owners were asked across the tenant's committees. */
    fun daily(now: Instant = clock.instant()): Int {
        val today = now.atZone(org.zone()).toLocalDate()
        return candidates.forumsWithOpen().sumOf { forumId ->
            val days = (policies.current(forumId)?.actions ?: ActionsPolicy()).updateRequestWorkingDays
            val forumMeetings = meetings.ofForum(forumId)
            val next = ActionMeetings.next(forumMeetings, now)
            val deadline = next?.let { deadlines.of(it.id) }
            if (next == null || deadline == null || !asking(today, deadline, days)) {
                0
            } else {
                val previous = ActionMeetings.previous(forumMeetings, next)
                val asked = ask(forumId, next, deadline, previous?.startsAt, DAILY, DSL.noCondition(), now, skipAsked = true)
                if (asked > 0) record(forumId, next, asked, DAILY)
                asked
            }
        }
    }

    /** Whether [today] is within the [days] working days before [deadline], the deadline included (0 days: off). */
    private fun asking(
        today: LocalDate,
        deadline: LocalDate,
        days: Int,
    ): Boolean {
        val from = workingDays.before(deadline, days)?.takeIf { days != ActionsPolicy.OFF } ?: return false
        return !today.isBefore(from) && !today.isAfter(deadline)
    }

    /** "Ask owners for an update" on [forumId] by the signed-in actor: how many were asked. */
    fun askNow(forumId: UUID): Int {
        val viewer = viewers.actor()
        notFoundUnless(directory.forum(forumId) != null, "committee")
        forbidUnless(viewer.manages(forumId), Permission.FORUMS_MANAGE)
        val now = clock.instant().truncatedTo(ChronoUnit.MICROS)
        val today = org.today()
        refuseIf(reports.askedOn(forumId, today), ALREADY_ASKED) { "The owners of this committee's actions were already asked today." }
        val forumMeetings = meetings.ofForum(forumId)
        val next = ActionMeetings.next(forumMeetings, now)
        val deadline = next?.let { deadlines.of(it.id) }?.takeIf { !it.isBefore(today) }
        val previous = ActionMeetings.before(forumMeetings, now).firstOrNull()
        val asked = ask(forumId, next, deadline, previous?.startsAt, SECRETARY, access.seen(viewer, forumId), now, skipAsked = false)
        refuseIf(!reports.asked(forumId, today, viewer.userId, now, asked), ALREADY_ASKED) {
            "The owners of this committee's actions were already asked today."
        }
        record(forumId, next, asked, SECRETARY)
        return asked
    }

    /**
     * Asks the holder of each of [forumId]'s open actions matching [visible] with no update since [since] (none at all
     * when there was no previous meeting), before [next]'s agenda [deadline]; [skipAsked] leaves out those already asked
     * for [next]. Returns how many were asked.
     */
    private fun ask(
        forumId: UUID,
        next: MeetingRef?,
        deadline: LocalDate?,
        since: Instant?,
        cause: String,
        visible: Condition,
        now: Instant,
        skipAsked: Boolean,
    ): Int {
        val candidates = candidates.awaitingUpdate(forumId, since, visible)
        val asked =
            if (skipAsked &&
                next != null
            ) {
                lines.marked(candidates.map { it.id }, UPDATE_REQUESTED, MEETING, next.id.toString())
            } else {
                emptySet()
            }
        var count = 0
        candidates.filter { it.id !in asked }.forEach { candidate ->
            // Under the action's lock, as every timeline entry is written; it may have changed since it was listed.
            val row = store.lock(candidate.id)?.takeIf { it.status in ActionChecks.UNDERWAY && it.holderUserId != null } ?: return@forEach
            val details = mapOf(MEETING to next?.id?.toString(), "deadline" to deadline?.toString(), "cause" to cause)
            lines.entry(row.id, UPDATE_REQUESTED, actorOrNull(), details, now.truncatedTo(ChronoUnit.MICROS))
            val words =
                mapOf("date" to row.dueDate.toString(), "when" to if (deadline != null) "DEADLINE" else "NEXT") +
                    (deadline?.let { mapOf("deadline" to it.toString()) } ?: emptyMap())
            effects.notify(checkNotNull(row.holderUserId), NotificationKind.ACTION_UPDATE_REQUESTED, row, words)
            count++
        }
        return count
    }

    private fun record(
        forumId: UUID,
        next: MeetingRef?,
        asked: Int,
        cause: String,
    ) = audit.record(
        "actions.update_requests.sent",
        "forum",
        forumId.toString(),
        after = mapOf("asked" to asked, "meetingId" to next?.id?.toString(), "cause" to cause),
    )

    companion object {
        /** The timeline's marker of an update asked for. */
        const val UPDATE_REQUESTED = "UPDATE_REQUESTED"
        const val ALREADY_ASKED = "ALREADY_ASKED"
        const val DAILY = "DAILY"
        const val SECRETARY = "SECRETARY"
        private const val MEETING = "meetingId"
    }
}
