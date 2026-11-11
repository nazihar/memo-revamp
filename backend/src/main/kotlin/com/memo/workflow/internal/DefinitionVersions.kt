package com.memo.workflow.internal

import com.memo.common.concurrency.ConcurrentModificationException
import com.memo.common.id.IdGenerator
import com.memo.common.tenant.TenantDsl
import com.memo.jooq.tables.records.WorkflowDefinitionVersionRecord
import com.memo.jooq.tables.references.WORKFLOW_DEFINITION
import com.memo.jooq.tables.references.WORKFLOW_DEFINITION_VERSION
import com.memo.workflow.definition.Definition
import org.jooq.Field
import org.jooq.JSONB
import org.jooq.exception.IntegrityConstraintViolationException
import org.jooq.impl.DSL
import org.springframework.dao.DuplicateKeyException
import org.springframework.stereotype.Repository
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/** The lifecycle of a definition version (ADR-0038). */
enum class VersionState { DRAFT, IN_REVIEW, PUBLISHED, RETIRED }

data class DefinitionRow(
    val id: UUID,
    val key: String,
    val name: String,
    val description: String?,
    val fragment: Boolean,
    val builtIn: Boolean,
)

data class VersionRow(
    val id: UUID,
    val definitionId: UUID,
    val number: Int,
    val state: VersionState,
    val schemaVersion: Int,
    /** Incremented by every change; the `ETag` clients send back in `If-Match`. */
    val revision: Long,
    /** The body as authored (JSON). */
    val body: String,
    val submittedBy: UUID?,
    val submittedAt: Instant?,
    val reviewedBy: UUID?,
    val reviewedAt: Instant?,
    val reviewRemark: String?,
    val publishedAt: Instant?,
)

/**
 * Definitions and their versions (ADR-0038), inside a tenant transaction. State changes are guarded by the state (and
 * the revision where the client sent one), so a lost race changes nothing and the caller reports a conflict.
 */
@Repository
class DefinitionVersions(
    private val tenantDsl: TenantDsl,
    private val ids: IdGenerator,
    private val clock: Clock,
) {
    fun definitions(): List<DefinitionRow> =
        tenantDsl
            .current()
            .selectFrom(WORKFLOW_DEFINITION)
            .orderBy(WORKFLOW_DEFINITION.NAME, WORKFLOW_DEFINITION.KEY)
            .fetch { DefinitionRow(it.id, it.key, it.name, it.description, it.fragment == true, it.builtIn == true) }

    fun definition(key: String): DefinitionRow? =
        tenantDsl
            .current()
            .selectFrom(WORKFLOW_DEFINITION)
            .where(WORKFLOW_DEFINITION.KEY.eq(key))
            .fetchOne { DefinitionRow(it.id, it.key, it.name, it.description, it.fragment == true, it.builtIn == true) }

    /** Every version of [definitionIds], newest first. */
    fun versions(definitionIds: Collection<UUID>): List<VersionRow> =
        tenantDsl
            .current()
            .selectFrom(WORKFLOW_DEFINITION_VERSION)
            .where(WORKFLOW_DEFINITION_VERSION.DEFINITION_ID.`in`(definitionIds))
            .orderBy(WORKFLOW_DEFINITION_VERSION.NUMBER.desc())
            .fetch(::row)

    fun create(
        key: String,
        name: String,
        description: String?,
        fragment: Boolean,
    ): UUID {
        val id = ids.next()
        tenantDsl
            .current()
            .insertInto(WORKFLOW_DEFINITION)
            .set(WORKFLOW_DEFINITION.ID, id)
            .set(WORKFLOW_DEFINITION.TENANT_ID, tenantDsl.tenant().value)
            .set(WORKFLOW_DEFINITION.KEY, key)
            .set(WORKFLOW_DEFINITION.NAME, name)
            .set(WORKFLOW_DEFINITION.DESCRIPTION, description)
            .set(WORKFLOW_DEFINITION.FRAGMENT, fragment)
            .set(WORKFLOW_DEFINITION.BUILT_IN, false)
            .set(WORKFLOW_DEFINITION.CREATED_AT, now())
            .execute()
        return id
    }

    /**
     * A new draft after the newest version, with its id. A definition has one draft or version in review at a time (a
     * unique index): losing that race to another administrator is a conflict.
     */
    fun draft(
        definitionId: UUID,
        body: String,
    ): Pair<UUID, Int> {
        val dsl = tenantDsl.current()
        val newest =
            dsl
                .select(DSL.max(WORKFLOW_DEFINITION_VERSION.NUMBER))
                .from(WORKFLOW_DEFINITION_VERSION)
                .where(WORKFLOW_DEFINITION_VERSION.DEFINITION_ID.eq(definitionId))
                .fetchOne(0, Int::class.java) ?: 0
        val id = ids.next()
        try {
            dsl
                .insertInto(WORKFLOW_DEFINITION_VERSION)
                .set(WORKFLOW_DEFINITION_VERSION.ID, id)
                .set(WORKFLOW_DEFINITION_VERSION.TENANT_ID, tenantDsl.tenant().value)
                .set(WORKFLOW_DEFINITION_VERSION.DEFINITION_ID, definitionId)
                .set(WORKFLOW_DEFINITION_VERSION.NUMBER, newest + 1)
                .set(WORKFLOW_DEFINITION_VERSION.BODY, JSONB.valueOf(body))
                .set(WORKFLOW_DEFINITION_VERSION.SCHEMA_VERSION, Definition.SCHEMA_VERSION)
                .set(WORKFLOW_DEFINITION_VERSION.STATE, VersionState.DRAFT.name)
                .set(WORKFLOW_DEFINITION_VERSION.CREATED_AT, now())
                .execute()
        } catch (_: IntegrityConstraintViolationException) {
            throw ConcurrentModificationException("workflow definition $definitionId", newest.toLong())
        } catch (_: DuplicateKeyException) {
            throw ConcurrentModificationException("workflow definition $definitionId", newest.toLong())
        }
        return id to newest + 1
    }

    /** Replaces a draft's body if it is still a draft at [revision]. */
    fun replaceBody(
        versionId: UUID,
        revision: Long,
        body: String,
    ): Boolean =
        move(
            versionId,
            VersionState.DRAFT,
            VersionState.DRAFT,
            revision,
            mapOf(WORKFLOW_DEFINITION_VERSION.BODY to JSONB.valueOf(body)),
        )

    /**
     * Moves a version from [from] to [to] and sets [fields], if it is still in [from] (and at [revision] when given);
     * false when it was not.
     */
    fun move(
        versionId: UUID,
        from: VersionState,
        to: VersionState,
        revision: Long? = null,
        fields: Map<out Field<*>, Any?> = emptyMap(),
    ): Boolean {
        val update =
            tenantDsl
                .current()
                .update(WORKFLOW_DEFINITION_VERSION)
                .set(WORKFLOW_DEFINITION_VERSION.STATE, to.name)
                .set(WORKFLOW_DEFINITION_VERSION.REVISION, WORKFLOW_DEFINITION_VERSION.REVISION.plus(1))
        @Suppress("UNCHECKED_CAST")
        fields.forEach { (field, value) -> update.set(field as Field<Any?>, value) }
        val guard =
            WORKFLOW_DEFINITION_VERSION.ID
                .eq(versionId)
                .and(WORKFLOW_DEFINITION_VERSION.STATE.eq(from.name))
                .and(revision?.let { WORKFLOW_DEFINITION_VERSION.REVISION.eq(it) } ?: DSL.noCondition())
        return update.where(guard).execute() == 1
    }

    /** Publishes [versionId] (in review) with its [compiled] body, retiring the version published before it (ADR-0038). */
    fun publish(
        definitionId: UUID,
        versionId: UUID,
        reviewer: UUID,
        remark: String?,
        compiled: String,
    ): Boolean {
        tenantDsl
            .current()
            .update(WORKFLOW_DEFINITION_VERSION)
            .set(WORKFLOW_DEFINITION_VERSION.STATE, VersionState.RETIRED.name)
            .set(WORKFLOW_DEFINITION_VERSION.REVISION, WORKFLOW_DEFINITION_VERSION.REVISION.plus(1))
            .where(
                WORKFLOW_DEFINITION_VERSION.DEFINITION_ID.eq(definitionId),
                WORKFLOW_DEFINITION_VERSION.STATE.eq(VersionState.PUBLISHED.name),
            ).execute()
        val at = now()
        return move(
            versionId,
            VersionState.IN_REVIEW,
            VersionState.PUBLISHED,
            fields =
                mapOf(
                    WORKFLOW_DEFINITION_VERSION.REVIEWED_BY to reviewer,
                    WORKFLOW_DEFINITION_VERSION.REVIEWED_AT to at,
                    WORKFLOW_DEFINITION_VERSION.REVIEW_REMARK to remark,
                    WORKFLOW_DEFINITION_VERSION.PUBLISHED_AT to at,
                    WORKFLOW_DEFINITION_VERSION.COMPILED to JSONB.valueOf(compiled),
                ),
        )
    }

    fun now(): OffsetDateTime = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC)

    private fun row(v: WorkflowDefinitionVersionRecord): VersionRow =
        VersionRow(
            id = v.id,
            definitionId = v.definitionId,
            number = v.number,
            state = VersionState.valueOf(v.state),
            schemaVersion = v.schemaVersion,
            revision = v.revision!!,
            body = v.body.data(),
            submittedBy = v.submittedBy,
            submittedAt = v.submittedAt?.toInstant(),
            reviewedBy = v.reviewedBy,
            reviewedAt = v.reviewedAt?.toInstant(),
            reviewRemark = v.reviewRemark,
            publishedAt = v.publishedAt?.toInstant(),
        )
}
