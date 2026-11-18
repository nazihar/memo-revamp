package com.memo.meeting.internal

import com.memo.forum.ForumRef
import com.memo.notification.NotificationKind
import com.memo.notification.Notifications
import com.memo.notification.RecipientLookup
import com.memo.tenancy.TenancyProperties
import com.memo.tenancy.TenantRegistry
import org.springframework.stereotype.Component

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
) {
    /** A scheduled meeting was called off: its audience is told. */
    fun cancelled(
        meeting: MeetingRow,
        forum: ForumRef,
    ) {
        val tenant = registry.current()
        val link = "/meetings/${meeting.id}"
        val told = audience.audience(meeting.id).map { it.userId }.toSet() - setOfNotNull(actorOrNull())
        told.forEach { user ->
            val recipient = recipients.recipient(user) ?: return@forEach
            notifications.notify(
                recipient,
                NotificationKind.MEETING_CANCELLED,
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
}
