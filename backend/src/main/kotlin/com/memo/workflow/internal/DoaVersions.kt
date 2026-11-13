package com.memo.workflow.internal

import com.memo.common.concurrency.ConcurrentModificationException
import com.memo.common.id.IdGenerator
import com.memo.common.tenant.TenantDsl
import com.memo.jooq.tables.records.WorkflowDoaRuleRecord
import com.memo.jooq.tables.records.WorkflowDoaVersionRecord
import com.memo.jooq.tables.references.WORKFLOW_DOA_RULE
import com.memo.jooq.tables.references.WORKFLOW_DOA_VERSION
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
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/** A member of a DoA approval group: the holder of a position, or the holders of a role in a unit (design E04 §3). */
data class DoaMember(
    /** `position` or `role`. */
    val kind: String,
    val positionId: UUID? = null,
    val roleId: UUID? = null,
    /** For a role: `initiator` (the initiator's unit and above) or a unit id. */
    val unit: String? = null,
)

/** An approval group: [needed] approvals from the people its members resolve to. */
data class DoaRuleGroup(
    val needed: Int,
    val of: List<DoaMember>,
)

/**
 * One DoA rule (design E04 §3, §4.3): the scope it applies to (subject type, memo type, the initiator's unit or its
 * subtree, a condition over the subject's attributes) and the approval groups it asks for, in order.
 */
data class DoaRule(
    val name: String,
    val subjectType: String,
    val memoTypeId: UUID? = null,
    val unitId: UUID? = null,
    val includeSubtree: Boolean = true,
    val condition: String? = null,
    val groups: List<DoaRuleGroup>,
)

data class DoaVersionRow(
    val id: UUID,
    val number: Int,
    val state: VersionState,
    val effectiveFrom: LocalDate?,
    /** Incremented by every change; the `ETag` clients send back in `If-Match`. */
    val revision: Long,
    val createdBy: UUID,
    val submittedBy: UUID?,
    val submittedAt: Instant?,
    val reviewedBy: UUID?,
    val reviewedAt: Instant?,
    val reviewRemark: String?,
    val publishedAt: Instant?,
)

/**
 * DoA versions and their rules (design E04 §3), inside a tenant transaction. State changes are guarded by the state (and
 * the revision where the client sent one), so a lost race changes nothing and the caller reports a conflict.
 */
@Repository
class DoaVersions(
    private val tenantDsl: TenantDsl,
    private val ids: IdGenerator,
    private val json: JsonMapper,
    private val clock: Clock,
) {
    /** Every version, newest first. */
    fun versions(): List<DoaVersionRow> =
        tenantDsl
            .current()
            .selectFrom(WORKFLOW_DOA_VERSION)
            .orderBy(WORKFLOW_DOA_VERSION.NUMBER.desc())
            .fetch(::row)

    fun version(number: Int): DoaVersionRow? =
        tenantDsl
            .current()
            .selectFrom(WORKFLOW_DOA_VERSION)
            .where(WORKFLOW_DOA_VERSION.NUMBER.eq(number))
            .fetchOne(::row)

    fun byId(id: UUID): DoaVersionRow? =
        tenantDsl
            .current()
            .selectFrom(WORKFLOW_DOA_VERSION)
            .where(WORKFLOW_DOA_VERSION.ID.eq(id))
            .fetchOne(::row)

    /** The published version in force on [date]: the newest effective on or before it. */
    fun inForce(date: LocalDate): DoaVersionRow? =
        tenantDsl
            .current()
            .selectFrom(WORKFLOW_DOA_VERSION)
            .where(WORKFLOW_DOA_VERSION.STATE.eq(VersionState.PUBLISHED.name), WORKFLOW_DOA_VERSION.EFFECTIVE_FROM.le(date))
            .orderBy(WORKFLOW_DOA_VERSION.EFFECTIVE_FROM.desc())
            .limit(1)
            .fetchOne(::row)

    /** The rules of [versionId], in order. */
    fun rules(versionId: UUID): List<DoaRule> =
        tenantDsl
            .current()
            .selectFrom(WORKFLOW_DOA_RULE)
            .where(WORKFLOW_DOA_RULE.VERSION_ID.eq(versionId))
            .orderBy(WORKFLOW_DOA_RULE.ORDINAL)
            .fetch(::rule)

    /** A new draft after the newest version; one draft or version in review at a time (a unique index). */
    fun draft(
        effectiveFrom: LocalDate?,
        rules: List<DoaRule>,
        createdBy: UUID,
    ): Int {
        val dsl = tenantDsl.current()
        val newest = dsl.select(DSL.max(WORKFLOW_DOA_VERSION.NUMBER)).from(WORKFLOW_DOA_VERSION).fetchOne(0, Int::class.java) ?: 0
        val id = ids.next()
        try {
            dsl
                .insertInto(WORKFLOW_DOA_VERSION)
                .set(WORKFLOW_DOA_VERSION.ID, id)
                .set(WORKFLOW_DOA_VERSION.TENANT_ID, tenantDsl.tenant().value)
                .set(WORKFLOW_DOA_VERSION.NUMBER, newest + 1)
                .set(WORKFLOW_DOA_VERSION.STATE, VersionState.DRAFT.name)
                .set(WORKFLOW_DOA_VERSION.EFFECTIVE_FROM, effectiveFrom)
                .set(WORKFLOW_DOA_VERSION.CREATED_BY, createdBy)
                .set(WORKFLOW_DOA_VERSION.CREATED_AT, now())
                .execute()
        } catch (_: IntegrityConstraintViolationException) {
            throw ConcurrentModificationException("workflow doa", newest.toLong())
        } catch (_: DuplicateKeyException) {
            throw ConcurrentModificationException("workflow doa", newest.toLong())
        }
        insertRules(id, rules)
        return newest + 1
    }

    /** Replaces a draft's date and rules if it is still a draft at [revision]. */
    fun replace(
        versionId: UUID,
        revision: Long,
        effectiveFrom: LocalDate?,
        rules: List<DoaRule>,
    ): Boolean {
        val moved =
            move(versionId, VersionState.DRAFT, VersionState.DRAFT, revision, mapOf(WORKFLOW_DOA_VERSION.EFFECTIVE_FROM to effectiveFrom))
        if (moved) {
            tenantDsl
                .current()
                .deleteFrom(WORKFLOW_DOA_RULE)
                .where(WORKFLOW_DOA_RULE.VERSION_ID.eq(versionId))
                .execute()
            insertRules(versionId, rules)
        }
        return moved
    }

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
                .update(WORKFLOW_DOA_VERSION)
                .set(WORKFLOW_DOA_VERSION.STATE, to.name)
                .set(WORKFLOW_DOA_VERSION.REVISION, WORKFLOW_DOA_VERSION.REVISION.plus(1))
        @Suppress("UNCHECKED_CAST")
        fields.forEach { (field, value) -> update.set(field as Field<Any?>, value) }
        val guard =
            WORKFLOW_DOA_VERSION.ID
                .eq(versionId)
                .and(WORKFLOW_DOA_VERSION.STATE.eq(from.name))
                .and(revision?.let { WORKFLOW_DOA_VERSION.REVISION.eq(it) } ?: DSL.noCondition())
        return update.where(guard).execute() == 1
    }

    fun now(): OffsetDateTime = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC)

    private fun insertRules(
        versionId: UUID,
        rules: List<DoaRule>,
    ) {
        if (rules.isEmpty()) return
        val dsl = tenantDsl.current()
        val insert =
            dsl.insertInto(
                WORKFLOW_DOA_RULE,
                WORKFLOW_DOA_RULE.ID,
                WORKFLOW_DOA_RULE.TENANT_ID,
                WORKFLOW_DOA_RULE.VERSION_ID,
                WORKFLOW_DOA_RULE.ORDINAL,
                WORKFLOW_DOA_RULE.NAME,
                WORKFLOW_DOA_RULE.SUBJECT_TYPE,
                WORKFLOW_DOA_RULE.MEMO_TYPE_ID,
                WORKFLOW_DOA_RULE.UNIT_ID,
                WORKFLOW_DOA_RULE.INCLUDE_SUBTREE,
                WORKFLOW_DOA_RULE.CONDITION,
                WORKFLOW_DOA_RULE.GROUPS,
            )
        rules
            .foldIndexed(insert) { i, query, rule ->
                query.values(
                    ids.next(),
                    tenantDsl.tenant().value,
                    versionId,
                    i,
                    rule.name,
                    rule.subjectType,
                    rule.memoTypeId,
                    rule.unitId,
                    rule.includeSubtree,
                    rule.condition,
                    JSONB.valueOf(json.writeValueAsString(rule.groups)),
                )
            }.execute()
    }

    private fun rule(r: WorkflowDoaRuleRecord): DoaRule =
        DoaRule(
            name = r.name,
            subjectType = r.subjectType,
            memoTypeId = r.memoTypeId,
            unitId = r.unitId,
            includeSubtree = r.includeSubtree == true,
            condition = r.condition,
            groups = json.readValue(r.groups.data(), GROUPS),
        )

    private companion object {
        val GROUPS = object : TypeReference<List<DoaRuleGroup>>() {}
    }
}

private fun row(v: WorkflowDoaVersionRecord): DoaVersionRow =
    DoaVersionRow(
        id = v.id,
        number = v.number,
        state = VersionState.valueOf(v.state),
        effectiveFrom = v.effectiveFrom,
        revision = v.revision ?: 0,
        createdBy = v.createdBy,
        submittedBy = v.submittedBy,
        submittedAt = v.submittedAt?.toInstant(),
        reviewedBy = v.reviewedBy,
        reviewedAt = v.reviewedAt?.toInstant(),
        reviewRemark = v.reviewRemark,
        publishedAt = v.publishedAt?.toInstant(),
    )
