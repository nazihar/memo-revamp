package com.memo.minutes.internal

import com.memo.forum.ForumDirectory
import com.memo.meeting.Meetings
import com.memo.notification.NotificationKind
import com.memo.notification.Notifications
import com.memo.notification.RecipientLookup
import com.memo.tenancy.TenancyProperties
import com.memo.tenancy.TenantRegistry
import com.memo.workflow.WorkingDays
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

/**
 * Tells people what happened to a meeting's minutes (design E06 §2, ADR-0030), in the transaction that calls it: the
 * subject carries nothing; the body names the committee and the meeting's date (and the comment deadline where it
 * matters) and links to the minutes page, never an item, a comment or a word of the minutes. The actor is never told of
 * their own step.
 */
@Component
class MinutesNotifier(
    private val recipients: RecipientLookup,
    private val notifications: Notifications,
    private val registry: TenantRegistry,
    private val tenancy: TenancyProperties,
    private val directory: ForumDirectory,
    private val meetings: Meetings,
    private val workingDays: WorkingDays,
) {
    /** [kind] to each of [users] but [except] (the actor), about [meetingId]'s minutes; [until] is the comment deadline. */
    fun notify(
        users: Collection<UUID>,
        kind: NotificationKind,
        meetingId: UUID,
        until: Instant? = null,
        details: Map<String, String> = emptyMap(),
        except: UUID? = actorOrNull(),
    ) {
        val told = users.toSet() - setOfNotNull(except)
        val meeting = meetings.find(meetingId)?.takeIf { told.isNotEmpty() }
        val forum = meeting?.let { directory.forum(it.forumId) } ?: return
        val tenant = registry.current()
        val link = "/meetings/$meetingId/minutes"
        val params =
            details +
                mapOf(
                    "committee" to forum.name,
                    "date" to meeting.date().toString(),
                    "organisation" to tenant?.name.orEmpty(),
                    "link" to (tenant?.let { tenancy.publicUrl(it.slug) }.orEmpty() + link),
                ) + listOfNotNull(until?.let { "until" to workingDays.dayOf(it).toString() })
        told.sorted().forEach { user ->
            recipients.recipient(user)?.let { notifications.notify(it, kind, params, link) }
        }
    }
}
