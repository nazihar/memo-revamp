package com.memo.memo.internal

import com.memo.common.concurrency.ConcurrentModificationException
import com.memo.common.id.IdGenerator
import com.memo.common.review.ReviewState
import com.memo.common.tenant.TenantDsl
import com.memo.jooq.tables.records.MemoTypeVersionRecord
import com.memo.jooq.tables.references.MEMO_TYPE_VERSION
import org.jooq.Field
import org.jooq.JSONB
import org.jooq.exception.IntegrityConstraintViolationException
import org.jooq.impl.DSL
import org.springframework.dao.DuplicateKeyException
import org.springframework.stereotype.Repository
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/** What a memo-type version says (ADR-0043); later E01 tasks add numbering and the lifecycle settings. */
data class MemoTypeSettings(
    val name: String,
    val code: String,
    /** Null: the tenant's lowest classification level. */
    val defaultClassificationId: UUID?,
    val definitionKey: String,
    val routePolicy: RoutePolicy,
    val allowedApprovers: AllowedApprovers = AllowedApprovers(),
    val doaCheck: Boolean = false,
    val stepUpOnApprove: Boolean = true,
    /** What the initiator fills in, in order (E01 T2). */
    val fields: List<MemoField> = emptyList(),
    /** Who may raise memos of the type (E01 T2); empty: everyone with `memo.create`. */
    val raiseScope: RaiseScope = RaiseScope(),
)

/** One version of a memo type. */
data class MemoTypeVersionRow(
    val id: UUID,
    val typeId: UUID,
    val number: Int,
    val state: ReviewState,
    /** Incremented by every change; the `ETag` clients send back in `If-Match`. */
    val revision: Long,
    val settings: MemoTypeSettings,
    /** Null: created by the system (the Phase 2 types, the built-in type, legacy direct edits). */
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
 * Memo-type versions (ADR-0043, design E01 §1), inside a tenant transaction. Changes are guarded by the state (and the
 * revision where the client sent one), so a lost race changes nothing and the caller reports a conflict.
 */
@Repository
class MemoTypeVersionStore(
    private val tenantDsl: TenantDsl,
    private val ids: IdGenerator,
    private val json: JsonMapper,
    private val clock: Clock,
) {
    /** The versions of [typeId], newest first. */
    fun versions(typeId: UUID): List<MemoTypeVersionRow> =
        tenantDsl
            .current()
            .selectFrom(MEMO_TYPE_VERSION)
            .where(MEMO_TYPE_VERSION.MEMO_TYPE_ID.eq(typeId))
            .orderBy(MEMO_TYPE_VERSION.NUMBER.desc())
            .fetch(json::row)

    fun version(
        typeId: UUID,
        number: Int,
    ): MemoTypeVersionRow? =
        tenantDsl
            .current()
            .selectFrom(MEMO_TYPE_VERSION)
            .where(MEMO_TYPE_VERSION.MEMO_TYPE_ID.eq(typeId), MEMO_TYPE_VERSION.NUMBER.eq(number))
            .fetchOne(json::row)

    fun byId(id: UUID): MemoTypeVersionRow? =
        tenantDsl
            .current()
            .selectFrom(MEMO_TYPE_VERSION)
            .where(MEMO_TYPE_VERSION.ID.eq(id))
            .fetchOne(json::row)

    fun published(typeId: UUID): MemoTypeVersionRow? =
        tenantDsl
            .current()
            .selectFrom(MEMO_TYPE_VERSION)
            .where(MEMO_TYPE_VERSION.MEMO_TYPE_ID.eq(typeId), MEMO_TYPE_VERSION.STATE.eq(ReviewState.PUBLISHED.name))
            .fetchOne(json::row)

    /** The draft or version in review of each type that has one, by type. */
    fun open(): Map<UUID, MemoTypeVersionRow> =
        tenantDsl
            .current()
            .selectFrom(MEMO_TYPE_VERSION)
            .where(MEMO_TYPE_VERSION.STATE.`in`(ReviewState.DRAFT.name, ReviewState.IN_REVIEW.name))
            .fetch(json::row)
            .associateBy { it.typeId }

    /**
     * Adds version `newest + 1` of [typeId] in [state] (DRAFT, or PUBLISHED for the system and legacy direct edits). A
     * second open or published version breaks a unique index: a conflict.
     */
    fun insert(
        typeId: UUID,
        state: ReviewState,
        settings: MemoTypeSettings,
        createdBy: UUID?,
    ): Int {
        val dsl = tenantDsl.current()
        val newest =
            dsl
                .select(DSL.max(MEMO_TYPE_VERSION.NUMBER))
                .from(MEMO_TYPE_VERSION)
                .where(MEMO_TYPE_VERSION.MEMO_TYPE_ID.eq(typeId))
                .fetchOne(0, Int::class.java) ?: 0
        val now = now()
        try {
            dsl
                .insertInto(MEMO_TYPE_VERSION)
                .set(MEMO_TYPE_VERSION.ID, ids.next())
                .set(MEMO_TYPE_VERSION.TENANT_ID, tenantDsl.tenant().value)
                .set(MEMO_TYPE_VERSION.MEMO_TYPE_ID, typeId)
                .set(MEMO_TYPE_VERSION.NUMBER, newest + 1)
                .set(MEMO_TYPE_VERSION.STATE, state.name)
                .set(json.settingsFields(settings))
                .set(MEMO_TYPE_VERSION.CREATED_BY, createdBy)
                .set(MEMO_TYPE_VERSION.CREATED_AT, now)
                .set(MEMO_TYPE_VERSION.PUBLISHED_AT, now.takeIf { state == ReviewState.PUBLISHED })
                .execute()
        } catch (_: IntegrityConstraintViolationException) {
            throw ConcurrentModificationException("memo type $typeId", newest.toLong())
        } catch (_: DuplicateKeyException) {
            throw ConcurrentModificationException("memo type $typeId", newest.toLong())
        }
        return newest + 1
    }

    /** Replaces a draft's settings if it is still a draft at [revision]. */
    fun replace(
        versionId: UUID,
        revision: Long,
        settings: MemoTypeSettings,
    ): Boolean = move(versionId, ReviewState.DRAFT, ReviewState.DRAFT, revision, json.settingsFields(settings))

    /** Retires the published version of [typeId], if any (before another one is published). */
    fun retirePublished(typeId: UUID) {
        tenantDsl
            .current()
            .update(MEMO_TYPE_VERSION)
            .set(MEMO_TYPE_VERSION.STATE, ReviewState.RETIRED.name)
            .set(MEMO_TYPE_VERSION.REVISION, MEMO_TYPE_VERSION.REVISION.plus(1))
            .where(MEMO_TYPE_VERSION.MEMO_TYPE_ID.eq(typeId), MEMO_TYPE_VERSION.STATE.eq(ReviewState.PUBLISHED.name))
            .execute()
    }

    /**
     * Moves a version from [from] to [to] and sets [fields], if it is still in [from] (and at [revision] when given);
     * false when it was not.
     */
    fun move(
        versionId: UUID,
        from: ReviewState,
        to: ReviewState,
        revision: Long? = null,
        fields: Map<out Field<*>, Any?> = emptyMap(),
    ): Boolean {
        val update =
            tenantDsl
                .current()
                .update(MEMO_TYPE_VERSION)
                .set(MEMO_TYPE_VERSION.STATE, to.name)
                .set(MEMO_TYPE_VERSION.REVISION, MEMO_TYPE_VERSION.REVISION.plus(1))
        @Suppress("UNCHECKED_CAST")
        fields.forEach { (field, value) -> update.set(field as Field<Any?>, value) }
        val guard =
            MEMO_TYPE_VERSION.ID
                .eq(versionId)
                .and(MEMO_TYPE_VERSION.STATE.eq(from.name))
                .and(revision?.let { MEMO_TYPE_VERSION.REVISION.eq(it) } ?: DSL.noCondition())
        return update.where(guard).execute() == 1
    }

    fun now(): OffsetDateTime = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC)
}

private fun JsonMapper.settingsFields(settings: MemoTypeSettings): Map<Field<*>, Any?> =
    mapOf(
        MEMO_TYPE_VERSION.NAME to settings.name,
        MEMO_TYPE_VERSION.CODE to settings.code,
        MEMO_TYPE_VERSION.DEFAULT_CLASSIFICATION_ID to settings.defaultClassificationId,
        MEMO_TYPE_VERSION.DEFINITION_KEY to settings.definitionKey,
        MEMO_TYPE_VERSION.ROUTE_POLICY to settings.routePolicy.name,
        MEMO_TYPE_VERSION.ALLOWED_APPROVERS to JSONB.valueOf(writeValueAsString(settings.allowedApprovers)),
        MEMO_TYPE_VERSION.DOA_CHECK to settings.doaCheck,
        MEMO_TYPE_VERSION.STEP_UP_ON_APPROVE to settings.stepUpOnApprove,
        MEMO_TYPE_VERSION.FIELDS to JSONB.valueOf(writeValueAsString(settings.fields)),
        MEMO_TYPE_VERSION.RAISE_SCOPE to JSONB.valueOf(writeValueAsString(settings.raiseScope)),
    )

private val FIELDS = object : TypeReference<List<MemoField>>() {}

private fun JsonMapper.row(v: MemoTypeVersionRecord): MemoTypeVersionRow =
    MemoTypeVersionRow(
        id = v.id,
        typeId = v.memoTypeId,
        number = v.number,
        state = ReviewState.valueOf(v.state),
        revision = v.revision ?: 0,
        settings =
            MemoTypeSettings(
                name = v.name,
                code = v.code,
                defaultClassificationId = v.defaultClassificationId,
                definitionKey = v.definitionKey,
                routePolicy = RoutePolicy.valueOf(v.routePolicy),
                allowedApprovers =
                    v.allowedApprovers?.let { readValue(it.data(), AllowedApprovers::class.java) } ?: AllowedApprovers(),
                doaCheck = v.doaCheck == true,
                stepUpOnApprove = v.stepUpOnApprove != false,
                fields = v.fields?.let { readValue(it.data(), FIELDS) }.orEmpty(),
                raiseScope = v.raiseScope?.let { readValue(it.data(), RaiseScope::class.java) } ?: RaiseScope(),
            ),
        createdBy = v.createdBy,
        createdAt = v.createdAt.toInstant(),
        submittedBy = v.submittedBy,
        submittedAt = v.submittedAt?.toInstant(),
        reviewedBy = v.reviewedBy,
        reviewedAt = v.reviewedAt?.toInstant(),
        reviewRemark = v.reviewRemark,
        publishedAt = v.publishedAt?.toInstant(),
    )
