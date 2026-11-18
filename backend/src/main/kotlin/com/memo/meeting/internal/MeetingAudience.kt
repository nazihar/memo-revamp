package com.memo.meeting.internal

import com.memo.forum.ForumChanged
import com.memo.forum.ForumDirectory
import com.memo.forum.ForumRole
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

/**
 * Who a meeting is for (design E03 §1.6), materialised and recomputed in the transaction of every change that affects it:
 *  - MEMBER: every roster seat in office on the meeting's date, position seats resolved to their holders; members-only
 *    sections are for the chair, vice-chair, members and secretaries; [AudienceRow.until] is the seat's last day;
 *  - GUEST: each current guest.
 * Item attendees (ATTENDEE) are the agenda's (T2). Officers are not audience rows: [MeetingAccess] checks them.
 */
@Component
class MeetingAudience(
    private val store: AudienceStore,
    private val meetings: MeetingStore,
    private val directory: ForumDirectory,
) {
    fun recompute(meeting: MeetingRow) {
        val members =
            directory
                .membersOn(meeting.forumId, meeting.date)
                .groupBy { it.userId }
                .map { (user, seats) ->
                    AudienceRow(
                        meetingId = meeting.id,
                        userId = user,
                        source = AudienceSource.MEMBER,
                        membersOnly = seats.any { it.role in MEMBERS_ONLY },
                        until = if (seats.any { it.until == null }) null else seats.mapNotNull { it.until }.max(),
                    )
                }
        val guests =
            store.guests(meeting.id).map {
                AudienceRow(meeting.id, it.userId, AudienceSource.GUEST, membersOnly = false, until = null)
            }
        store.replace(meeting.id, AudienceSource.MEMBER, members)
        store.replace(meeting.id, AudienceSource.GUEST, guests)
    }

    /** A roster change reaches the forum's meetings that are not held, cancelled or later, in the changing transaction. */
    @EventListener
    fun onForumChanged(event: ForumChanged) {
        meetings.openOfForum(event.forumId).forEach(::recompute)
    }

    companion object {
        /** The roles that sit in members-only (in camera) sections. */
        val MEMBERS_ONLY = setOf(ForumRole.CHAIR, ForumRole.VICE_CHAIR, ForumRole.MEMBER, ForumRole.SECRETARY)
    }
}
