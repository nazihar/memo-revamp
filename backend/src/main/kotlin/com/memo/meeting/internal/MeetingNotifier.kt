package com.memo.meeting.internal

import com.memo.forum.ForumDirectory
import com.memo.forum.ForumRef
import com.memo.forum.ForumRole
import com.memo.notification.NotificationKind
import com.memo.notification.Notifications
import com.memo.notification.RecipientLookup
import com.memo.tenancy.TenancyProperties
import com.memo.tenancy.TenantRegistry
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Tells a meeting's audience what changed for them (design E03 §1.1, §9), in the change's transaction (ADR-0030): the
 * subject carries nothing; the body names the committee and the meeting's date and links to the meeting. The actor is
 * never told about their own change. E05 adds the calendar invitations.
 */
@Component
class MeetingNotifier(
    private val audience: AudienceStore,
    private val recipients: RecipientLookup,
    private val notifications: Notifications,
    private val registry: TenantRegistry,
    private val tenancy: TenancyProperties,
    private val directory: ForumDirectory,
) {
    /** A scheduled meeting was called off: its audience is told. */
    fun cancelled(
        meeting: MeetingRow,
        forum: ForumRef,
    ) {
        val told = audience.audience(meeting.id).map { it.userId }.toSet() - setOfNotNull(actorOrNull())
        told.forEach { notify(it, NotificationKind.MEETING_CANCELLED, meeting, forum) }
    }

    /**
     * A late item or paper waits for the chair's agreement (design E03 §1.4): the chair in office is told, or the
     * vice-chair when there is no chair. Never the item's title (ADR-0030).
     */
    fun latePaper(
        meeting: MeetingRow,
        forum: ForumRef,
    ) {
        val seats = directory.membersOn(forum.id)
        val chair = (seats.firstOrNull { it.role == ForumRole.CHAIR } ?: seats.firstOrNull { it.role == ForumRole.VICE_CHAIR })?.userId
        chair?.let { notify(it, NotificationKind.MEETING_LATE_PAPER, meeting, forum) }
    }

    private fun notify(
        user: UUID,
        kind: NotificationKind,
        meeting: MeetingRow,
        forum: ForumRef,
    ) {
        val recipient = recipients.recipient(user) ?: return
        val tenant = registry.current()
        val link = "/meetings/${meeting.id}"
        notifications.notify(
            recipient,
            kind,
            mapOf(
                "committee" to forum.name,
                "date" to meeting.date.toString(),
                "organisation" to tenant?.name.orEmpty(),
                "link" to (tenant?.let { tenancy.publicUrl(it.slug) }.orEmpty() + link),
            ),
            link,
        )
    }
}
