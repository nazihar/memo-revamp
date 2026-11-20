package com.memo.meeting.internal.attendance

import com.memo.common.tenant.TenantDsl
import com.memo.forum.UnmetCondition
import com.memo.jooq.tables.records.MeetingAttendanceEventRecord
import com.memo.jooq.tables.records.MeetingAttendanceRecord
import com.memo.jooq.tables.references.MEETING_ATTENDANCE
import com.memo.jooq.tables.references.MEETING_ATTENDANCE_EVENT
import com.memo.meeting.AttendanceCapacity
import com.memo.meeting.AttendanceEventKind
import com.memo.meeting.AttendanceMode
import com.memo.meeting.AttendanceSource
import com.memo.meeting.AttendanceState
import com.memo.meeting.QuorumChangeKind
import org.springframework.stereotype.Repository
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/** A person's register row (design E05 §1.6): who they are to the meeting, and their [standing] derived from the events. */
data class AttendanceRow(
    val meetingId: UUID,
    val userId: UUID,
    val capacity: AttendanceCapacity,
    val forMemberId: UUID?,
    val forUserId: UUID?,
    val standing: Standing,
    val openedAt: Instant,
    val version: Long,
)

/** "Now: item 5" from [startedAt]. */
data class ItemProgressRow(
    val id: UUID,
    val meetingId: UUID,
    val itemId: UUID,
    val startedAt: Instant,
    val recordedBy: UUID?,
)

/** Whether a change is of the meeting's quorum (T4) or one item's (T5). */
enum class QuorumLevel { MEETING, ITEM }

/** A flip of the live quorum (design E05 §1.7). */
data class QuorumChangeRow(
    val id: UUID,
    val meetingId: UUID,
    val level: QuorumLevel,
    val itemId: UUID?,
    val kind: QuorumChangeKind,
    val needed: Int,
    val present: Int,
    val unmet: List<UnmetCondition>,
    val at: Instant,
    val causeEventId: UUID?,
    val recordedAt: Instant,
)

/**
 * The attendance register, its events, the item in progress and the quorum's changes (design E05 §1.6, §1.7, §6), inside
 * a tenant transaction. Register rows change by guarded updates on their version; the rest is append-only.
 */
@Repository
class AttendanceStore(
    private val tenantDsl: TenantDsl,
    private val clock: Clock,
) {
    fun rows(meetingId: UUID): List<AttendanceRow> =
        tenantDsl
            .current()
            .selectFrom(MEETING_ATTENDANCE)
            .where(MEETING_ATTENDANCE.MEETING_ID.eq(meetingId))
            .orderBy(MEETING_ATTENDANCE.USER_ID)
            .fetch { it.row() }

    fun row(
        meetingId: UUID,
        userId: UUID,
    ): AttendanceRow? =
        tenantDsl
            .current()
            .selectFrom(MEETING_ATTENDANCE)
            .where(MEETING_ATTENDANCE.MEETING_ID.eq(meetingId), MEETING_ATTENDANCE.USER_ID.eq(userId))
            .fetchOne { it.row() }

    /** Opens [row] on the register; false when the person already has one. */
    fun open(row: AttendanceRow): Boolean {
        val now = now()
        return tenantDsl
            .current()
            .insertInto(MEETING_ATTENDANCE)
            .set(MEETING_ATTENDANCE.TENANT_ID, tenantDsl.tenant().value)
            .set(MEETING_ATTENDANCE.MEETING_ID, row.meetingId)
            .set(MEETING_ATTENDANCE.USER_ID, row.userId)
            .set(MEETING_ATTENDANCE.CAPACITY, row.capacity.name)
            .set(MEETING_ATTENDANCE.FOR_MEMBER_ID, row.forMemberId)
            .set(MEETING_ATTENDANCE.FOR_USER_ID, row.forUserId)
            .set(MEETING_ATTENDANCE.STATE, AttendanceState.EXPECTED.name)
            .set(MEETING_ATTENDANCE.OPENED_AT, now)
            .set(MEETING_ATTENDANCE.UPDATED_AT, now)
            .onConflictDoNothing()
            .execute() == 1
    }

    /** Writes [standing] on the row of [userId] if it is still at [version]; false when it was not. */
    fun update(
        meetingId: UUID,
        userId: UUID,
        standing: Standing,
        version: Long,
    ): Boolean =
        tenantDsl
            .current()
            .update(MEETING_ATTENDANCE)
            .set(MEETING_ATTENDANCE.STATE, standing.state.name)
            .set(MEETING_ATTENDANCE.MODE, standing.mode?.name)
            .set(MEETING_ATTENDANCE.STEPPED_OUT_OF, standing.steppedOutOf)
            .set(MEETING_ATTENDANCE.FIRST_ARRIVED_AT, standing.firstArrivedAt?.let(::utc))
            .set(MEETING_ATTENDANCE.LAST_LEFT_AT, standing.lastLeftAt?.let(::utc))
            .set(MEETING_ATTENDANCE.ARRIVED_DURING_ITEM, standing.arrivedDuringItem)
            .set(MEETING_ATTENDANCE.LEFT_DURING_ITEM, standing.leftDuringItem)
            .set(MEETING_ATTENDANCE.UPDATED_AT, now())
            .set(MEETING_ATTENDANCE.VERSION, MEETING_ATTENDANCE.VERSION.plus(1))
            .where(
                MEETING_ATTENDANCE.MEETING_ID.eq(meetingId),
                MEETING_ATTENDANCE.USER_ID.eq(userId),
                MEETING_ATTENDANCE.VERSION.eq(version),
            ).execute() == 1

    /** Every event of [meetingId], voids included, in the order they happened. */
    fun events(meetingId: UUID): List<AttendanceEventRow> =
        tenantDsl
            .current()
            .selectFrom(MEETING_ATTENDANCE_EVENT)
            .where(MEETING_ATTENDANCE_EVENT.MEETING_ID.eq(meetingId))
            .orderBy(MEETING_ATTENDANCE_EVENT.AT, MEETING_ATTENDANCE_EVENT.RECORDED_AT, MEETING_ATTENDANCE_EVENT.ID)
            .fetch { it.row() }

    /** [userId]'s events in [meetingId], voids included. */
    fun eventsOf(
        meetingId: UUID,
        userId: UUID,
    ): List<AttendanceEventRow> =
        tenantDsl
            .current()
            .selectFrom(MEETING_ATTENDANCE_EVENT)
            .where(MEETING_ATTENDANCE_EVENT.MEETING_ID.eq(meetingId), MEETING_ATTENDANCE_EVENT.USER_ID.eq(userId))
            .orderBy(MEETING_ATTENDANCE_EVENT.AT, MEETING_ATTENDANCE_EVENT.RECORDED_AT, MEETING_ATTENDANCE_EVENT.ID)
            .fetch { it.row() }

    fun event(
        meetingId: UUID,
        id: UUID,
    ): AttendanceEventRow? =
        tenantDsl
            .current()
            .selectFrom(MEETING_ATTENDANCE_EVENT)
            .where(MEETING_ATTENDANCE_EVENT.MEETING_ID.eq(meetingId), MEETING_ATTENDANCE_EVENT.ID.eq(id))
            .fetchOne { it.row() }

    fun insert(event: AttendanceEventRow) {
        tenantDsl
            .current()
            .insertInto(MEETING_ATTENDANCE_EVENT)
            .set(MEETING_ATTENDANCE_EVENT.ID, event.id)
            .set(MEETING_ATTENDANCE_EVENT.TENANT_ID, tenantDsl.tenant().value)
            .set(MEETING_ATTENDANCE_EVENT.MEETING_ID, event.meetingId)
            .set(MEETING_ATTENDANCE_EVENT.USER_ID, event.userId)
            .set(MEETING_ATTENDANCE_EVENT.KIND, event.kind.name)
            .set(MEETING_ATTENDANCE_EVENT.MODE, event.mode?.name)
            .set(MEETING_ATTENDANCE_EVENT.AT, utc(event.at))
            .set(MEETING_ATTENDANCE_EVENT.ITEM_ID, event.itemId)
            .set(MEETING_ATTENDANCE_EVENT.VOIDS_EVENT_ID, event.voidsEventId)
            .set(MEETING_ATTENDANCE_EVENT.REASON, event.reason)
            .set(MEETING_ATTENDANCE_EVENT.NOTE, event.note)
            .set(MEETING_ATTENDANCE_EVENT.SOURCE, event.source.name)
            .set(MEETING_ATTENDANCE_EVENT.CORRECTION, event.correction)
            .set(MEETING_ATTENDANCE_EVENT.RECORDED_BY, event.recordedBy)
            .set(MEETING_ATTENDANCE_EVENT.RECORDED_AT, utc(event.recordedAt))
            .execute()
    }

    private fun now(): OffsetDateTime = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC)
}

/** An instant as the rows store it (UTC). */
internal fun utc(at: Instant): OffsetDateTime = at.atOffset(ZoneOffset.UTC)

private fun MeetingAttendanceRecord.row() =
    AttendanceRow(
        meetingId = meetingId,
        userId = userId,
        capacity = AttendanceCapacity.valueOf(capacity),
        forMemberId = forMemberId,
        forUserId = forUserId,
        standing =
            Standing(
                state = AttendanceState.valueOf(state),
                mode = mode?.let(AttendanceMode::valueOf),
                steppedOutOf = steppedOutOf,
                firstArrivedAt = firstArrivedAt?.toInstant(),
                lastLeftAt = lastLeftAt?.toInstant(),
                arrivedDuringItem = arrivedDuringItem,
                leftDuringItem = leftDuringItem,
            ),
        openedAt = openedAt.toInstant(),
        version = version ?: 0,
    )

private fun MeetingAttendanceEventRecord.row() =
    AttendanceEventRow(
        id = id,
        meetingId = meetingId,
        userId = userId,
        kind = AttendanceEventKind.valueOf(kind),
        mode = mode?.let(AttendanceMode::valueOf),
        at = at.toInstant(),
        itemId = itemId,
        voidsEventId = voidsEventId,
        reason = reason,
        note = note,
        source = AttendanceSource.valueOf(source),
        correction = correction,
        recordedBy = recordedBy,
        recordedAt = recordedAt.toInstant(),
    )
