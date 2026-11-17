package com.memo.forum.internal

import com.memo.common.tenant.TenantDsl
import com.memo.forum.ForumRole
import com.memo.forum.ForumStatus
import com.memo.jooq.tables.records.ForumRecord
import com.memo.jooq.tables.references.FORUM
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

enum class MemberKind { PERSON, POSITION }

/** Why a term of office ended (design E01 §1). */
enum class EndReason { RESIGNED, TERM_COMPLETED, REMOVED, RENEWED, ROLE_CHANGED, WITHDRAWN, OTHER }

/** Where a membership row stands on a date (design E01 §1); derived, never stored. */
enum class MemberState { UPCOMING, IN_OFFICE, TERM_ENDED, ENDED }

data class ForumRow(
    val id: UUID,
    val key: String,
    val code: String,
    val name: String,
    val shortName: String,
    val typeId: UUID,
    val parentId: UUID?,
    val classificationId: UUID?,
    val status: ForumStatus,
    val dissolvedOn: LocalDate?,
    val dissolvedReason: String?,
    val createdBy: UUID?,
    val createdAt: Instant,
    val version: Long,
)

data class ForumMemberRow(
    val id: UUID,
    val forumId: UUID,
    val kind: MemberKind,
    val userId: UUID?,
    val positionId: UUID?,
    val role: ForumRole,
    val voting: Boolean,
    val attributes: Set<String>,
    val alternateUserId: UUID?,
    val startsOn: LocalDate,
    val termEndsOn: LocalDate?,
    val endedOn: LocalDate?,
    val endReason: EndReason?,
    val endNote: String?,
    val termNumber: Int,
    val authority: String?,
    val previousId: UUID?,
    val createdBy: UUID?,
    val createdAt: Instant,
    val version: Long,
) {
    /** In office on [date]: started, not ended before it, and not past an unrenewed term (ADR-0056). */
    fun inOffice(date: LocalDate): Boolean =
        !startsOn.isAfter(date) && (endedOn == null || !endedOn.isBefore(date)) && (termEndsOn == null || !termEndsOn.isBefore(date))

    fun state(date: LocalDate): MemberState =
        when {
            endedOn != null && endedOn.isBefore(date) -> MemberState.ENDED
            startsOn.isAfter(date) -> MemberState.UPCOMING
            termEndsOn != null && termEndsOn.isBefore(date) -> MemberState.TERM_ENDED
            else -> MemberState.IN_OFFICE
        }

    /** The last day the row can count: the earlier of its end and its planned term end; null when open-ended. */
    val lastDay: LocalDate? get() = listOfNotNull(endedOn, termEndsOn).minOrNull()

    /** Whether this row's time span overlaps [from]–[to] (null: open-ended). */
    fun overlaps(
        from: LocalDate,
        to: LocalDate?,
    ): Boolean {
        val last = lastDay
        return (last == null || !last.isBefore(from)) && (to == null || !startsOn.isAfter(to))
    }

    /** Whether the row has ended (withdrawn rows included). */
    val ended: Boolean get() = endedOn != null
}

/** New values of a membership row. */
data class MemberValues(
    val kind: MemberKind,
    val userId: UUID?,
    val positionId: UUID?,
    val role: ForumRole,
    val voting: Boolean,
    val attributes: Set<String>,
    val alternateUserId: UUID?,
    val startsOn: LocalDate,
    val termEndsOn: LocalDate?,
    val termNumber: Int,
    val authority: String?,
    val previousId: UUID?,
)

/** Forums (ADR-0056, design E01 §1), inside a tenant transaction. Their members are in [ForumMemberStore]. */
@Repository
class ForumStore(
    private val tenantDsl: TenantDsl,
    private val clock: Clock,
) {
    fun all(): List<ForumRow> =
        tenantDsl
            .current()
            .selectFrom(FORUM)
            .orderBy(FORUM.NAME)
            .fetch { it.row() }

    fun find(id: UUID): ForumRow? =
        tenantDsl
            .current()
            .selectFrom(FORUM)
            .where(FORUM.ID.eq(id))
            .fetchOne { it.row() }

    fun findByKey(key: String): ForumRow? =
        tenantDsl
            .current()
            .selectFrom(FORUM)
            .where(FORUM.KEY.eq(key))
            .fetchOne { it.row() }

    /** Locks the forum row for a membership change, so invariants are checked against a stable roster. */
    fun lock(id: UUID): ForumRow? =
        tenantDsl
            .current()
            .selectFrom(FORUM)
            .where(FORUM.ID.eq(id))
            .forUpdate()
            .fetchOne { it.row() }

    fun keyTaken(key: String): Boolean = tenantDsl.current().fetchExists(FORUM, FORUM.KEY.eq(key))

    fun codeTaken(
        code: String,
        except: UUID? = null,
    ): Boolean =
        tenantDsl.current().fetchExists(
            FORUM,
            FORUM.CODE.eq(code).and(except?.let { FORUM.ID.ne(it) } ?: DSL.noCondition()),
        )

    fun insert(forum: ForumRow): UUID {
        tenantDsl
            .current()
            .insertInto(FORUM)
            .set(FORUM.ID, forum.id)
            .set(FORUM.TENANT_ID, tenantDsl.tenant().value)
            .set(FORUM.KEY, forum.key)
            .set(FORUM.CODE, forum.code)
            .set(FORUM.NAME, forum.name)
            .set(FORUM.SHORT_NAME, forum.shortName)
            .set(FORUM.FORUM_TYPE_ID, forum.typeId)
            .set(FORUM.PARENT_FORUM_ID, forum.parentId)
            .set(FORUM.CLASSIFICATION_ID, forum.classificationId)
            .set(FORUM.STATUS, forum.status.name)
            .set(FORUM.CREATED_BY, forum.createdBy)
            .set(FORUM.CREATED_AT, now())
            .execute()
        return forum.id
    }

    /** Writes [forum]'s editable fields and status if it is still at [version]. */
    fun update(
        forum: ForumRow,
        version: Long,
    ): Boolean =
        tenantDsl
            .current()
            .update(FORUM)
            .set(FORUM.CODE, forum.code)
            .set(FORUM.NAME, forum.name)
            .set(FORUM.SHORT_NAME, forum.shortName)
            .set(FORUM.PARENT_FORUM_ID, forum.parentId)
            .set(FORUM.CLASSIFICATION_ID, forum.classificationId)
            .set(FORUM.STATUS, forum.status.name)
            .set(FORUM.DISSOLVED_ON, forum.dissolvedOn)
            .set(FORUM.DISSOLVED_REASON, forum.dissolvedReason)
            .set(FORUM.VERSION, FORUM.VERSION.plus(1))
            .where(FORUM.ID.eq(forum.id), FORUM.VERSION.eq(version))
            .execute() == 1

    fun now(): OffsetDateTime = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC)

    fun tenant(): UUID = tenantDsl.tenant().value
}

private fun ForumRecord.row() =
    ForumRow(
        id = id,
        key = key,
        code = code,
        name = name,
        shortName = shortName,
        typeId = forumTypeId,
        parentId = parentForumId,
        classificationId = classificationId,
        status = ForumStatus.valueOf(status ?: ForumStatus.ACTIVE.name),
        dissolvedOn = dissolvedOn,
        dissolvedReason = dissolvedReason,
        createdBy = createdBy,
        createdAt = createdAt.toInstant(),
        version = version ?: 0,
    )
