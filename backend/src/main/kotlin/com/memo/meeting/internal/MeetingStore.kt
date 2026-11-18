package com.memo.meeting.internal

import com.memo.common.tenant.TenantDsl
import com.memo.jooq.tables.records.MeetingRecord
import com.memo.jooq.tables.references.MEETING
import com.memo.jooq.tables.references.MEETING_AUDIENCE
import com.memo.meeting.MeetingKind
import com.memo.meeting.MeetingMode
import com.memo.meeting.MeetingStatus
import org.jooq.Condition
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

/** A meeting (design E03 §1.1). Times are instants; [zone] is the IANA zone they are entered and shown in. */
data class MeetingRow(
    val id: UUID,
    val forumId: UUID,
    val typeVersion: Int,
    val kind: MeetingKind,
    val title: String?,
    val startsAt: Instant,
    val endsAt: Instant,
    val zone: ZoneId,
    val mode: MeetingMode,
    val location: String?,
    val joinUrl: String?,
    val joinDetails: String?,
    val classificationId: UUID,
    val number: Int?,
    val numberYear: Int?,
    val label: String?,
    val status: MeetingStatus,
    val statusReason: String?,
    val createdBy: UUID?,
    val createdAt: Instant,
    val version: Long,
) {
    /** The meeting's date: the day it starts, in its zone (the roster and the paper deadline are on this day). */
    val date: LocalDate get() = startsAt.atZone(zone).toLocalDate()
}

/** A page of meetings by start time, ascending, after the cursor position ([startsAt], [id]). */
data class MeetingWindow(
    val from: Instant?,
    val to: Instant?,
    val statuses: Set<MeetingStatus>,
    val after: Pair<Instant, UUID>?,
    val limit: Int,
)

/** Meetings (ADR-0060), inside a tenant transaction. Changes are guarded by status and version (AGENTS.md §4). */
@Repository
class MeetingStore(
    private val tenantDsl: TenantDsl,
    private val clock: Clock,
) {
    fun find(id: UUID): MeetingRow? =
        tenantDsl
            .current()
            .selectFrom(MEETING)
            .where(MEETING.ID.eq(id))
            .fetchOne { it.row() }

    fun findAll(ids: Collection<UUID>): List<MeetingRow> =
        if (ids.isEmpty()) {
            emptyList()
        } else {
            tenantDsl
                .current()
                .selectFrom(MEETING)
                .where(MEETING.ID.`in`(ids))
                .orderBy(MEETING.STARTS_AT, MEETING.ID)
                .fetch { it.row() }
        }

    fun ofForum(forumId: UUID): List<MeetingRow> =
        tenantDsl
            .current()
            .selectFrom(MEETING)
            .where(MEETING.FORUM_ID.eq(forumId))
            .orderBy(MEETING.STARTS_AT, MEETING.ID)
            .fetch { it.row() }

    /** The forum's meetings whose audience still follows the roster: not HELD, CANCELLED or later (design E03 §1.6). */
    fun openOfForum(forumId: UUID): List<MeetingRow> =
        tenantDsl
            .current()
            .selectFrom(MEETING)
            .where(MEETING.FORUM_ID.eq(forumId), MEETING.STATUS.`in`(OPEN.map { it.name }))
            .fetch { it.row() }

    fun insert(row: MeetingRow) {
        val now = now()
        tenantDsl
            .current()
            .insertInto(MEETING)
            .set(MEETING.ID, row.id)
            .set(MEETING.TENANT_ID, tenantDsl.tenant().value)
            .set(MEETING.FORUM_ID, row.forumId)
            .set(MEETING.FORUM_TYPE_VERSION, row.typeVersion)
            .set(MEETING.STATUS, row.status.name)
            .set(MEETING.CREATED_BY, row.createdBy)
            .set(MEETING.CREATED_AT, now)
            .set(MEETING.UPDATED_AT, now)
            .set(fields(row))
            .execute()
    }

    /** Writes [row]'s fields and status if the meeting is still in [from] at [version]; false when it was not. */
    fun update(
        row: MeetingRow,
        from: MeetingStatus,
        version: Long,
    ): Boolean =
        tenantDsl
            .current()
            .update(MEETING)
            .set(fields(row))
            .set(MEETING.STATUS, row.status.name)
            .set(MEETING.UPDATED_AT, now())
            .set(MEETING.VERSION, MEETING.VERSION.plus(1))
            .where(MEETING.ID.eq(row.id), MEETING.STATUS.eq(from.name), MEETING.VERSION.eq(version))
            .execute() == 1

    /**
     * The meeting locked for this transaction (`SELECT … FOR NO KEY UPDATE`, which rows referring to it do not wait on).
     * The lock order (design E03 §1.8): placed submissions, then the meeting, then its agenda.
     */
    fun lock(id: UUID): MeetingRow? =
        tenantDsl
            .current()
            .selectFrom(MEETING)
            .where(MEETING.ID.eq(id))
            .forNoKeyUpdate()
            .fetchOne { it.row() }

    /** Gives the meeting its [number] of [year] and its [label], once (when its agenda is first published). */
    fun number(
        id: UUID,
        number: Int,
        year: Int,
        label: String,
    ): Boolean =
        tenantDsl
            .current()
            .update(MEETING)
            .set(MEETING.NUMBER, number)
            .set(MEETING.NUMBER_YEAR, year)
            .set(MEETING.LABEL, label)
            .set(MEETING.UPDATED_AT, now())
            .set(MEETING.VERSION, MEETING.VERSION.plus(1))
            .where(MEETING.ID.eq(id), MEETING.NUMBER.isNull)
            .execute() == 1

    fun now(): OffsetDateTime = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC)

    private fun fields(row: MeetingRow): Map<org.jooq.Field<*>, Any?> =
        mapOf(
            MEETING.KIND to row.kind.name,
            MEETING.TITLE to row.title,
            MEETING.STARTS_AT to utc(row.startsAt),
            MEETING.ENDS_AT to utc(row.endsAt),
            MEETING.TIME_ZONE to row.zone.id,
            MEETING.MODE to row.mode.name,
            MEETING.LOCATION to row.location,
            MEETING.JOIN_URL to row.joinUrl,
            MEETING.JOIN_DETAILS to row.joinDetails,
            MEETING.CLASSIFICATION_ID to row.classificationId,
            MEETING.STATUS_REASON to row.statusReason,
        )

    companion object {
        val OPEN = setOf(MeetingStatus.DRAFT, MeetingStatus.SCHEDULED, MeetingStatus.IN_PROGRESS)

        fun utc(at: Instant): OffsetDateTime = at.atOffset(ZoneOffset.UTC)
    }
}

/** The meeting lists (design E03 §3, §8): a forum's by start time with a cursor, and "My meetings". */
@Repository
class MeetingListStore(
    private val tenantDsl: TenantDsl,
) {
    /** The forum's meetings in [window]; with [audienceOf], only those where that user has a current audience row. */
    fun page(
        forumId: UUID,
        window: MeetingWindow,
        audienceOf: UUID?,
    ): List<MeetingRow> {
        val inAudience =
            audienceOf?.let {
                DSL.exists(
                    DSL
                        .selectOne()
                        .from(MEETING_AUDIENCE)
                        .where(
                            MEETING_AUDIENCE.MEETING_ID.eq(MEETING.ID),
                            MEETING_AUDIENCE.USER_ID.eq(it),
                            MEETING_AUDIENCE.REMOVED_AT.isNull,
                        ),
                )
            } ?: DSL.noCondition()
        return tenantDsl
            .current()
            .selectFrom(MEETING)
            .where(MEETING.FORUM_ID.eq(forumId), inAudience, window.condition())
            .orderBy(MEETING.STARTS_AT, MEETING.ID)
            .limit(window.limit)
            .fetch { it.row() }
    }

    /** Meetings of the forums in [forumIds] (any status) ending on or after [from], oldest first, at most [limit]. */
    fun ofForumsFrom(
        forumIds: Collection<UUID>,
        from: Instant,
        limit: Int,
    ): List<MeetingRow> =
        if (forumIds.isEmpty()) {
            emptyList()
        } else {
            tenantDsl
                .current()
                .selectFrom(MEETING)
                .where(MEETING.FORUM_ID.`in`(forumIds), MEETING.ENDS_AT.ge(utc(from)))
                .orderBy(MEETING.STARTS_AT, MEETING.ID)
                .limit(limit)
                .fetch { it.row() }
        }

    /** Meetings past DRAFT where [userId] has a current audience row, ending on or after [from], oldest first. */
    fun ofAudienceFrom(
        userId: UUID,
        from: Instant,
        limit: Int,
    ): List<MeetingRow> =
        tenantDsl
            .current()
            .select(MEETING.asterisk())
            .from(MEETING_AUDIENCE)
            .join(MEETING)
            .on(MEETING.TENANT_ID.eq(MEETING_AUDIENCE.TENANT_ID), MEETING.ID.eq(MEETING_AUDIENCE.MEETING_ID))
            .where(
                MEETING_AUDIENCE.USER_ID.eq(userId),
                MEETING_AUDIENCE.REMOVED_AT.isNull,
                MEETING.STATUS.ne(MeetingStatus.DRAFT.name),
                MEETING.ENDS_AT.ge(utc(from)),
            ).orderBy(MEETING.STARTS_AT, MEETING.ID)
            .limit(limit)
            .fetch { it.into(MEETING).row() }
            .distinctBy { it.id }

    private fun MeetingWindow.condition(): Condition =
        DSL.and(
            listOfNotNull(
                from?.let { MEETING.STARTS_AT.ge(utc(it)) },
                to?.let { MEETING.STARTS_AT.lt(utc(it)) },
                MEETING.STATUS.`in`(statuses.map { it.name }),
                after?.let { (at, id) -> DSL.row(MEETING.STARTS_AT, MEETING.ID).gt(DSL.row(DSL.value(utc(at)), DSL.value(id))) },
            ),
        )
}

private fun utc(at: Instant): OffsetDateTime = MeetingStore.utc(at)

private fun MeetingRecord.row() =
    MeetingRow(
        id = id,
        forumId = forumId,
        typeVersion = forumTypeVersion,
        kind = MeetingKind.valueOf(kind),
        title = title,
        startsAt = startsAt.toInstant(),
        endsAt = endsAt.toInstant(),
        zone = ZoneId.of(timeZone),
        mode = MeetingMode.valueOf(mode),
        location = location,
        joinUrl = joinUrl,
        joinDetails = joinDetails,
        classificationId = classificationId,
        number = number,
        numberYear = numberYear,
        label = label,
        status = MeetingStatus.valueOf(status ?: MeetingStatus.DRAFT.name),
        statusReason = statusReason,
        createdBy = createdBy,
        createdAt = createdAt.toInstant(),
        version = version ?: 0,
    )
