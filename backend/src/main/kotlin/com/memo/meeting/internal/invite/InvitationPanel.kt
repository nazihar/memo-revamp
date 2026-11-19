package com.memo.meeting.internal.invite

import com.memo.audit.AuditLog
import com.memo.common.authz.ForbiddenException
import com.memo.common.authz.Permission
import com.memo.common.web.FieldProblem
import com.memo.common.web.refuseIf
import com.memo.identity.UserSummaries
import com.memo.meeting.MeetingStatus
import com.memo.meeting.internal.MeetingAccess
import com.memo.meeting.internal.MeetingGuard
import com.memo.meeting.internal.MeetingInvitationSummaryResponse
import com.memo.meeting.internal.MeetingLifecycle
import com.memo.meeting.internal.MeetingNotFoundException
import com.memo.meeting.internal.MeetingRelation
import com.memo.meeting.internal.actorOrNull
import com.memo.meeting.internal.requireNoProblems
import com.memo.meeting.internal.rsvp.ReplyReads
import com.memo.meeting.internal.rsvp.ReplyResponse
import com.memo.notification.MailState
import com.memo.notification.MailStatus
import com.memo.notification.MailStatuses
import com.memo.notification.RecipientLookup
import io.swagger.v3.oas.annotations.media.Schema
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** Where a person stands with a meeting's invitation, for the panel: [NOT_INVITED] has had no message yet. */
enum class InvitationState { NOT_INVITED, INVITED, UNINVITED, CANCELLED }

/** The relay's answer for a person's last message (ADR-0030's delivery log). */
data class InvitationDeliveryResponse(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val state: MailState,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val attempts: Int,
    /** The relay's last SMTP reply code when it refused or deferred the mail. */
    val smtpCode: Int?,
    val lastAttemptAt: Instant?,
    val sentAt: Instant?,
    val failedAt: Instant?,
)

/** One person on the invitations panel (design E05 §3): never the UID. */
data class InviteeResponse(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val userId: UUID,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val name: String,
    /** Whether they should hold the event now (in the audience as a member, alternate or guest, active, cleared). */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val invitable: Boolean,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val guest: Boolean,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val state: InvitationState,
    /** The SEQUENCE of their last file. */
    val lastSequence: Int?,
    val lastKind: InvitationMessageKind?,
    val firstSentAt: Instant?,
    val lastSentAt: Instant?,
    /** They switched invitation emails off: the last message reached them in the app only. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val emailOff: Boolean,
    val delivery: InvitationDeliveryResponse?,
    /** From when a resend is allowed (10 minutes after their last message); null when it is now. */
    val resendFrom: Instant?,
    /** Their reply to "Will you attend?" (design E05 §3, flow W1); null when they do not answer the meeting. */
    val reply: ReplyResponse? = null,
)

/** A meeting's invitations as its officers see them (design E05 §3, flow W). */
data class InvitationsResponse(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val meetingId: UUID,
    /** False for a draft: invitations are created when the meeting is scheduled. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val created: Boolean,
    /** The version of the meeting the invitations describe. */
    val sequence: Int?,
    /** A send is waiting (debounced after a change). */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val pending: Boolean,
    val dueAt: Instant?,
    /** When the invitations were last brought up to date. */
    val reconciledAt: Instant?,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val cancelled: Boolean,
    /** Whether the viewer may resend (a secretary or `forums.manage`, the meeting scheduled). */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val canResend: Boolean,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val people: List<InviteeResponse>,
)

/**
 * The invitations panel and resend (design E05 §1.3, §3, §4): officers (secretaries, the chair, `forums.manage`) read
 * who was invited, with what and when, and the relay's answer; secretaries and `forums.manage` resend the current
 * version, at most once per person per 10 minutes. Inside a tenant transaction.
 */
@Service
class InvitationPanel(
    private val guard: MeetingGuard,
    private val access: MeetingAccess,
    private val invitations: InvitationStore,
    private val invitees: InviteeStore,
    private val invitable: InvitableAudience,
    private val messages: InvitationMessages,
    private val recipients: RecipientLookup,
    private val statuses: MailStatuses,
    private val users: UserSummaries,
    private val replies: ReplyReads,
    private val audit: AuditLog,
    private val clock: Clock,
) {
    fun panel(meetingId: UUID): InvitationsResponse {
        val (meeting, relation) = guard.requireVisible(meetingId)
        if (relation != MeetingRelation.OFFICER) throw ForbiddenException(Permission.FORUMS_MANAGE)
        val invitation = invitations.find(meeting.id)
        val people = invitable.of(meeting).takeIf { invitation != null }.orEmpty()
        val rows = invitees.invitees(meeting.id).associateBy { it.userId }
        val ids = people.keys + rows.keys
        val names = users.of(ids).mapValues { it.value.displayName }
        val mail = statuses.of(rows.values.mapNotNull { it.lastOutboxId })
        val now = clock.instant()
        val manages = access.manages(meeting.forumId)
        val answers = replies.ofOfficers(meeting)
        return InvitationsResponse(
            meetingId = meeting.id,
            created = invitation != null,
            sequence = invitation?.sequence,
            pending = invitation?.dueAt != null,
            dueAt = invitation?.dueAt,
            reconciledAt = invitation?.reconciledAt,
            cancelled = invitation?.cancelledAt != null,
            canResend = manages && meeting.status == MeetingStatus.SCHEDULED,
            people =
                ids
                    .map { id -> person(id, names[id].orEmpty(), people[id], rows[id], mail, now).copy(reply = answers[id]) }
                    .sortedWith(compareBy({ it.name.lowercase() }, { it.userId })),
        )
    }

    /** Resends the current version to [userIds] (every INVITED person when null); answers the panel. */
    fun resend(
        meetingId: UUID,
        userIds: Set<UUID>?,
        now: Instant = clock.instant(),
    ): InvitationsResponse {
        val meeting = guard.requireManaged(meetingId)
        refuseIf(meeting.status != MeetingStatus.SCHEDULED, "WRONG_STATE") { "Invitations are resent only while the meeting is scheduled." }
        val invitation = invitations.lock(meeting.id) ?: throw MeetingNotFoundException("invitation")
        val rows = invitees.invitees(meeting.id).associateBy { it.userId }
        val invited =
            rows.values
                .filter { it.state == InviteeState.INVITED }
                .map { it.userId }
                .toSet()
        val targets = userIds ?: invited
        requireNoProblems(
            listOfNotNull(
                FieldProblem("userIds", "NOT_INVITED", "Only people who hold the invitation can get it again.").takeIf {
                    !invited.containsAll(targets)
                },
            ),
        )
        refuseIf(targets.isEmpty(), "WRONG_STATE") { "Nobody holds the invitation yet: it is sent shortly after scheduling." }
        val tooSoon = targets.filter { rows.getValue(it).lastSentAt.isAfter(now.minus(RESEND_AFTER)) }
        refuseIf(tooSoon.isNotEmpty(), "RESEND_TOO_SOON") { "An invitation can be resent to a person once every 10 minutes." }
        val context = messages.context(meeting, invitation)
        val guests = invitable.of(meeting)
        val sent =
            targets.sorted().mapNotNull { user ->
                val holding = rows.getValue(user).holding
                val message = InvitationPlan.resend(invitation.sequence, holding)
                val recipient = recipients.recipient(user)
                if (message == null || recipient == null) {
                    null
                } else {
                    messages.send(
                        context,
                        message,
                        recipient,
                        holding,
                        guests[user]?.guest ?: false,
                        invitation.pendingChanges,
                        actorOrNull(),
                        now,
                    )
                }
            }
        audit.record(
            "meeting.invitations.resent",
            MeetingLifecycle.OBJECT_TYPE,
            meeting.id.toString(),
            after = mapOf("recipients" to sent.size, "userIds" to sent.map { it.userId.toString() }, "sequence" to invitation.sequence),
        )
        return panel(meeting.id)
    }

    /** When [meetingId]'s invitations went out (the meeting's header for officers); null before it is scheduled. */
    fun summary(meetingId: UUID): MeetingInvitationSummaryResponse? {
        val invitation = invitations.find(meetingId) ?: return null
        val sent = invitees.invitees(meetingId)
        return MeetingInvitationSummaryResponse(
            sequence = invitation.sequence,
            sentAt = sent.minOfOrNull { it.firstSentAt },
            lastSentAt = sent.maxOfOrNull { it.lastSentAt },
            pending = invitation.dueAt != null,
        )
    }

    /**
     * "Add to my calendar" (design E05 §3): the PUBLISH file of the current version, for the audience (members, guests,
     * item attendees) and officers; 404 for a draft or a cancelled meeting, 403 for a `forum.read` holder outside it.
     */
    fun calendar(meetingId: UUID): ByteArray {
        val (meeting, relation) = guard.requireVisible(meetingId)
        val invitation = invitations.find(meeting.id)?.takeIf { meeting.status !in NO_CALENDAR }
        if (invitation == null) throw MeetingNotFoundException("invitation")
        if (relation == MeetingRelation.READER) throw ForbiddenException(null)
        return messages.download(messages.context(meeting, invitation), actorOrNull()?.let { recipients.recipient(it) }?.locale)
    }

    @Suppress("LongParameterList")
    private fun person(
        id: UUID,
        name: String,
        invitable: InvitablePerson?,
        row: InviteeRow?,
        mail: Map<UUID, MailStatus>,
        now: Instant,
    ): InviteeResponse {
        val delivery = row?.lastOutboxId?.let { mail[it] }
        val resendFrom = row?.lastSentAt?.plus(RESEND_AFTER)?.takeIf { it.isAfter(now) }
        return InviteeResponse(
            userId = id,
            name = name,
            invitable = invitable != null,
            guest = invitable?.guest ?: false,
            state = row?.state?.let { InvitationState.valueOf(it.name) } ?: InvitationState.NOT_INVITED,
            lastSequence = row?.lastSequence,
            lastKind = row?.lastKind,
            firstSentAt = row?.firstSentAt,
            lastSentAt = row?.lastSentAt,
            emailOff = row != null && row.lastOutboxId == null,
            delivery =
                delivery?.let {
                    InvitationDeliveryResponse(it.state, it.attempts, it.smtpCode, it.lastAttemptAt, it.sentAt, it.failedAt)
                },
            resendFrom = resendFrom,
        )
    }

    companion object {
        /** A person gets the invitation again at most once in this time (design E05 §1.3, threat D1). */
        val RESEND_AFTER: Duration = Duration.ofMinutes(10)

        /** No calendar file for a draft (not yet invited) or a cancelled meeting. */
        private val NO_CALENDAR = setOf(MeetingStatus.DRAFT, MeetingStatus.CANCELLED)
    }
}
