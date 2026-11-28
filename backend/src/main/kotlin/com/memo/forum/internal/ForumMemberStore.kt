package com.memo.forum.internal

import com.memo.common.cache.CacheInvalidation
import com.memo.common.cache.TenantCaches
import com.memo.common.id.IdGenerator
import com.memo.common.tenant.TenantDsl
import com.memo.forum.ForumRole
import com.memo.jooq.tables.records.ForumMemberRecord
import com.memo.jooq.tables.references.FORUM_MEMBER
import org.jooq.JSONB
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/** Terms of office (ADR-0056, design E01 §1), inside a tenant transaction. Each forum's rows are cached and evicted on change. */
@Repository
class ForumMemberStore(
    private val tenantDsl: TenantDsl,
    private val ids: IdGenerator,
    private val json: JsonMapper,
    private val clock: Clock,
    private val invalidation: CacheInvalidation,
    caches: TenantCaches,
) {
    private val roster = caches.register<UUID, List<ForumMemberRow>>(ROSTER_CACHE)

    /** Every row of [forumId], oldest start first (cached). */
    fun members(forumId: UUID): List<ForumMemberRow> =
        roster.get(forumId) {
            tenantDsl
                .current()
                .selectFrom(FORUM_MEMBER)
                .where(FORUM_MEMBER.FORUM_ID.eq(forumId))
                .orderBy(FORUM_MEMBER.STARTS_ON, FORUM_MEMBER.CREATED_AT)
                .fetch { json.member(it) }
        }

    fun member(id: UUID): ForumMemberRow? =
        tenantDsl
            .current()
            .selectFrom(FORUM_MEMBER)
            .where(FORUM_MEMBER.ID.eq(id))
            .fetchOne { json.member(it) }

    /** Every row naming [userId] as a person, or one of [positions] as a seat, in any forum. */
    fun rowsOf(
        userId: UUID,
        positions: Set<UUID>,
    ): List<ForumMemberRow> =
        tenantDsl
            .current()
            .selectFrom(FORUM_MEMBER)
            .where(
                FORUM_MEMBER.USER_ID.eq(userId).or(
                    if (positions.isEmpty()) DSL.falseCondition() else FORUM_MEMBER.POSITION_ID.`in`(positions),
                ),
            ).fetch { json.member(it) }

    /** Every row of [positionId] as a seat, in any forum (a position's holder changed), through `forum_member_position_idx`. */
    fun seatsOf(positionId: UUID): List<ForumMemberRow> =
        tenantDsl
            .current()
            .selectFrom(FORUM_MEMBER)
            .where(FORUM_MEMBER.POSITION_ID.eq(positionId))
            .fetch { json.member(it) }

    /** Rows not ended whose planned term ends on [date], in any forum (term reminders). */
    fun insertMember(
        forumId: UUID,
        values: MemberValues,
        createdBy: UUID?,
    ): UUID {
        val id = ids.next()
        tenantDsl
            .current()
            .insertInto(FORUM_MEMBER)
            .set(FORUM_MEMBER.ID, id)
            .set(FORUM_MEMBER.TENANT_ID, tenantDsl.tenant().value)
            .set(FORUM_MEMBER.FORUM_ID, forumId)
            .set(FORUM_MEMBER.KIND, values.kind.name)
            .set(FORUM_MEMBER.USER_ID, values.userId)
            .set(FORUM_MEMBER.POSITION_ID, values.positionId)
            .set(FORUM_MEMBER.ROLE, values.role.name)
            .set(FORUM_MEMBER.VOTING, values.voting)
            .set(FORUM_MEMBER.ATTRIBUTES, JSONB.valueOf(json.writeValueAsString(values.attributes.sorted())))
            .set(FORUM_MEMBER.ALTERNATE_USER_ID, values.alternateUserId)
            .set(FORUM_MEMBER.STARTS_ON, values.startsOn)
            .set(FORUM_MEMBER.TERM_ENDS_ON, values.termEndsOn)
            .set(FORUM_MEMBER.TERM_NUMBER, values.termNumber)
            .set(FORUM_MEMBER.AUTHORITY, values.authority)
            .set(FORUM_MEMBER.PREVIOUS_ID, values.previousId)
            .set(FORUM_MEMBER.CREATED_BY, createdBy)
            .set(FORUM_MEMBER.CREATED_AT, now())
            .execute()
        changed(forumId)
        return id
    }

    /** Edits a row in place (voting, attributes, alternate, planned term end, authority) if it is still at [version]. */
    fun editMember(
        row: ForumMemberRow,
        version: Long,
    ): Boolean {
        val updated =
            tenantDsl
                .current()
                .update(FORUM_MEMBER)
                .set(FORUM_MEMBER.VOTING, row.voting)
                .set(FORUM_MEMBER.ATTRIBUTES, JSONB.valueOf(json.writeValueAsString(row.attributes.sorted())))
                .set(FORUM_MEMBER.ALTERNATE_USER_ID, row.alternateUserId)
                .set(FORUM_MEMBER.TERM_ENDS_ON, row.termEndsOn)
                .set(FORUM_MEMBER.AUTHORITY, row.authority)
                .set(FORUM_MEMBER.VERSION, FORUM_MEMBER.VERSION.plus(1))
                .where(FORUM_MEMBER.ID.eq(row.id), FORUM_MEMBER.VERSION.eq(version), FORUM_MEMBER.ENDED_ON.isNull)
                .execute() == 1
        if (updated) changed(row.forumId)
        return updated
    }

    /** Ends a row that has not ended yet; false when it already had. */
    fun endMember(
        row: ForumMemberRow,
        on: LocalDate,
        reason: EndReason,
        note: String?,
        version: Long?,
    ): Boolean {
        val updated =
            tenantDsl
                .current()
                .update(FORUM_MEMBER)
                .set(FORUM_MEMBER.ENDED_ON, on)
                .set(FORUM_MEMBER.END_REASON, reason.name)
                .set(FORUM_MEMBER.END_NOTE, note)
                .set(FORUM_MEMBER.VERSION, FORUM_MEMBER.VERSION.plus(1))
                .where(
                    FORUM_MEMBER.ID.eq(row.id),
                    FORUM_MEMBER.ENDED_ON.isNull,
                    version?.let { FORUM_MEMBER.VERSION.eq(it) } ?: DSL.noCondition(),
                ).execute() == 1
        if (updated) changed(row.forumId)
        return updated
    }

    fun tenant(): UUID = tenantDsl.tenant().value

    private fun changed(forumId: UUID) = invalidation.invalidate(ROSTER_CACHE, tenantDsl.tenant(), forumId.toString())

    private fun now(): OffsetDateTime = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC)

    companion object {
        const val ROSTER_CACHE = "forum.roster"
    }
}

private val KEYS = object : TypeReference<List<String>>() {}

private fun JsonMapper.member(r: ForumMemberRecord) =
    ForumMemberRow(
        id = r.id,
        forumId = r.forumId,
        kind = MemberKind.valueOf(r.kind),
        userId = r.userId,
        positionId = r.positionId,
        role = ForumRole.valueOf(r.role),
        voting = r.voting,
        attributes =
            r.attributes
                ?.let { readValue(it.data(), KEYS) }
                .orEmpty()
                .toSet(),
        alternateUserId = r.alternateUserId,
        startsOn = r.startsOn,
        termEndsOn = r.termEndsOn,
        endedOn = r.endedOn,
        endReason = r.endReason?.let { EndReason.valueOf(it) },
        endNote = r.endNote,
        termNumber = r.termNumber ?: 1,
        authority = r.authority,
        previousId = r.previousId,
        createdBy = r.createdBy,
        createdAt = r.createdAt.toInstant(),
        version = r.version ?: 0,
    )
