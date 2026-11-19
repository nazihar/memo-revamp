package com.memo.meeting.internal

import com.memo.common.tenant.TenantDsl
import com.memo.jooq.tables.references.MEETING_AUDIENCE
import com.memo.jooq.tables.references.MEETING_GUEST
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/** Why someone is in a meeting's audience (design E03 §1.6). */
enum class AudienceSource { MEMBER, GUEST, ATTENDEE }

/** One person in a meeting's audience; [until] is a member's last day in office (null: open). */
data class AudienceRow(
    val meetingId: UUID,
    val userId: UUID,
    val source: AudienceSource,
    val membersOnly: Boolean,
    val until: LocalDate?,
)

/** A person attending the whole meeting who is not on the roster; removal records its time. */
data class GuestRow(
    val id: UUID,
    val meetingId: UUID,
    val userId: UUID,
    val note: String?,
    val addedBy: UUID?,
    val addedAt: Instant,
)

/**
 * Meeting audiences and guests (design E03 §1.6), inside a tenant transaction. The audience is materialised: [replace]
 * writes the rows of one source and marks the ones that no longer apply removed; nothing is deleted (design E03 §6).
 */
@Repository
class AudienceStore(
    private val tenantDsl: TenantDsl,
    private val clock: Clock,
) {
    /** The current audience rows of [meetingId]. */
    fun audience(meetingId: UUID): List<AudienceRow> =
        tenantDsl
            .current()
            .selectFrom(MEETING_AUDIENCE)
            .where(MEETING_AUDIENCE.MEETING_ID.eq(meetingId), MEETING_AUDIENCE.REMOVED_AT.isNull)
            .orderBy(MEETING_AUDIENCE.USER_ID, MEETING_AUDIENCE.SOURCE)
            .fetch { AudienceRow(it.meetingId, it.userId, AudienceSource.valueOf(it.source), it.membersOnly, it.until) }

    /** [userId]'s current audience rows in [meetingId]. */
    fun rowsOf(
        meetingId: UUID,
        userId: UUID,
    ): List<AudienceRow> = rowsOf(listOf(meetingId), userId)[meetingId].orEmpty()

    /** [userId]'s current audience rows in each of [meetingIds], in one query (the lists, design E03 §8). */
    fun rowsOf(
        meetingIds: Collection<UUID>,
        userId: UUID,
    ): Map<UUID, List<AudienceRow>> =
        if (meetingIds.isEmpty()) {
            emptyMap()
        } else {
            tenantDsl
                .current()
                .selectFrom(MEETING_AUDIENCE)
                .where(
                    MEETING_AUDIENCE.USER_ID.eq(userId),
                    MEETING_AUDIENCE.MEETING_ID.`in`(meetingIds),
                    MEETING_AUDIENCE.REMOVED_AT.isNull,
                ).orderBy(MEETING_AUDIENCE.MEETING_ID, MEETING_AUDIENCE.SOURCE)
                .fetch { AudienceRow(it.meetingId, it.userId, AudienceSource.valueOf(it.source), it.membersOnly, it.until) }
                .groupBy { it.meetingId }
        }

    /** The meetings [userId] has a current audience row in. */
    fun meetingsOf(userId: UUID): Set<UUID> =
        tenantDsl
            .current()
            .selectDistinct(MEETING_AUDIENCE.MEETING_ID)
            .from(MEETING_AUDIENCE)
            .where(MEETING_AUDIENCE.USER_ID.eq(userId), MEETING_AUDIENCE.REMOVED_AT.isNull)
            .fetchSet(MEETING_AUDIENCE.MEETING_ID)
            .filterNotNull()
            .toSet()

    /** Makes [rows] the current rows of [source] in [meetingId]: upserted, and every other row of the source removed. */
    fun replace(
        meetingId: UUID,
        source: AudienceSource,
        rows: List<AudienceRow>,
    ) {
        val dsl = tenantDsl.current()
        val tenant = tenantDsl.tenant().value
        rows.forEach { row ->
            dsl
                .insertInto(MEETING_AUDIENCE)
                .set(MEETING_AUDIENCE.TENANT_ID, tenant)
                .set(MEETING_AUDIENCE.MEETING_ID, meetingId)
                .set(MEETING_AUDIENCE.USER_ID, row.userId)
                .set(MEETING_AUDIENCE.SOURCE, source.name)
                .set(MEETING_AUDIENCE.MEMBERS_ONLY, row.membersOnly)
                .set(MEETING_AUDIENCE.UNTIL, row.until)
                .onConflict(MEETING_AUDIENCE.TENANT_ID, MEETING_AUDIENCE.MEETING_ID, MEETING_AUDIENCE.USER_ID, MEETING_AUDIENCE.SOURCE)
                .doUpdate()
                .set(MEETING_AUDIENCE.MEMBERS_ONLY, row.membersOnly)
                .set(MEETING_AUDIENCE.UNTIL, row.until)
                .setNull(MEETING_AUDIENCE.REMOVED_AT)
                .execute()
        }
        dsl
            .update(MEETING_AUDIENCE)
            .set(MEETING_AUDIENCE.REMOVED_AT, now())
            .where(
                MEETING_AUDIENCE.MEETING_ID.eq(meetingId),
                MEETING_AUDIENCE.SOURCE.eq(source.name),
                MEETING_AUDIENCE.REMOVED_AT.isNull,
                if (rows.isEmpty()) DSL.noCondition() else MEETING_AUDIENCE.USER_ID.notIn(rows.map { it.userId }),
            ).execute()
    }

    /** The current guests of [meetingId], in the order they were added. */
    fun guests(meetingId: UUID): List<GuestRow> =
        tenantDsl
            .current()
            .selectFrom(MEETING_GUEST)
            .where(MEETING_GUEST.MEETING_ID.eq(meetingId), MEETING_GUEST.REMOVED_AT.isNull)
            .orderBy(MEETING_GUEST.ADDED_AT, MEETING_GUEST.ID)
            .fetch { GuestRow(it.id, it.meetingId, it.userId, it.note, it.addedBy, it.addedAt.toInstant()) }

    /** Adds [userId] as a guest; false when they already are one (the unique index decides under concurrency). */
    fun addGuest(
        id: UUID,
        meetingId: UUID,
        userId: UUID,
        note: String?,
        by: UUID?,
    ): Boolean =
        tenantDsl
            .current()
            .insertInto(MEETING_GUEST)
            .set(MEETING_GUEST.ID, id)
            .set(MEETING_GUEST.TENANT_ID, tenantDsl.tenant().value)
            .set(MEETING_GUEST.MEETING_ID, meetingId)
            .set(MEETING_GUEST.USER_ID, userId)
            .set(MEETING_GUEST.NOTE, note)
            .set(MEETING_GUEST.ADDED_BY, by)
            .set(MEETING_GUEST.ADDED_AT, now())
            .onConflictDoNothing()
            .execute() == 1

    /** Records [userId]'s removal as a guest; false when they were not one. */
    fun removeGuest(
        meetingId: UUID,
        userId: UUID,
        by: UUID?,
    ): Boolean =
        tenantDsl
            .current()
            .update(MEETING_GUEST)
            .set(MEETING_GUEST.REMOVED_AT, now())
            .set(MEETING_GUEST.REMOVED_BY, by)
            .where(MEETING_GUEST.MEETING_ID.eq(meetingId), MEETING_GUEST.USER_ID.eq(userId), MEETING_GUEST.REMOVED_AT.isNull)
            .execute() == 1

    private fun now(): OffsetDateTime = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC)
}
