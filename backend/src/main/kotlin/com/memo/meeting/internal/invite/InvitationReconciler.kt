package com.memo.meeting.internal.invite

import com.memo.audit.AuditLog
import com.memo.common.authz.Clearances
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantTransactions
import com.memo.identity.UserStatus
import com.memo.identity.UserSummaries
import com.memo.meeting.MeetingStatus
import com.memo.meeting.internal.AudienceSource
import com.memo.meeting.internal.AudienceStore
import com.memo.meeting.internal.MeetingLifecycle
import com.memo.meeting.internal.MeetingRow
import com.memo.meeting.internal.MeetingStore
import com.memo.meeting.internal.ical.InviteInvalidException
import com.memo.meeting.internal.ical.InviteRefusedException
import com.memo.notification.RecipientLookup
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** Someone who should hold a meeting's event now; a [guest] is an optional participant. */
data class InvitablePerson(
    val userId: UUID,
    val guest: Boolean,
)

/**
 * Who is invitable (ADR-0063, design E05 §1.1): an audience row as MEMBER (a roster seat in office on the meeting's date),
 * GUEST or ALTERNATE (standing in for a member, §1.5), not removed, whose user is active, has an email and is cleared for
 * the meeting's classification (they could not open it otherwise). Item attendees, presenters and officers without a seat
 * are not invited.
 */
@Component
class InvitableAudience(
    private val audience: AudienceStore,
    private val users: UserSummaries,
    private val clearances: Clearances,
) {
    fun of(meeting: MeetingRow): Map<UUID, InvitablePerson> {
        val rows = audience.audience(meeting.id).filter { it.source in INVITED_SOURCES }.groupBy { it.userId }
        val summaries = users.of(rows.keys)
        val ranks = clearances.rankOf(rows.keys)
        val floor = clearances.level(meeting.classificationId)?.rank ?: Int.MAX_VALUE
        return rows
            .filter { (user, _) ->
                val summary = summaries[user]
                summary != null && summary.status == UserStatus.ACTIVE && summary.email.isNotBlank() && (ranks[user] ?: 0) >= floor
            }.mapValues { (user, sources) -> InvitablePerson(user, sources.none { it.source in PARTICIPANTS }) }
    }

    private companion object {
        val INVITED_SOURCES = setOf(AudienceSource.MEMBER, AudienceSource.GUEST, AudienceSource.ALTERNATE)

        /** Required participants in the file; a guest is optional. */
        val PARTICIPANTS = setOf(AudienceSource.MEMBER, AudienceSource.ALTERNATE)
    }
}

/**
 * The `meeting-invitations` reconciler (ADR-0063, design E05 §1.3): in one tenant transaction, under the invitation's
 * row lock, it compares what each person should hold with what they were last sent and sends only the difference
 * ([InvitationPlan]): invitations, updates, uninvitations, and the cancellation. Idempotent (a retry finds nothing to
 * send) and coalescing (it waits until the send is due, so quick edits make one update). Nothing is sent once the meeting
 * has started. A file that fails validation fails the run (audited, nothing sent); the job retries.
 */
@Service
class InvitationReconciler(
    private val invitations: InvitationStore,
    private val invitees: InviteeStore,
    private val meetings: MeetingStore,
    private val invitable: InvitableAudience,
    private val recipients: RecipientLookup,
    private val messages: InvitationMessages,
    private val transactions: TenantTransactions,
    private val audit: AuditLog,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** One step of the job at [at]: the wait until the send is due, or null when it is done. */
    fun run(
        meetingId: UUID,
        at: Instant = clock.instant(),
    ): Duration? =
        try {
            transactions.inTenant(TenantContext.current()) { reconcile(meetingId, at) }
        } catch (e: InviteInvalidException) {
            failed(meetingId, e.problems)
            throw e
        } catch (e: InviteRefusedException) {
            failed(meetingId, listOf(e.message.orEmpty()))
            throw e
        }

    private fun reconcile(
        meetingId: UUID,
        at: Instant,
    ): Duration? {
        val invitation = invitations.lock(meetingId)
        val due = invitation?.dueAt
        val meeting = meetings.find(meetingId)
        return when {
            invitation == null || due == null || meeting == null -> {
                null
            }

            due.isAfter(at) -> {
                Duration.between(at, due)
            }

            meeting.status != MeetingStatus.SCHEDULED && meeting.status != MeetingStatus.CANCELLED -> {
                invitations.settled(meetingId, at)
                null
            }

            else -> {
                send(meeting, invitation, at)
                null
            }
        }
    }

    private fun send(
        meeting: MeetingRow,
        invitation: InvitationRow,
        at: Instant,
    ) {
        val people = invitable.of(meeting)
        val holdings = invitees.invitees(meeting.id).associate { it.userId to it.holding }
        val cancelled = meeting.status == MeetingStatus.CANCELLED || invitation.cancelledAt != null
        val plan = InvitationPlan.reconcile(invitation.sequence, cancelled, people.keys, holdings)
        val context = messages.context(meeting, invitation)
        val sent =
            plan.mapNotNull { message ->
                recipients.recipient(message.userId)?.let { recipient ->
                    val guest = people[message.userId]?.guest ?: false
                    messages.send(context, message, recipient, holdings[message.userId], guest, invitation.pendingChanges, null, at)
                }
            }
        invitations.reconciled(meeting.id, invitation.sequence, at)
        sent.groupBy { it.kind }.forEach { (kind, rows) ->
            audit.record(
                "meeting.invitations.sent",
                MeetingLifecycle.OBJECT_TYPE,
                meeting.id.toString(),
                after =
                    mapOf(
                        "sequence" to invitation.sequence,
                        "method" to kind.method.name,
                        "kind" to kind.name,
                        "recipients" to rows.size,
                        "userIds" to rows.map { it.userId.toString() },
                        "changes" to rows.flatMap { it.changes }.distinct().map { it.name },
                    ),
            )
        }
    }

    /** Records a file the writer refused or could not validate, in its own transaction (the run's rolls back). */
    private fun failed(
        meetingId: UUID,
        problems: List<String>,
    ) {
        log.error("Meeting {} invitations failed validation: {}", meetingId, problems)
        transactions.inTenant(TenantContext.current()) {
            val sequence = invitations.find(meetingId)?.sequence
            audit.record(
                "meeting.invitation.validation_failed",
                MeetingLifecycle.OBJECT_TYPE,
                meetingId.toString(),
                after = mapOf("sequence" to sequence, "problems" to problems),
            )
        }
    }
}

/**
 * The reconciler's safety net (design E05 §9 (h)): an invitation whose send is overdue by 15 minutes (its job gave up
 * after a validation failure, or a change landed while the job was finishing) is scheduled again. The hourly meeting
 * sweep (E03 T5, E05 T6) calls it per tenant, inside a tenant transaction.
 */
@Component
class InvitationSafetyNet(
    private val invitations: InvitationStore,
    private val scheduler: InvitationScheduler,
    private val clock: Clock,
) {
    /** The meetings scheduled again. */
    fun rescheduleOverdue(now: Instant = clock.instant()): List<UUID> =
        invitations.overdue(now.minus(OVERDUE)).onEach { scheduler.now(it, now) }

    private companion object {
        val OVERDUE: Duration = Duration.ofMinutes(15)
    }
}
