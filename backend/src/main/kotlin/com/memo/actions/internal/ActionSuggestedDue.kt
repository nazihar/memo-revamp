package com.memo.actions.internal

import com.memo.common.authz.Permission
import com.memo.forum.ActionsPolicy
import com.memo.forum.DefaultDue
import com.memo.forum.ForumDirectory
import com.memo.forum.ForumPolicies
import com.memo.meeting.Meetings
import com.memo.workflow.WorkingDays
import org.springframework.stereotype.Service
import java.time.Clock
import java.util.UUID

/**
 * The committee type's `defaultDue` applied (design E07 §1.13, `GET /forums/{id}/actions/suggested-due`): the date the
 * action form offers first to whoever raises the committee's actions (a secretary or `forums.manage`; 404 for an unknown
 * committee, 403 for anyone else). NEXT_MEETING gives the next meeting's date (drafted or scheduled, on its own clock,
 * as the form's "By the next meeting"); WORKING_DAYS, and NEXT_MEETING with no meeting to come, the type's working days
 * after today on the tenant's business calendar (the colours' calendar). It suggests only: issuing still needs a date.
 */
@Service
class ActionSuggestedDue(
    private val viewers: ActionViewers,
    private val directory: ForumDirectory,
    private val policies: ForumPolicies,
    private val meetings: Meetings,
    private val workingDays: WorkingDays,
    private val clock: Clock,
) {
    fun of(forumId: UUID): SuggestedDueResponse {
        val viewer = viewers.actor()
        notFoundUnless(directory.forum(forumId) != null, "committee")
        forbidUnless(viewer.manages(forumId), Permission.FORUMS_MANAGE)
        val policy = policies.current(forumId)?.actions ?: ActionsPolicy()
        val next =
            if (policy.defaultDue == DefaultDue.NEXT_MEETING) ActionMeetings.next(meetings.ofForum(forumId), clock.instant()) else null
        if (next != null) {
            val date = next.startsAt.atZone(next.timeZone).toLocalDate()
            return SuggestedDueResponse(forumId, policy.defaultDue, DefaultDue.NEXT_MEETING, date, meetingId = next.id)
        }
        val days = policy.defaultDueWorkingDays
        return SuggestedDueResponse(
            forumId,
            policy.defaultDue,
            DefaultDue.WORKING_DAYS,
            workingDays.after(workingDays.today(), days),
            workingDays = days,
        )
    }
}
