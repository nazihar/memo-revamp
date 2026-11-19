package com.memo.meeting.internal.invite

import com.memo.common.tenant.TenantDsl
import com.memo.jooq.tables.records.MeetingInvitationRecord
import com.memo.jooq.tables.records.MeetingInviteeRecord
import com.memo.jooq.tables.references.MEETING_INVITATION
import com.memo.jooq.tables.references.MEETING_INVITATION_MESSAGE
import com.memo.jooq.tables.references.MEETING_INVITEE
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/** A meeting's invitation (design E05 §1.1): its UID, the sequence of the version to send, and when a send is due. */
data class InvitationRow(
    val meetingId: UUID,
    val icalUid: UUID,
    val sequence: Int,
    /** The sequence last sent by the reconciler; null before its first send. */
    val sentSequence: Int?,
    /** What changed since the last send. */
    val pendingChanges: Set<MeetingChangeKind>,
    val cancelledAt: Instant?,
    val dueAt: Instant?,
    val reconciledAt: Instant?,
    val version: Long,
)

/** One person the invitation was ever sent to, and the last message they were sent. */
data class InviteeRow(
    val userId: UUID,
    val state: InviteeState,
    val lastSequence: Int,
    val heldSequence: Int,
    val lastKind: InvitationMessageKind,
    val lastOutboxId: UUID?,
    val firstSentAt: Instant,
    val lastSentAt: Instant,
) {
    val holding: Holding get() = Holding(userId, state, lastSequence, heldSequence)
}

/** One message as it was queued (append-only). */
data class InvitationMessageRow(
    val id: UUID,
    val userId: UUID,
    val sequence: Int,
    val kind: InvitationMessageKind,
    val changes: Set<MeetingChangeKind>,
    val outboxId: UUID?,
    val notificationId: UUID?,
    /** The secretary for a resend; null for the reconciler. */
    val queuedBy: UUID?,
    val queuedAt: Instant,
)

/**
 * Meeting invitations, their invitees and the message log (design E05 §1.1, §6), inside a tenant transaction. Nothing is
 * deleted; the invitation and invitees change only forward (the tables' triggers refuse a lower sequence or a new UID).
 */
@Repository
class InvitationStore(
    private val tenantDsl: TenantDsl,
) {
    fun find(meetingId: UUID): InvitationRow? =
        tenantDsl
            .current()
            .selectFrom(MEETING_INVITATION)
            .where(MEETING_INVITATION.MEETING_ID.eq(meetingId))
            .fetchOne { it.row() }

    /** The invitation locked for this transaction (`FOR UPDATE`): the reconciler and a resend take it before sending. */
    fun lock(meetingId: UUID): InvitationRow? =
        tenantDsl
            .current()
            .selectFrom(MEETING_INVITATION)
            .where(MEETING_INVITATION.MEETING_ID.eq(meetingId))
            .forUpdate()
            .fetchOne { it.row() }

    /** Creates the invitation at sequence 0, due at [dueAt]; false when the meeting already has one. */
    fun create(
        meetingId: UUID,
        uid: UUID,
        now: Instant,
        dueAt: Instant,
    ): Boolean =
        tenantDsl
            .current()
            .insertInto(MEETING_INVITATION)
            .set(MEETING_INVITATION.TENANT_ID, tenantDsl.tenant().value)
            .set(MEETING_INVITATION.MEETING_ID, meetingId)
            .set(MEETING_INVITATION.ICAL_UID, uid)
            .set(MEETING_INVITATION.SEQUENCE, 0)
            .set(MEETING_INVITATION.CREATED_AT, utc(now))
            .set(MEETING_INVITATION.DUE_AT, utc(dueAt))
            .onConflictDoNothing()
            .execute() == 1

    /**
     * A change the invitees must hear of: the sequence rises once per batch ([InvitationPlan.afterChange]), [pending] are
     * the changes not sent yet (the earlier pending ones and this one's; the caller holds the meeting's row lock), and the
     * send is due at [dueAt] (debounced: the last change decides).
     */
    fun changed(
        meetingId: UUID,
        pending: Set<MeetingChangeKind>,
        dueAt: Instant,
    ) {
        tenantDsl
            .current()
            .update(MEETING_INVITATION)
            .set(MEETING_INVITATION.SEQUENCE, sequenceAfterChange())
            .set(MEETING_INVITATION.PENDING_CHANGES, pending.sorted().map { it.name }.toTypedArray<String?>())
            .set(MEETING_INVITATION.DUE_AT, utc(dueAt))
            .set(MEETING_INVITATION.VERSION, MEETING_INVITATION.VERSION.plus(1))
            .where(MEETING_INVITATION.MEETING_ID.eq(meetingId), MEETING_INVITATION.CANCELLED_AT.isNull)
            .execute()
    }

    /** The audience changed: a send is due at [dueAt], at the same sequence (newcomers get it, leavers are uninvited). */
    fun touched(
        meetingId: UUID,
        dueAt: Instant,
    ) {
        tenantDsl
            .current()
            .update(MEETING_INVITATION)
            .set(MEETING_INVITATION.DUE_AT, utc(dueAt))
            .set(MEETING_INVITATION.VERSION, MEETING_INVITATION.VERSION.plus(1))
            .where(MEETING_INVITATION.MEETING_ID.eq(meetingId), MEETING_INVITATION.CANCELLED_AT.isNull)
            .execute()
    }

    /** The meeting was called off: the sequence rises (once per batch), and the CANCEL is due at once. */
    fun cancelled(
        meetingId: UUID,
        now: Instant,
    ): Boolean =
        tenantDsl
            .current()
            .update(MEETING_INVITATION)
            .set(MEETING_INVITATION.SEQUENCE, sequenceAfterChange())
            .set(MEETING_INVITATION.CANCELLED_AT, utc(now))
            .set(MEETING_INVITATION.DUE_AT, utc(now))
            .set(MEETING_INVITATION.VERSION, MEETING_INVITATION.VERSION.plus(1))
            .where(MEETING_INVITATION.MEETING_ID.eq(meetingId), MEETING_INVITATION.CANCELLED_AT.isNull)
            .execute() == 1

    /** The reconciler sent the version at [sequence]: nothing is due or pending any more. */
    fun reconciled(
        meetingId: UUID,
        sequence: Int,
        now: Instant,
    ) {
        tenantDsl
            .current()
            .update(MEETING_INVITATION)
            .set(MEETING_INVITATION.SENT_SEQUENCE, sequence)
            .set(MEETING_INVITATION.PENDING_CHANGES, emptyArray<String?>())
            .setNull(MEETING_INVITATION.DUE_AT)
            .set(MEETING_INVITATION.RECONCILED_AT, utc(now))
            .set(MEETING_INVITATION.VERSION, MEETING_INVITATION.VERSION.plus(1))
            .where(MEETING_INVITATION.MEETING_ID.eq(meetingId))
            .execute()
    }

    /** Nothing more is sent (the meeting started or was held): the pending send is dropped. */
    fun settled(
        meetingId: UUID,
        now: Instant,
    ) {
        tenantDsl
            .current()
            .update(MEETING_INVITATION)
            .setNull(MEETING_INVITATION.DUE_AT)
            .set(MEETING_INVITATION.RECONCILED_AT, utc(now))
            .set(MEETING_INVITATION.VERSION, MEETING_INVITATION.VERSION.plus(1))
            .where(MEETING_INVITATION.MEETING_ID.eq(meetingId))
            .execute()
    }

    /** Invitations whose send was due before [before]: the safety net schedules them again (design E05 §9 (h)). */
    fun overdue(before: Instant): List<UUID> =
        tenantDsl
            .current()
            .select(MEETING_INVITATION.MEETING_ID)
            .from(MEETING_INVITATION)
            .where(MEETING_INVITATION.DUE_AT.lt(utc(before)))
            .orderBy(MEETING_INVITATION.DUE_AT)
            .fetch { it.value1()!! }

    private fun sequenceAfterChange() =
        DSL
            .`when`(MEETING_INVITATION.SENT_SEQUENCE.eq(MEETING_INVITATION.SEQUENCE), MEETING_INVITATION.SEQUENCE.plus(1))
            .otherwise(MEETING_INVITATION.SEQUENCE)
}

/** The people a meeting's invitation was sent to, and the message log (design E05 §1.1), inside a tenant transaction. */
@Repository
class InviteeStore(
    private val tenantDsl: TenantDsl,
) {
    fun invitees(meetingId: UUID): List<InviteeRow> =
        tenantDsl
            .current()
            .selectFrom(MEETING_INVITEE)
            .where(MEETING_INVITEE.MEETING_ID.eq(meetingId))
            .orderBy(MEETING_INVITEE.USER_ID)
            .fetch { it.row() }

    /** The people who hold the event now (INVITED). */
    fun invited(meetingId: UUID): Set<UUID> = invitees(meetingId).filter { it.state == InviteeState.INVITED }.map { it.userId }.toSet()

    /** Records [message] to its person: their row follows it, and the message joins the log. */
    fun sent(
        meetingId: UUID,
        message: InvitationMessageRow,
        held: Holding,
    ) {
        val dsl = tenantDsl.current()
        val tenant = tenantDsl.tenant().value
        val at = utc(message.queuedAt)
        dsl
            .insertInto(MEETING_INVITEE)
            .set(MEETING_INVITEE.TENANT_ID, tenant)
            .set(MEETING_INVITEE.MEETING_ID, meetingId)
            .set(MEETING_INVITEE.USER_ID, message.userId)
            .set(MEETING_INVITEE.STATE, held.state.name)
            .set(MEETING_INVITEE.LAST_SEQUENCE, held.lastSequence)
            .set(MEETING_INVITEE.HELD_SEQUENCE, held.heldSequence)
            .set(MEETING_INVITEE.LAST_METHOD, message.kind.method.name)
            .set(MEETING_INVITEE.LAST_KIND, message.kind.name)
            .set(MEETING_INVITEE.LAST_OUTBOX_ID, message.outboxId)
            .set(MEETING_INVITEE.FIRST_SENT_AT, at)
            .set(MEETING_INVITEE.LAST_SENT_AT, at)
            .onConflict(MEETING_INVITEE.TENANT_ID, MEETING_INVITEE.MEETING_ID, MEETING_INVITEE.USER_ID)
            .doUpdate()
            .set(MEETING_INVITEE.STATE, held.state.name)
            .set(MEETING_INVITEE.LAST_SEQUENCE, held.lastSequence)
            .set(MEETING_INVITEE.HELD_SEQUENCE, held.heldSequence)
            .set(MEETING_INVITEE.LAST_METHOD, message.kind.method.name)
            .set(MEETING_INVITEE.LAST_KIND, message.kind.name)
            .set(MEETING_INVITEE.LAST_OUTBOX_ID, message.outboxId)
            // Never before the first message, whatever another node's clock says (the table checks it).
            .set(MEETING_INVITEE.LAST_SENT_AT, DSL.greatest(MEETING_INVITEE.FIRST_SENT_AT, DSL.value(at)))
            .set(MEETING_INVITEE.VERSION, MEETING_INVITEE.VERSION.plus(1))
            .execute()
        dsl
            .insertInto(MEETING_INVITATION_MESSAGE)
            .set(MEETING_INVITATION_MESSAGE.ID, message.id)
            .set(MEETING_INVITATION_MESSAGE.TENANT_ID, tenant)
            .set(MEETING_INVITATION_MESSAGE.MEETING_ID, meetingId)
            .set(MEETING_INVITATION_MESSAGE.USER_ID, message.userId)
            .set(MEETING_INVITATION_MESSAGE.SEQUENCE, message.sequence)
            .set(MEETING_INVITATION_MESSAGE.METHOD, message.kind.method.name)
            .set(MEETING_INVITATION_MESSAGE.KIND, message.kind.name)
            .set(
                MEETING_INVITATION_MESSAGE.CHANGES,
                message.changes
                    .sorted()
                    .map { it.name }
                    .toTypedArray<String?>(),
            ).set(MEETING_INVITATION_MESSAGE.OUTBOX_ID, message.outboxId)
            .set(MEETING_INVITATION_MESSAGE.NOTIFICATION_ID, message.notificationId)
            .set(MEETING_INVITATION_MESSAGE.QUEUED_BY, message.queuedBy)
            .set(MEETING_INVITATION_MESSAGE.QUEUED_AT, at)
            .execute()
    }

    /** The message log of [meetingId], oldest first. */
    fun messages(meetingId: UUID): List<InvitationMessageRow> =
        tenantDsl
            .current()
            .selectFrom(MEETING_INVITATION_MESSAGE)
            .where(MEETING_INVITATION_MESSAGE.MEETING_ID.eq(meetingId))
            .orderBy(MEETING_INVITATION_MESSAGE.QUEUED_AT, MEETING_INVITATION_MESSAGE.ID)
            .fetch {
                InvitationMessageRow(
                    id = it.id,
                    userId = it.userId,
                    sequence = it.sequence,
                    kind = InvitationMessageKind.valueOf(it.kind),
                    changes = changes(it.changes),
                    outboxId = it.outboxId,
                    notificationId = it.notificationId,
                    queuedBy = it.queuedBy,
                    queuedAt = it.queuedAt.toInstant(),
                )
            }
}

private fun utc(at: Instant): OffsetDateTime = at.atOffset(ZoneOffset.UTC)

private fun changes(values: Array<String?>?): Set<MeetingChangeKind> =
    values
        .orEmpty()
        .filterNotNull()
        .map { MeetingChangeKind.valueOf(it) }
        .toSortedSet()

private fun MeetingInvitationRecord.row() =
    InvitationRow(
        meetingId = meetingId,
        icalUid = icalUid,
        sequence = sequence ?: 0,
        sentSequence = sentSequence,
        pendingChanges = changes(pendingChanges),
        cancelledAt = cancelledAt?.toInstant(),
        dueAt = dueAt?.toInstant(),
        reconciledAt = reconciledAt?.toInstant(),
        version = version ?: 0,
    )

private fun MeetingInviteeRecord.row() =
    InviteeRow(
        userId = userId,
        state = InviteeState.valueOf(state),
        lastSequence = lastSequence,
        heldSequence = heldSequence,
        lastKind = InvitationMessageKind.valueOf(lastKind),
        lastOutboxId = lastOutboxId,
        firstSentAt = firstSentAt.toInstant(),
        lastSentAt = lastSentAt.toInstant(),
    )
