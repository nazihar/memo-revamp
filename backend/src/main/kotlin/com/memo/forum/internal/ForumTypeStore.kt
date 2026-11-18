package com.memo.forum.internal

import com.memo.common.concurrency.ConcurrentModificationException
import com.memo.common.id.IdGenerator
import com.memo.common.review.ReviewState
import com.memo.common.tenant.TenantDsl
import com.memo.forum.ForumRetention
import com.memo.forum.MemberAttribute
import com.memo.forum.Outcome
import com.memo.forum.QuorumRule
import com.memo.forum.SubmissionPolicy
import com.memo.forum.TermPolicy
import com.memo.jooq.tables.records.ForumTypeRecord
import com.memo.jooq.tables.references.FORUM_TYPE
import org.jooq.exception.IntegrityConstraintViolationException
import org.springframework.dao.DuplicateKeyException
import org.springframework.stereotype.Repository
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/** What a forum-type version says (ADR-0056, ADR-0057). */
data class ForumTypeSettings(
    val name: String,
    val description: String? = null,
    /** Null: the tenant's lowest classification level. */
    val defaultClassificationId: UUID? = null,
    val retention: ForumRetention? = null,
    val memberAttributes: List<MemberAttribute> = emptyList(),
    val quorum: QuorumRule = QuorumRule(),
    val outcomes: List<Outcome> = emptyList(),
    val termPolicy: TermPolicy = TermPolicy(),
    val submissionPolicy: SubmissionPolicy = SubmissionPolicy(),
)

/** A forum type's identity. */
data class ForumType(
    val id: UUID,
    val code: String,
    val active: Boolean,
    val publishedVersion: Int?,
    val createdAt: Instant,
    val version: Long,
)

/** One version of a forum type. */
data class ForumTypeVersionRow(
    val id: UUID,
    val typeId: UUID,
    val number: Int,
    val state: ReviewState,
    /** Incremented by every change; the `ETag` clients send back in `If-Match`. */
    val revision: Long,
    val settings: ForumTypeSettings,
    val createdBy: UUID?,
    val createdAt: Instant,
    val submittedBy: UUID?,
    val submittedAt: Instant?,
    val reviewedBy: UUID?,
    val reviewedAt: Instant?,
    val reviewRemark: String?,
    val publishedAt: Instant?,
)

/**
 * Forum types: their identity, code, switch and published version number (ADR-0056, design E01 §1), inside a tenant
 * transaction. Their versions are in [ForumTypeVersionStore].
 */
@Repository
class ForumTypeStore(
    private val tenantDsl: TenantDsl,
    private val ids: IdGenerator,
    private val clock: Clock,
) {
    fun all(): List<ForumType> =
        tenantDsl
            .current()
            .selectFrom(FORUM_TYPE)
            .orderBy(FORUM_TYPE.CODE)
            .fetch { it.type() }

    fun find(id: UUID): ForumType? =
        tenantDsl
            .current()
            .selectFrom(FORUM_TYPE)
            .where(FORUM_TYPE.ID.eq(id))
            .fetchOne { it.type() }

    fun codeTaken(code: String): Boolean = tenantDsl.current().fetchExists(FORUM_TYPE, FORUM_TYPE.CODE.eq(code))

    /** A new type with no version yet; a code taken in a race is a conflict. */
    fun insertType(
        code: String,
        createdBy: UUID?,
    ): UUID {
        val id = ids.next()
        try {
            tenantDsl
                .current()
                .insertInto(FORUM_TYPE)
                .set(FORUM_TYPE.ID, id)
                .set(FORUM_TYPE.TENANT_ID, tenantDsl.tenant().value)
                .set(FORUM_TYPE.CODE, code)
                .set(FORUM_TYPE.CREATED_BY, createdBy)
                .set(FORUM_TYPE.CREATED_AT, now())
                .execute()
        } catch (_: DuplicateKeyException) {
            throw ConcurrentModificationException("forum type $code", 0)
        } catch (_: IntegrityConstraintViolationException) {
            throw ConcurrentModificationException("forum type $code", 0)
        }
        return id
    }

    fun setActive(
        id: UUID,
        active: Boolean,
    ): Boolean =
        tenantDsl
            .current()
            .update(FORUM_TYPE)
            .set(FORUM_TYPE.ACTIVE, active)
            .set(FORUM_TYPE.VERSION, FORUM_TYPE.VERSION.plus(1))
            .where(FORUM_TYPE.ID.eq(id))
            .execute() == 1

    fun setPublished(
        id: UUID,
        number: Int,
    ) {
        tenantDsl
            .current()
            .update(FORUM_TYPE)
            .set(FORUM_TYPE.PUBLISHED_VERSION, number)
            .set(FORUM_TYPE.VERSION, FORUM_TYPE.VERSION.plus(1))
            .where(FORUM_TYPE.ID.eq(id))
            .execute()
    }

    fun now(): OffsetDateTime = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC)

    fun tenant(): UUID = tenantDsl.tenant().value
}

private fun ForumTypeRecord.type() =
    ForumType(
        id = id,
        code = code,
        active = active != false,
        publishedVersion = publishedVersion,
        createdAt = createdAt.toInstant(),
        version = version ?: 0,
    )
