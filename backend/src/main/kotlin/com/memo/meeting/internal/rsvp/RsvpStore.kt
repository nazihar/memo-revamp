package com.memo.meeting.internal.rsvp

import com.memo.common.tenant.TenantDsl
import com.memo.jooq.tables.records.MeetingRsvpRecord
import com.memo.jooq.tables.references.MEETING_RSVP
import com.memo.jooq.tables.references.MEETING_RSVP_EVENT
import org.springframework.stereotype.Repository
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/** A person's current answer for a meeting (design E05 §1.4). */
data class RsvpRow(
    val meetingId: UUID,
    val userId: UUID,
    val answer: RsvpAnswer,
    val alternateAttends: Boolean,
    val note: String?,
    /** The meeting's times when the answer was given. */
    val forStartsAt: Instant,
    val forEndsAt: Instant,
    val recordedBy: UUID,
    val how: RsvpHow,
    val howNote: String?,
    val answeredAt: Instant,
    val version: Long,
) {
    /** Recorded by someone else (a secretary) on the person's behalf. */
    val onBehalf: Boolean get() = how != RsvpHow.IN_APP
}

/** One answer of the history (append-only): the note only as its hash. */
data class RsvpEventRow(
    val id: UUID,
    val meetingId: UUID,
    val userId: UUID,
    val answer: RsvpAnswer,
    val alternateAttends: Boolean,
    val noteSha256: String?,
    val forStartsAt: Instant,
    val forEndsAt: Instant,
    val how: RsvpHow,
    val recordedBy: UUID,
    val at: Instant,
)

/**
 * Replies (design E05 §1.4, §6), inside a tenant transaction: the current row per person, written by an insert or a
 * guarded update on its version, and the append-only history.
 */
@Repository
class RsvpStore(
    private val tenantDsl: TenantDsl,
) {
    fun find(
        meetingId: UUID,
        userId: UUID,
    ): RsvpRow? =
        tenantDsl
            .current()
            .selectFrom(MEETING_RSVP)
            .where(MEETING_RSVP.MEETING_ID.eq(meetingId), MEETING_RSVP.USER_ID.eq(userId))
            .fetchOne { it.row() }

    /** Every answer of [meetingId], by person. */
    fun ofMeeting(meetingId: UUID): Map<UUID, RsvpRow> =
        tenantDsl
            .current()
            .selectFrom(MEETING_RSVP)
            .where(MEETING_RSVP.MEETING_ID.eq(meetingId))
            .fetch { it.row() }
            .associateBy { it.userId }

    /** [userId]'s answers for each of [meetingIds] that has one, in one query on `(tenant_id, user_id, meeting_id)`. */
    fun mine(
        meetingIds: Collection<UUID>,
        userId: UUID,
    ): Map<UUID, RsvpRow> =
        if (meetingIds.isEmpty()) {
            emptyMap()
        } else {
            tenantDsl
                .current()
                .selectFrom(MEETING_RSVP)
                .where(MEETING_RSVP.USER_ID.eq(userId), MEETING_RSVP.MEETING_ID.`in`(meetingIds))
                .fetch { it.row() }
                .associateBy { it.meetingId }
        }

    /** The first answer of the person; false when one exists already (a concurrent first answer: the caller's 412). */
    fun insert(row: RsvpRow): Boolean =
        tenantDsl
            .current()
            .insertInto(MEETING_RSVP)
            .set(MEETING_RSVP.TENANT_ID, tenantDsl.tenant().value)
            .set(MEETING_RSVP.MEETING_ID, row.meetingId)
            .set(MEETING_RSVP.USER_ID, row.userId)
            .set(MEETING_RSVP.VERSION, 0L)
            .set(values(row))
            .onConflictDoNothing()
            .execute() == 1

    /** Replaces the answer at [version] with [row]; false when the version moved on (412). */
    fun update(
        row: RsvpRow,
        version: Long,
    ): Boolean =
        tenantDsl
            .current()
            .update(MEETING_RSVP)
            .set(values(row))
            .set(MEETING_RSVP.VERSION, version + 1)
            .where(MEETING_RSVP.MEETING_ID.eq(row.meetingId), MEETING_RSVP.USER_ID.eq(row.userId), MEETING_RSVP.VERSION.eq(version))
            .execute() == 1

    fun event(event: RsvpEventRow) {
        tenantDsl
            .current()
            .insertInto(MEETING_RSVP_EVENT)
            .set(MEETING_RSVP_EVENT.ID, event.id)
            .set(MEETING_RSVP_EVENT.TENANT_ID, tenantDsl.tenant().value)
            .set(MEETING_RSVP_EVENT.MEETING_ID, event.meetingId)
            .set(MEETING_RSVP_EVENT.USER_ID, event.userId)
            .set(MEETING_RSVP_EVENT.ANSWER, event.answer.name)
            .set(MEETING_RSVP_EVENT.ALTERNATE_ATTENDS, event.alternateAttends)
            .set(MEETING_RSVP_EVENT.NOTE_SHA256, event.noteSha256)
            .set(MEETING_RSVP_EVENT.FOR_STARTS_AT, utc(event.forStartsAt))
            .set(MEETING_RSVP_EVENT.FOR_ENDS_AT, utc(event.forEndsAt))
            .set(MEETING_RSVP_EVENT.HOW, event.how.name)
            .set(MEETING_RSVP_EVENT.RECORDED_BY, event.recordedBy)
            .set(MEETING_RSVP_EVENT.AT, utc(event.at))
            .execute()
    }

    /** The history of [userId]'s answers for [meetingId], the oldest first. */
    fun events(
        meetingId: UUID,
        userId: UUID,
    ): List<RsvpEventRow> =
        tenantDsl
            .current()
            .selectFrom(MEETING_RSVP_EVENT)
            .where(MEETING_RSVP_EVENT.MEETING_ID.eq(meetingId), MEETING_RSVP_EVENT.USER_ID.eq(userId))
            .orderBy(MEETING_RSVP_EVENT.AT, MEETING_RSVP_EVENT.ID)
            .fetch {
                RsvpEventRow(
                    id = it.id,
                    meetingId = it.meetingId,
                    userId = it.userId,
                    answer = RsvpAnswer.valueOf(it.answer),
                    alternateAttends = it.alternateAttends,
                    noteSha256 = it.noteSha256,
                    forStartsAt = it.forStartsAt.toInstant(),
                    forEndsAt = it.forEndsAt.toInstant(),
                    how = RsvpHow.valueOf(it.how),
                    recordedBy = it.recordedBy,
                    at = it.at.toInstant(),
                )
            }

    private fun values(row: RsvpRow): Map<org.jooq.Field<*>, Any?> =
        mapOf(
            MEETING_RSVP.ANSWER to row.answer.name,
            MEETING_RSVP.ALTERNATE_ATTENDS to row.alternateAttends,
            MEETING_RSVP.NOTE to row.note,
            MEETING_RSVP.FOR_STARTS_AT to utc(row.forStartsAt),
            MEETING_RSVP.FOR_ENDS_AT to utc(row.forEndsAt),
            MEETING_RSVP.RECORDED_BY to row.recordedBy,
            MEETING_RSVP.HOW to row.how.name,
            MEETING_RSVP.HOW_NOTE to row.howNote,
            MEETING_RSVP.ANSWERED_AT to utc(row.answeredAt),
        )

    private fun MeetingRsvpRecord.row() =
        RsvpRow(
            meetingId = meetingId,
            userId = userId,
            answer = RsvpAnswer.valueOf(answer),
            alternateAttends = checkNotNull(alternateAttends),
            note = note,
            forStartsAt = forStartsAt.toInstant(),
            forEndsAt = forEndsAt.toInstant(),
            recordedBy = recordedBy,
            how = RsvpHow.valueOf(how),
            howNote = howNote,
            answeredAt = answeredAt.toInstant(),
            version = checkNotNull(version),
        )

    private fun utc(at: Instant): OffsetDateTime = at.atOffset(ZoneOffset.UTC)
}
