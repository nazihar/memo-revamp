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
 * Tells a meeting's audience and officers what changed for them (design E03 §1.1, §1.4, §9), in the transaction that
 * calls it (ADR-0030): the subject carries nothing; the body names the committee and the meeting's date and links to the
 * meeting, never an item's title. The actor is never told about their own change. E05 adds the calendar invitations.
 */
@Component
class MeetingNotifier(
    private val audience: AudienceStore,
    private val recipients: RecipientLookup,
    private val notifications: Notifications,
    private val registry: TenantRegistry,
    private val tenancy: TenancyProperties,
    private val directory: ForumDirectory,
    private val declarations: DeclarationAccess,
    private val recusals: RecusalStore,
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
     * A late item or paper waits for the chair's agreement (design E03 §1.4, E04 §1.7): who decides for the item whose
     * [scope] (the item and its parent) is given is told: the chair in office, or the vice-chair when the chair is recused
     * from it or there is none. When neither can, the secretaries not recused from it are told, to record the committee's
     * agreement. Never the item's title (ADR-0030).
     */
    fun latePaper(
        meeting: MeetingRow,
        forum: ForumRef,
        scope: Collection<UUID>,
    ) {
        val decider = declarations.decider(forum.id, scope)
        val told = if (decider != null) setOf(decider) else secretaries(forum.id).let { it - recusals.recusedAmong(scope, it) }
        told.forEach { notify(it, NotificationKind.MEETING_LATE_PAPER, meeting, forum) }
    }

    /**
     * An item of the published agenda left it (its submission was withdrawn or returned): the secretaries publish again.
     * Not those recused from the item whose [scope] is given (design E04 §1.7).
     */
    fun itemWithdrawn(
        meeting: MeetingRow,
        forum: ForumRef,
        scope: Collection<UUID>,
    ) {
        val told = secretaries(forum.id).let { it - recusals.recusedAmong(scope, it) }
        told.forEach { notify(it, NotificationKind.MEETING_ITEM_WITHDRAWN, meeting, forum) }
    }

    /**
     * A member asked to discuss a consent item (design E03 §1.5): the secretaries and the chair in office (the vice-chair
     * when there is none) are told, never the requester, and not those recused from the item whose [scope] is given
     * (design E04 §1.7). Never the item's title (ADR-0030).
     */
    fun discussionRequested(
        meeting: MeetingRow,
        forum: ForumRef,
        scope: Collection<UUID>,
    ) {
        val officers = secretaries(forum.id) + setOfNotNull(chairOf(forum.id)) - setOfNotNull(actorOrNull())
        val told = officers - recusals.recusedAmong(scope, officers)
        told.forEach { notify(it, NotificationKind.MEETING_DISCUSSION_REQUESTED, meeting, forum) }
    }

    /** The chair commented on the draft: the secretaries are told in the app (the comment is on the meeting). */
    fun chairCommented(
        meeting: MeetingRow,
        forum: ForumRef,
    ) {
        secretaries(forum.id).forEach { notify(it, NotificationKind.MEETING_CHAIR_COMMENTED, meeting, forum) }
    }

    /** A revision of the agenda, to one person of the audience (design E03 §9): its first publication, or a change they see. */
    fun agenda(
        user: UUID,
        kind: NotificationKind,
        meeting: MeetingRow,
        forum: ForumRef,
    ) = notify(user, kind, meeting, forum)

    /**
     * A reminder of the hourly sweep (design E03 §9: the agenda is due, or the meeting is not marked as held), to the
     * forum's secretaries in office, or to [fallback] (the holders of `forums.manage`) when it has none.
     */
    fun reminder(
        kind: NotificationKind,
        meeting: MeetingRow,
        forum: ForumRef,
        fallback: () -> Set<UUID>,
    ) {
        secretaries(forum.id).ifEmpty(fallback).forEach { notify(it, kind, meeting, forum) }
    }

    /**
     * A declaration of interest, to one person (design E04 §2): the in-app text names the item's [number] and the [change];
     * the email names neither (its template uses the committee, the date and the link only). Never the nature.
     */
    fun declaration(
        user: UUID,
        kind: NotificationKind,
        meeting: MeetingRow,
        forum: ForumRef,
        details: Map<String, String>,
    ) = notify(user, kind, meeting, forum, details)

    /** The chair in office, else the vice-chair. */
    fun chairOf(forumId: UUID): UUID? {
        val seats = directory.membersOn(forumId)
        return (seats.firstOrNull { it.role == ForumRole.CHAIR } ?: seats.firstOrNull { it.role == ForumRole.VICE_CHAIR })?.userId
    }

    private fun secretaries(forumId: UUID): Set<UUID> =
        directory
            .membersOn(forumId)
            .filter { it.role == ForumRole.SECRETARY }
            .map { it.userId }
            .toSet() - setOfNotNull(actorOrNull())

    private fun notify(
        user: UUID,
        kind: NotificationKind,
        meeting: MeetingRow,
        forum: ForumRef,
        details: Map<String, String> = emptyMap(),
    ) {
        val recipient = recipients.recipient(user) ?: return
        val tenant = registry.current()
        val link = "/meetings/${meeting.id}"
        notifications.notify(
            recipient,
            kind,
            details +
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
