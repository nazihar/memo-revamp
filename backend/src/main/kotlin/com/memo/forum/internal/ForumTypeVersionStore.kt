package com.memo.forum.internal

import com.memo.common.concurrency.ConcurrentModificationException
import com.memo.common.id.IdGenerator
import com.memo.common.review.ReviewState
import com.memo.common.tenant.TenantDsl
import com.memo.forum.ActionsPolicy
import com.memo.forum.AgendaPolicy
import com.memo.forum.ForumRetention
import com.memo.forum.MeetingPolicy
import com.memo.forum.MemberAttribute
import com.memo.forum.MinutesPolicy
import com.memo.forum.Outcome
import com.memo.forum.QuorumRule
import com.memo.forum.SubmissionPolicy
import com.memo.forum.TermPolicy
import com.memo.jooq.tables.records.ForumTypeVersionRecord
import com.memo.jooq.tables.references.FORUM_TYPE_VERSION
import org.jooq.Field
import org.jooq.JSONB
import org.jooq.exception.IntegrityConstraintViolationException
import org.jooq.impl.DSL
import org.springframework.dao.DuplicateKeyException
import org.springframework.stereotype.Repository
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * Forum-type versions (ADR-0056, design E01 §1), inside a tenant transaction. Changes are guarded by state (and revision
 * when the client sent one), so a lost race changes nothing and the caller reports a conflict.
 */
@Repository
class ForumTypeVersionStore(
    private val tenantDsl: TenantDsl,
    private val ids: IdGenerator,
    private val json: JsonMapper,
    private val clock: Clock,
) {
    /** The versions of [typeId], newest first. */
    fun versions(typeId: UUID): List<ForumTypeVersionRow> =
        tenantDsl
            .current()
            .selectFrom(FORUM_TYPE_VERSION)
            .where(FORUM_TYPE_VERSION.FORUM_TYPE_ID.eq(typeId))
            .orderBy(FORUM_TYPE_VERSION.NUMBER.desc())
            .fetch { json.row(it) }

    fun version(
        typeId: UUID,
        number: Int,
    ): ForumTypeVersionRow? =
        tenantDsl
            .current()
            .selectFrom(FORUM_TYPE_VERSION)
            .where(FORUM_TYPE_VERSION.FORUM_TYPE_ID.eq(typeId), FORUM_TYPE_VERSION.NUMBER.eq(number))
            .fetchOne { json.row(it) }

    fun byId(id: UUID): ForumTypeVersionRow? =
        tenantDsl
            .current()
            .selectFrom(FORUM_TYPE_VERSION)
            .where(FORUM_TYPE_VERSION.ID.eq(id))
            .fetchOne { json.row(it) }

    /** [id] locked for the transaction (`SELECT … FOR UPDATE`), or null when there is no such version. */
    fun lock(id: UUID): ForumTypeVersionRow? =
        tenantDsl
            .current()
            .selectFrom(FORUM_TYPE_VERSION)
            .where(FORUM_TYPE_VERSION.ID.eq(id))
            .forUpdate()
            .fetchOne { json.row(it) }

    fun published(typeId: UUID): ForumTypeVersionRow? =
        tenantDsl
            .current()
            .selectFrom(FORUM_TYPE_VERSION)
            .where(FORUM_TYPE_VERSION.FORUM_TYPE_ID.eq(typeId), FORUM_TYPE_VERSION.STATE.eq(ReviewState.PUBLISHED.name))
            .fetchOne { json.row(it) }

    /** The published and open versions of every type, for the list. */
    fun current(): List<ForumTypeVersionRow> =
        tenantDsl
            .current()
            .selectFrom(FORUM_TYPE_VERSION)
            .where(FORUM_TYPE_VERSION.STATE.`in`(ReviewState.PUBLISHED.name, ReviewState.DRAFT.name, ReviewState.IN_REVIEW.name))
            .fetch { json.row(it) }

    /** Adds version `newest + 1` of [typeId] in [state]; a second open or published version is a conflict. */
    fun insertVersion(
        typeId: UUID,
        state: ReviewState,
        settings: ForumTypeSettings,
        createdBy: UUID?,
    ): Int {
        val dsl = tenantDsl.current()
        val newest =
            dsl
                .select(DSL.max(FORUM_TYPE_VERSION.NUMBER))
                .from(FORUM_TYPE_VERSION)
                .where(FORUM_TYPE_VERSION.FORUM_TYPE_ID.eq(typeId))
                .fetchOne(0, Int::class.java) ?: 0
        val now = now()
        try {
            dsl
                .insertInto(FORUM_TYPE_VERSION)
                .set(FORUM_TYPE_VERSION.ID, ids.next())
                .set(FORUM_TYPE_VERSION.TENANT_ID, tenantDsl.tenant().value)
                .set(FORUM_TYPE_VERSION.FORUM_TYPE_ID, typeId)
                .set(FORUM_TYPE_VERSION.NUMBER, newest + 1)
                .set(FORUM_TYPE_VERSION.STATE, state.name)
                .set(json.settingsFields(settings))
                .set(FORUM_TYPE_VERSION.CREATED_BY, createdBy)
                .set(FORUM_TYPE_VERSION.CREATED_AT, now)
                .set(FORUM_TYPE_VERSION.PUBLISHED_AT, now.takeIf { state == ReviewState.PUBLISHED })
                .execute()
        } catch (_: IntegrityConstraintViolationException) {
            throw ConcurrentModificationException("forum type $typeId", newest.toLong())
        } catch (_: DuplicateKeyException) {
            throw ConcurrentModificationException("forum type $typeId", newest.toLong())
        }
        return newest + 1
    }

    fun replace(
        versionId: UUID,
        revision: Long,
        settings: ForumTypeSettings,
    ): Boolean = move(versionId, ReviewState.DRAFT, ReviewState.DRAFT, revision, json.settingsFields(settings))

    fun retirePublished(typeId: UUID) {
        tenantDsl
            .current()
            .update(FORUM_TYPE_VERSION)
            .set(FORUM_TYPE_VERSION.STATE, ReviewState.RETIRED.name)
            .set(FORUM_TYPE_VERSION.REVISION, FORUM_TYPE_VERSION.REVISION.plus(1))
            .where(FORUM_TYPE_VERSION.FORUM_TYPE_ID.eq(typeId), FORUM_TYPE_VERSION.STATE.eq(ReviewState.PUBLISHED.name))
            .execute()
    }

    /** Moves a version from [from] to [to] and sets [fields], if it is still in [from] (and at [revision] when given). */
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
                .update(FORUM_TYPE_VERSION)
                .set(FORUM_TYPE_VERSION.STATE, to.name)
                .set(FORUM_TYPE_VERSION.REVISION, FORUM_TYPE_VERSION.REVISION.plus(1))
        @Suppress("UNCHECKED_CAST")
        fields.forEach { (field, value) -> update.set(field as Field<Any?>, value) }
        val guard =
            FORUM_TYPE_VERSION.ID
                .eq(versionId)
                .and(FORUM_TYPE_VERSION.STATE.eq(from.name))
                .and(revision?.let { FORUM_TYPE_VERSION.REVISION.eq(it) } ?: DSL.noCondition())
        return update.where(guard).execute() == 1
    }

    private fun now(): OffsetDateTime = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC)
}

private fun JsonMapper.jsonb(value: Any): JSONB = JSONB.valueOf(writeValueAsString(value))

private fun JsonMapper.settingsFields(settings: ForumTypeSettings): Map<Field<*>, Any?> =
    mapOf(
        FORUM_TYPE_VERSION.NAME to settings.name,
        FORUM_TYPE_VERSION.DESCRIPTION to settings.description,
        FORUM_TYPE_VERSION.DEFAULT_CLASSIFICATION_ID to settings.defaultClassificationId,
        FORUM_TYPE_VERSION.RETENTION to settings.retention?.let { jsonb(it) },
        FORUM_TYPE_VERSION.MEMBER_ATTRIBUTES to jsonb(settings.memberAttributes),
        FORUM_TYPE_VERSION.QUORUM to jsonb(settings.quorum),
        FORUM_TYPE_VERSION.OUTCOMES to jsonb(settings.outcomes),
        FORUM_TYPE_VERSION.TERM_POLICY to jsonb(settings.termPolicy),
        FORUM_TYPE_VERSION.SUBMISSION_POLICY to jsonb(settings.submissionPolicy),
        FORUM_TYPE_VERSION.AGENDA to jsonb(settings.agenda),
        FORUM_TYPE_VERSION.MEETINGS to jsonb(settings.meetings),
        FORUM_TYPE_VERSION.MINUTES to jsonb(settings.minutes),
        FORUM_TYPE_VERSION.ACTIONS to jsonb(settings.actions),
    )

private val ATTRIBUTES = object : TypeReference<List<MemberAttribute>>() {}
private val OUTCOMES = object : TypeReference<List<Outcome>>() {}

/** A policy column as stored, or [default] for a version stored before the column existed (null reads as the defaults). */
private inline fun <reified T : Any> JsonMapper.policy(
    value: JSONB?,
    default: () -> T,
): T = value?.let { readValue(it.data(), T::class.java) } ?: default()

private fun JsonMapper.row(v: ForumTypeVersionRecord): ForumTypeVersionRow =
    ForumTypeVersionRow(
        id = v.id,
        typeId = v.forumTypeId,
        number = v.number,
        state = ReviewState.valueOf(v.state),
        revision = v.revision ?: 0,
        settings =
            ForumTypeSettings(
                name = v.name,
                description = v.description,
                defaultClassificationId = v.defaultClassificationId,
                retention = v.retention?.let { readValue(it.data(), ForumRetention::class.java) },
                memberAttributes = v.memberAttributes?.let { readValue(it.data(), ATTRIBUTES) }.orEmpty(),
                quorum = readValue(v.quorum.data(), QuorumRule::class.java),
                outcomes = readValue(v.outcomes.data(), OUTCOMES),
                termPolicy = policy(v.termPolicy) { TermPolicy() },
                submissionPolicy = policy(v.submissionPolicy) { SubmissionPolicy() },
                agenda = policy(v.agenda) { AgendaPolicy() },
                meetings = policy(v.meetings) { MeetingPolicy() },
                minutes = policy(v.minutes) { MinutesPolicy() },
                actions = policy(v.actions) { ActionsPolicy() },
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
