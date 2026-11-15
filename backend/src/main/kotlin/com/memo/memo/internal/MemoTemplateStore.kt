package com.memo.memo.internal

import com.memo.common.review.ReviewState
import com.memo.common.tenant.TenantDsl
import com.memo.jooq.tables.references.MEMO_
import com.memo.jooq.tables.references.MEMO_TYPE_VERSION
import com.memo.templating.TemplateScan
import org.jooq.JSONB
import org.springframework.stereotype.Repository
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/** A type version's template (ADR-0048): the exact file version, its cached scan, and the version last previewed. */
data class TemplateState(
    val versionId: UUID,
    val state: ReviewState,
    val revision: Long,
    val fileId: UUID?,
    val fileVersionId: UUID?,
    val scan: TemplateScan?,
    val scanVersionId: UUID?,
    val previewedId: UUID?,
) {
    val present: Boolean get() = fileVersionId != null

    /** The cached scan, if it is of the current template version. */
    val currentScan: TemplateScan? get() = scan?.takeIf { fileVersionId != null && scanVersionId == fileVersionId }

    /** Whether the current template version was previewed without error. */
    val previewed: Boolean get() = fileVersionId != null && previewedId == fileVersionId
}

/** The template columns of `memo_type_version` and `memo` (V12); inside a tenant transaction. */
@Repository
class MemoTemplateStore(
    private val tenantDsl: TenantDsl,
    private val json: JsonMapper,
) {
    fun of(versionId: UUID): TemplateState? = select(versionId, lock = false)

    /** The state under the version's row lock, so a template change and a submit do not cross. */
    fun lock(versionId: UUID): TemplateState? = select(versionId, lock = true)

    private fun select(
        versionId: UUID,
        lock: Boolean,
    ): TemplateState? {
        val v = MEMO_TYPE_VERSION
        val query =
            tenantDsl
                .current()
                .select(
                    v.ID,
                    v.STATE,
                    v.REVISION,
                    v.TEMPLATE_FILE_ID,
                    v.TEMPLATE_VERSION_ID,
                    v.TEMPLATE_SCAN,
                    v.TEMPLATE_SCAN_VERSION_ID,
                    v.TEMPLATE_PREVIEWED_ID,
                ).from(v)
                .where(v.ID.eq(versionId))
        val row = (if (lock) query.forUpdate().fetchOne() else query.fetchOne()) ?: return null
        return TemplateState(
            versionId = row[v.ID]!!,
            state = ReviewState.valueOf(row[v.STATE]!!),
            revision = row[v.REVISION] ?: 0,
            fileId = row[v.TEMPLATE_FILE_ID],
            fileVersionId = row[v.TEMPLATE_VERSION_ID],
            scan = row[v.TEMPLATE_SCAN]?.let { json.readValue(it.data(), TemplateScan::class.java) },
            scanVersionId = row[v.TEMPLATE_SCAN_VERSION_ID],
            previewedId = row[v.TEMPLATE_PREVIEWED_ID],
        )
    }

    /** Points the version at a new template version; a new template is not previewed yet. Bumps the revision. */
    fun set(
        versionId: UUID,
        fileId: UUID?,
        fileVersionId: UUID?,
    ) {
        tenantDsl
            .current()
            .update(MEMO_TYPE_VERSION)
            .set(MEMO_TYPE_VERSION.TEMPLATE_FILE_ID, fileId)
            .set(MEMO_TYPE_VERSION.TEMPLATE_VERSION_ID, fileVersionId)
            .set(MEMO_TYPE_VERSION.TEMPLATE_PREVIEWED_ID, null as UUID?)
            .set(MEMO_TYPE_VERSION.REVISION, MEMO_TYPE_VERSION.REVISION.plus(1))
            .where(MEMO_TYPE_VERSION.ID.eq(versionId))
            .execute()
    }

    /** Caches [scan] of [fileVersionId], if that is still the version's template. */
    fun storeScan(
        versionId: UUID,
        fileVersionId: UUID,
        scan: TemplateScan,
    ) {
        tenantDsl
            .current()
            .update(MEMO_TYPE_VERSION)
            .set(MEMO_TYPE_VERSION.TEMPLATE_SCAN, JSONB.valueOf(json.writeValueAsString(scan)))
            .set(MEMO_TYPE_VERSION.TEMPLATE_SCAN_VERSION_ID, fileVersionId)
            .where(MEMO_TYPE_VERSION.ID.eq(versionId), MEMO_TYPE_VERSION.TEMPLATE_VERSION_ID.eq(fileVersionId))
            .execute()
    }

    /** Records a preview of [fileVersionId] without error, if that is still the template of a draft. */
    fun markPreviewed(
        versionId: UUID,
        fileVersionId: UUID,
    ): Boolean =
        tenantDsl
            .current()
            .update(MEMO_TYPE_VERSION)
            .set(MEMO_TYPE_VERSION.TEMPLATE_PREVIEWED_ID, fileVersionId)
            .where(
                MEMO_TYPE_VERSION.ID.eq(versionId),
                MEMO_TYPE_VERSION.TEMPLATE_VERSION_ID.eq(fileVersionId),
                MEMO_TYPE_VERSION.STATE.eq(ReviewState.DRAFT.name),
            ).execute() == 1

    /** A new draft starts with the template of the version it was copied from, scan and preview included. */
    fun copy(
        from: UUID,
        to: UUID,
    ) {
        val source = of(from) ?: return
        tenantDsl
            .current()
            .update(MEMO_TYPE_VERSION)
            .set(MEMO_TYPE_VERSION.TEMPLATE_FILE_ID, source.fileId)
            .set(MEMO_TYPE_VERSION.TEMPLATE_VERSION_ID, source.fileVersionId)
            .set(MEMO_TYPE_VERSION.TEMPLATE_SCAN, source.scan?.let { JSONB.valueOf(json.writeValueAsString(it)) })
            .set(MEMO_TYPE_VERSION.TEMPLATE_SCAN_VERSION_ID, source.scanVersionId.takeIf { source.scan != null })
            .set(MEMO_TYPE_VERSION.TEMPLATE_PREVIEWED_ID, source.previewedId)
            .where(MEMO_TYPE_VERSION.ID.eq(to))
            .execute()
    }

    /** The template version a memo's document was filled from. */
    fun filledFrom(memoId: UUID): UUID? =
        tenantDsl
            .current()
            .select(MEMO_.TEMPLATE_VERSION_ID)
            .from(MEMO_)
            .where(MEMO_.ID.eq(memoId))
            .fetchOne(MEMO_.TEMPLATE_VERSION_ID)

    fun recordFill(
        memoId: UUID,
        fileVersionId: UUID,
    ) {
        tenantDsl
            .current()
            .update(MEMO_)
            .set(MEMO_.TEMPLATE_VERSION_ID, fileVersionId)
            .where(MEMO_.ID.eq(memoId))
            .execute()
    }
}
