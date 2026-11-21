package com.memo.meeting.internal.invite

import com.memo.common.id.IdGenerator
import com.memo.forum.ForumDirectory
import com.memo.forum.ForumPolicies
import com.memo.forum.ForumRef
import com.memo.forum.InvitationTitle
import com.memo.forum.MeetingPolicy
import com.memo.meeting.internal.MeetingRow
import com.memo.meeting.internal.RecipientLocales
import com.memo.meeting.internal.ical.Invite
import com.memo.meeting.internal.ical.InviteEvent
import com.memo.meeting.internal.ical.InviteKind
import com.memo.meeting.internal.ical.InviteParty
import com.memo.meeting.internal.ical.InviteTexts
import com.memo.meeting.internal.ical.InviteWriter
import com.memo.notification.CalendarPart
import com.memo.notification.NotificationKind
import com.memo.notification.Notifications
import com.memo.notification.Recipient
import com.memo.notification.SendingAddress
import com.memo.tenancy.TenancyProperties
import com.memo.tenancy.TenantRegistry
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

/**
 * What every message of one meeting's invitation shares (design E05 §1.2): the UID, the times and place, what the
 * calendar calls it (the meeting's title or the committee's name, or the type's neutral title, which also marks it
 * private), the organizer (the tenant's sending address with the committee's name) and the meeting page.
 */
data class InvitationContext(
    val meeting: MeetingRow,
    val invitation: InvitationRow,
    val forum: ForumRef,
    val summary: String,
    /** The committee as the email body names it: its name, or the neutral title. */
    val committee: String,
    val confidential: Boolean,
    val organizer: InviteParty,
    /** The meeting page (absolute). */
    val url: String,
    val organisation: String,
)

/**
 * Builds and queues one invitation message (ADR-0063, design E05 §1.3 step 5): [InviteWriter] writes and validates the
 * file for one recipient in their language, [Notifications.notify] writes the in-app item and the outbox row with the
 * file, and the person's row and the message log follow. Never the agenda, items, papers, classification or a reason.
 */
@Component
class InvitationMessages(
    private val writer: InviteWriter,
    private val notifications: Notifications,
    private val sending: SendingAddress,
    private val directory: ForumDirectory,
    private val policies: ForumPolicies,
    private val registry: TenantRegistry,
    private val tenancy: TenancyProperties,
    private val locales: RecipientLocales,
    private val invitees: InviteeStore,
    private val ids: IdGenerator,
) {
    private val texts = InviteTexts()

    fun context(
        meeting: MeetingRow,
        invitation: InvitationRow,
    ): InvitationContext {
        val forum = checkNotNull(directory.forum(meeting.forumId)) { "forum ${meeting.forumId}" }
        val forumType = forum.typeId
        val policy = policies.version(forumType, meeting.typeVersion)?.meetings ?: MeetingPolicy()
        val neutral = policy.invitationTitle == InvitationTitle.NEUTRAL
        val tenant = registry.current()
        return InvitationContext(
            meeting = meeting,
            invitation = invitation,
            forum = forum,
            summary = if (neutral) policy.neutralTitle else meeting.title ?: forum.name,
            committee = if (neutral) policy.neutralTitle else forum.name,
            confidential = neutral,
            organizer = InviteParty(forum.name, sending.address()),
            url = tenant?.let { tenancy.publicUrl(it.slug) }.orEmpty() + link(meeting.id),
            organisation = tenant?.name.orEmpty(),
        )
    }

    /**
     * Sends [message] to [recipient], who held [holding] before it ([guest]: an optional participant); [changes] for an
     * update; [by] the secretary of a resend. Answers the logged message.
     */
    @Suppress("LongParameterList")
    fun send(
        context: InvitationContext,
        message: PlannedMessage,
        recipient: Recipient,
        holding: Holding?,
        guest: Boolean,
        changes: Set<MeetingChangeKind>,
        by: UUID?,
        at: Instant,
    ): InvitationMessageRow {
        val locale = locales.of(recipient.locale)
        val invite =
            Invite(
                kind = INVITE_KINDS.getValue(message.kind),
                event = event(context, message.sequence),
                organizer = context.organizer,
                attendee = InviteParty(recipient.displayName, recipient.email),
                guest = guest,
                locale = locale,
            )
        val file = writer.write(invite)
        val told = if (message.kind == InvitationMessageKind.UPDATE) changes else emptySet()
        val params =
            mapOf(
                "committee" to context.committee,
                "date" to context.meeting.date.toString(),
                "organisation" to context.organisation,
                "link" to context.url,
            ) + listOfNotNull(("changes" to texts.changes(told.map { it.name }, locale)).takeIf { told.isNotEmpty() })
        val result =
            notifications.notify(
                recipient,
                NOTIFICATION_KINDS.getValue(message.kind),
                params,
                link(context.meeting.id),
                CalendarPart(message.kind.method, file),
            )
        val row =
            InvitationMessageRow(ids.next(), message.userId, message.sequence, message.kind, told, result.outboxId, result.itemId, by, at)
        invitees.sent(context.meeting.id, row, InvitationPlan.after(holding, message, context.invitation.sequence))
        return row
    }

    /** "Add to my calendar" (design E05 §3): the PUBLISH file of the current version, without ATTENDEE, in [locale]. */
    fun download(
        context: InvitationContext,
        localeTag: String?,
    ): ByteArray =
        writer.write(
            Invite(
                InviteKind.PUBLISH,
                event(context, context.invitation.sequence),
                context.organizer,
                null,
                locale = locales.of(localeTag),
            ),
        )

    private fun event(
        context: InvitationContext,
        sequence: Int,
    ): InviteEvent {
        val meeting = context.meeting
        return InviteEvent(
            uid = context.invitation.icalUid,
            sequence = sequence,
            startsAt = meeting.startsAt,
            endsAt = meeting.endsAt,
            zone = meeting.zone,
            summary = context.summary,
            confidential = context.confidential,
            mode = meeting.mode,
            location = meeting.location,
            joinUrl = meeting.joinUrl,
            joinDetails = meeting.joinDetails,
            meetingUrl = context.url,
        )
    }

    private companion object {
        fun link(meetingId: UUID) = "/meetings/$meetingId"

        val INVITE_KINDS =
            mapOf(
                InvitationMessageKind.INVITATION to InviteKind.REQUEST,
                InvitationMessageKind.UPDATE to InviteKind.REQUEST,
                InvitationMessageKind.RESEND to InviteKind.REQUEST,
                InvitationMessageKind.UNINVITE to InviteKind.UNINVITE,
                InvitationMessageKind.CANCEL to InviteKind.CANCEL,
            )

        val NOTIFICATION_KINDS =
            mapOf(
                InvitationMessageKind.INVITATION to NotificationKind.MEETING_INVITATION,
                InvitationMessageKind.UPDATE to NotificationKind.MEETING_UPDATED,
                InvitationMessageKind.RESEND to NotificationKind.MEETING_INVITATION,
                InvitationMessageKind.UNINVITE to NotificationKind.MEETING_UNINVITED,
                InvitationMessageKind.CANCEL to NotificationKind.MEETING_CANCELLED,
            )
    }
}
