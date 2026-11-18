package com.memo.meeting.internal

import com.memo.common.authz.Clearances
import com.memo.common.authz.Policy
import com.memo.forum.ForumDirectory
import com.memo.forum.ForumRole
import com.memo.meeting.MeetingStatus
import com.memo.meeting.PaperViewer
import com.memo.org.OrgDirectory
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.util.UUID

/**
 * Who a person is to the paper rule on a meeting (design E03 §1.9), read once per request: the actor (their officer
 * roles, clearance and `forum.read` from their grants), or another person (a revision's recipient, the preview's target),
 * whose officer roles are their seats in office today (another user's `forums.manage` is not read).
 */
@Component
class PaperViewers(
    private val access: MeetingAccess,
    private val policy: Policy,
    private val clearances: Clearances,
    private val audience: AudienceStore,
    private val directory: ForumDirectory,
    private val org: OrgDirectory,
) {
    /** The actor on [meeting]; null when nobody is signed in. */
    fun actor(meeting: MeetingRow): PaperViewer? =
        actorOrNull()?.let { user ->
            viewer(
                meeting = meeting,
                userId = user,
                officer = access.officer(meeting.forumId),
                rank = policy.clearanceRank(),
                reader = access.readsAll() && access.cleared(meeting),
                rows = audience.rowsOf(meeting.id, user),
            )
        }

    /** [userId] on [meeting], with their audience [rows] (all of the meeting's when not given) and clearance [rank]. */
    fun of(
        meeting: MeetingRow,
        userId: UUID,
        rows: List<AudienceRow> = audience.rowsOf(meeting.id, userId),
        rank: Int = clearances.rankOf(listOf(userId))[userId] ?: 0,
        officers: Set<UUID> = officers(meeting.forumId),
    ): PaperViewer = viewer(meeting, userId, userId in officers, rank, reader = false, rows = rows)

    /** The forum's secretaries and chair in office today (the vice-chair when there is no chair): they see every item. */
    fun officers(forumId: UUID): Set<UUID> {
        val seats = directory.membersOn(forumId)
        val chair = seats.any { it.role == ForumRole.CHAIR }
        return seats
            .filter { it.role == ForumRole.SECRETARY || it.role == ForumRole.CHAIR || (it.role == ForumRole.VICE_CHAIR && !chair) }
            .map { it.userId }
            .toSet()
    }

    private fun viewer(
        meeting: MeetingRow,
        userId: UUID,
        officer: Boolean,
        rank: Int,
        reader: Boolean,
        rows: List<AudienceRow>,
    ): PaperViewer {
        val today = org.today()
        val members = rows.filter { it.source == AudienceSource.MEMBER && inOffice(it.until, today) }
        return PaperViewer(
            userId = userId,
            officer = officer,
            cleared =
                clearances
                    .levels()
                    .filter { it.rank <= rank }
                    .map { it.id }
                    .toSet(),
            member = members.isNotEmpty(),
            membersOnly = members.any { it.membersOnly },
            guest = rows.any { it.source == AudienceSource.GUEST },
            attendee = rows.any { it.source == AudienceSource.ATTENDEE },
            reader = reader,
            open = meeting.status in OPEN,
            guestsRead = meeting.status in GUESTS_READ,
        )
    }

    private fun inOffice(
        until: LocalDate?,
        today: LocalDate,
    ): Boolean = until == null || !until.isBefore(today)

    private companion object {
        /** Past DRAFT and not cancelled: the audience reads the published revisions (design E03 §1.9). */
        val OPEN =
            setOf(
                MeetingStatus.SCHEDULED,
                MeetingStatus.IN_PROGRESS,
                MeetingStatus.HELD,
                MeetingStatus.MINUTES_APPROVED,
                MeetingStatus.CLOSED,
            )

        /** Guests and item attendees read until the minutes are approved. */
        val GUESTS_READ = setOf(MeetingStatus.SCHEDULED, MeetingStatus.IN_PROGRESS, MeetingStatus.HELD)
    }
}
