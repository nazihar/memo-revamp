package com.memo.meeting.internal.rsvp

import com.memo.audit.AuditLog
import com.memo.common.authz.Clearances
import com.memo.forum.ForumDirectory
import com.memo.forum.ForumRef
import com.memo.forum.ForumSeat
import com.memo.identity.UserStatus
import com.memo.identity.UserSummaries
import com.memo.meeting.internal.AudienceRow
import com.memo.meeting.internal.AudienceSource
import com.memo.meeting.internal.AudienceStore
import com.memo.meeting.internal.MeetingAudience
import com.memo.meeting.internal.MeetingLifecycle
import com.memo.meeting.internal.MeetingNotifier
import com.memo.meeting.internal.MeetingRow
import com.memo.meeting.internal.invite.MeetingInvitations
import com.memo.notification.NotificationKind
import org.springframework.stereotype.Component
import java.util.UUID

/** Why an alternate stopped standing in: the member's answer changed, or the roster no longer allows it. */
enum class ReleaseReason { ANSWER, ROSTER }

/**
 * Alternates standing in (ADR-0064, design E05 §1.5), in the transaction of the change: a member who sends apologies asks
 * their seat's alternate to attend, who joins the audience as ALTERNATE (reading what the member reads, recused wherever
 * the member is), is told and is invited through the reconciler; changing the answer back, or a roster change that
 * removes the member's seat, the seat's alternate or makes the alternate a member, releases them (uninvited and told).
 */
@Component
class Alternates(
    private val audience: AudienceStore,
    private val store: AlternateStore,
    private val directory: ForumDirectory,
    private val users: UserSummaries,
    private val clearances: Clearances,
    private val invitations: MeetingInvitations,
    private val notifier: MeetingNotifier,
    private val audit: AuditLog,
) {
    /** The current ALTERNATE rows of [meetingId]. */
    fun standing(meetingId: UUID): List<AudienceRow> = audience.audience(meetingId).filter { it.source == AudienceSource.ALTERNATE }

    /** The alternate of [member]'s seat on [meeting]'s roster, whoever stands in now; null when the seat has none. */
    fun alternateOf(
        meeting: MeetingRow,
        member: UUID,
    ): UUID? =
        directory
            .membersOn(meeting.forumId, meeting.date)
            .firstOrNull { it.userId == member && it.alternateUserId != null }
            ?.alternateUserId

    /**
     * [member] (who sent apologies) asks their seat's alternate to attend [meeting]: refused with 409 `ALTERNATE_NONE`,
     * `ALTERNATE_IS_MEMBER` or `ALTERNATE_BUSY`. Nothing changes when the alternate already stands in for them.
     */
    fun ask(
        meeting: MeetingRow,
        forum: ForumRef,
        member: UUID,
    ) {
        val roster = directory.membersOn(meeting.forumId, meeting.date)
        val standing = standing(meeting.id)
        if (standing.any { it.forUserId == member }) return
        val seat = AlternateRules.seat(member, roster, standing) { available(meeting, it) }
        val alternate = checkNotNull(seat.alternateUserId)
        val memberSeats = roster.filter { it.userId == member }
        store.standIn(
            meetingId = meeting.id,
            userId = alternate,
            forMemberId = seat.memberId,
            forUserId = member,
            membersOnly = memberSeats.any { it.role in MeetingAudience.MEMBERS_ONLY },
            until = seat.until,
        )
        invitations.audienceChanged(meeting)
        audit.record(
            "meeting.alternate.asked",
            MeetingLifecycle.OBJECT_TYPE,
            meeting.id.toString(),
            after = ids(seat.memberId, alternate, member),
        )
        notifier.person(alternate, NotificationKind.MEETING_ALTERNATE_ASKED, meeting, forum, mapOf("member" to name(member)))
    }

    /** The alternate standing in for [member] at [meeting], if any, is released for [reason]. */
    fun release(
        meeting: MeetingRow,
        forum: ForumRef,
        member: UUID,
        reason: ReleaseReason,
    ) {
        standing(meeting.id).filter { it.forUserId == member }.forEach { release(meeting, forum, it, reason) }
    }

    /**
     * After a roster change (design E05 §1.5), in [MeetingAudience.recompute]'s transaction: every alternate whose
     * standing no longer holds on [roster] (the roster on the meeting's date) is released.
     */
    fun reconcile(
        meeting: MeetingRow,
        roster: List<ForumSeat>,
    ) {
        val stale = standing(meeting.id).filterNot { AlternateRules.holds(it, roster) }
        if (stale.isEmpty()) return
        val forum = directory.forum(meeting.forumId) ?: return
        stale.forEach { release(meeting, forum, it, ReleaseReason.ROSTER) }
    }

    private fun release(
        meeting: MeetingRow,
        forum: ForumRef,
        row: AudienceRow,
        reason: ReleaseReason,
    ) {
        if (!store.release(meeting.id, row.userId)) return
        val member = checkNotNull(row.forUserId)
        invitations.audienceChanged(meeting)
        audit.record(
            "meeting.alternate.released",
            MeetingLifecycle.OBJECT_TYPE,
            meeting.id.toString(),
            before = ids(checkNotNull(row.forMemberId), row.userId, member),
            after = mapOf("reason" to reason.name),
        )
        notifier.person(row.userId, NotificationKind.MEETING_ALTERNATE_RELEASED, meeting, forum, mapOf("member" to name(member)))
    }

    /** An alternate who can attend [meeting]: active, and cleared for its classification (they could not open it otherwise). */
    private fun available(
        meeting: MeetingRow,
        userId: UUID,
    ): Boolean {
        val active = users.of(userId)?.status == UserStatus.ACTIVE
        val floor = clearances.level(meeting.classificationId)?.rank ?: Int.MAX_VALUE
        return active && (clearances.rankOf(listOf(userId))[userId] ?: 0) >= floor
    }

    private fun name(userId: UUID): String = users.of(userId)?.displayName.orEmpty()

    private fun ids(
        seat: UUID,
        alternate: UUID,
        member: UUID,
    ): Map<String, Any?> = mapOf("memberId" to seat.toString(), "userId" to alternate.toString(), "forUserId" to member.toString())
}
