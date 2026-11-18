package com.memo.meeting.internal

import com.memo.common.tenant.TenantDsl
import com.memo.jooq.tables.references.MEETING_AGENDA
import com.memo.jooq.tables.references.MEETING_CONSENT_REQUEST
import com.memo.jooq.tables.references.MEETING_PAPER_OPEN
import com.memo.jooq.tables.references.MEETING_READER_STATE
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/** Where a person stands with a meeting's agenda: its latest revision, and the last one they looked at (null: never). */
data class ReaderState(
    val meetingId: UUID,
    val currentRevision: Int,
    val lastSeen: Int?,
)

/**
 * What the audience did with published agendas (design E03 §1.5, §1.9), inside a tenant transaction: the last revision
 * each person saw ("What changed since you last looked"), and the papers they opened (the "Opened" ticks).
 */
@Repository
class ReaderStateStore(
    private val tenantDsl: TenantDsl,
    private val clock: Clock,
) {
    /** Records that [userId] has seen revision [revision] of [meetingId]: the state only moves forward. */
    fun seen(
        meetingId: UUID,
        userId: UUID,
        revision: Int,
    ) {
        val s = MEETING_READER_STATE
        tenantDsl
            .current()
            .insertInto(s)
            .set(s.TENANT_ID, tenantDsl.tenant().value)
            .set(s.MEETING_ID, meetingId)
            .set(s.USER_ID, userId)
            .set(s.LAST_REVISION, revision)
            .set(s.SEEN_AT, now())
            .onConflict(s.TENANT_ID, s.MEETING_ID, s.USER_ID)
            .doUpdate()
            .set(s.LAST_REVISION, DSL.greatest(s.LAST_REVISION, DSL.excluded(s.LAST_REVISION)))
            .set(s.SEEN_AT, now())
            .execute()
    }

    /** [userId]'s states on the published agendas of [meetingIds] (meetings without a published agenda are absent). */
    fun states(
        userId: UUID,
        meetingIds: Collection<UUID>,
    ): Map<UUID, ReaderState> {
        if (meetingIds.isEmpty()) return emptyMap()
        val a = MEETING_AGENDA
        val s = MEETING_READER_STATE
        return tenantDsl
            .current()
            .select(a.MEETING_ID, a.CURRENT_REVISION, s.LAST_REVISION)
            .from(a)
            .leftJoin(s)
            .on(s.TENANT_ID.eq(a.TENANT_ID), s.MEETING_ID.eq(a.MEETING_ID), s.USER_ID.eq(userId))
            .where(a.MEETING_ID.`in`(meetingIds), a.CURRENT_REVISION.gt(0))
            .fetch { ReaderState(checkNotNull(it.value1()), checkNotNull(it.value2()), it.value3()) }
            .associateBy { it.meetingId }
    }

    /** Records [userId]'s first open of [paperId]; later opens change nothing. */
    fun opened(
        paperId: UUID,
        userId: UUID,
    ) {
        tenantDsl
            .current()
            .insertInto(MEETING_PAPER_OPEN)
            .set(MEETING_PAPER_OPEN.TENANT_ID, tenantDsl.tenant().value)
            .set(MEETING_PAPER_OPEN.PAPER_ID, paperId)
            .set(MEETING_PAPER_OPEN.USER_ID, userId)
            .set(MEETING_PAPER_OPEN.FIRST_OPENED_AT, now())
            .onConflictDoNothing()
            .execute()
    }

    /** The papers among [paperIds] that [userId] has opened. */
    fun openedOf(
        userId: UUID,
        paperIds: Collection<UUID>,
    ): Set<UUID> =
        if (paperIds.isEmpty()) {
            emptySet()
        } else {
            tenantDsl
                .current()
                .select(MEETING_PAPER_OPEN.PAPER_ID)
                .from(MEETING_PAPER_OPEN)
                .where(MEETING_PAPER_OPEN.USER_ID.eq(userId), MEETING_PAPER_OPEN.PAPER_ID.`in`(paperIds))
                .fetchSet(MEETING_PAPER_OPEN.PAPER_ID)
                .filterNotNull()
                .toSet()
        }

    private fun now(): OffsetDateTime = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC)
}

/** An open request to lift a consent item for discussion. */
data class ConsentRequestRow(
    val id: UUID,
    val meetingId: UUID,
    val itemId: UUID,
    val userId: UUID,
    val revision: Int,
    val requestedAt: Instant,
)

/**
 * Consent requests (design E03 §1.5), inside a tenant transaction: append-only, one open request per person and item
 * (the partial unique index decides under concurrency); a withdrawal sets its time once, the only change the app role
 * may make (column grant and trigger).
 */
@Repository
class ConsentRequestStore(
    private val tenantDsl: TenantDsl,
    private val clock: Clock,
) {
    /** Records [row]; false when the person already has an open request on the item. */
    fun insert(row: ConsentRequestRow): Boolean {
        val c = MEETING_CONSENT_REQUEST
        return tenantDsl
            .current()
            .insertInto(c)
            .set(c.ID, row.id)
            .set(c.TENANT_ID, tenantDsl.tenant().value)
            .set(c.MEETING_ID, row.meetingId)
            .set(c.ITEM_ID, row.itemId)
            .set(c.USER_ID, row.userId)
            .set(c.REVISION, row.revision)
            .set(c.REQUESTED_AT, row.requestedAt.atOffset(ZoneOffset.UTC))
            .onConflictDoNothing()
            .execute() == 1
    }

    /** Withdraws [userId]'s open request on [itemId]; the request withdrawn, or null when there was none. */
    fun withdraw(
        itemId: UUID,
        userId: UUID,
    ): UUID? {
        val c = MEETING_CONSENT_REQUEST
        return tenantDsl
            .current()
            .update(c)
            .set(c.WITHDRAWN_AT, OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC))
            .where(c.ITEM_ID.eq(itemId), c.USER_ID.eq(userId), c.WITHDRAWN_AT.isNull)
            .returning(c.ID)
            .fetchOne(c.ID)
    }

    /** The open requests of [meetingId], the oldest first. */
    fun open(meetingId: UUID): List<ConsentRequestRow> {
        val c = MEETING_CONSENT_REQUEST
        return tenantDsl
            .current()
            .selectFrom(c)
            .where(c.MEETING_ID.eq(meetingId), c.WITHDRAWN_AT.isNull)
            .orderBy(c.REQUESTED_AT, c.ID)
            .fetch { ConsentRequestRow(it.id, it.meetingId, it.itemId, it.userId, it.revision, it.requestedAt.toInstant()) }
    }

    fun now(): Instant = clock.instant()
}
