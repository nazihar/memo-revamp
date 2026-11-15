package com.memo.memo.internal.rendition

import com.memo.common.id.IdGenerator
import com.memo.common.tenant.TenantDsl
import com.memo.jooq.tables.records.MemoRenditionRecord
import com.memo.jooq.tables.references.MEMO_RENDITION
import com.memo.jooq.tables.references.MEMO_RENDITION_PART
import org.jooq.JSONB
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

enum class RenditionState { PENDING, BUILDING, READY, FAILED }

enum class RenditionTrigger { APPROVED, BACKFILL, REGENERATE, RETRY }

enum class PartKind { COVER, DOCUMENT, APPROVALS, NOTES, ATTACHMENT, PLACEHOLDER }

/** One version of a memo's final record (design E04 §2). */
data class MemoRendition(
    val id: UUID,
    val memoId: UUID,
    val version: Int,
    val state: RenditionState,
    val trigger: RenditionTrigger,
    val reason: String?,
    val requestedBy: UUID?,
    val fileId: UUID?,
    val fileVersionId: UUID?,
    val sha256: String?,
    val sizeBytes: Long?,
    val pages: Int?,
    val classificationId: UUID?,
    val classificationRank: Int?,
    val compliant: Boolean?,
    val validation: Map<String, Any?>,
    val flags: Map<String, Any?>,
    val renderer: Map<String, Any?>,
    val attempts: Int,
    val error: String?,
    val createdAt: Instant,
    val finishedAt: Instant?,
)

/** What went into a record, in order. */
data class RenditionPart(
    val position: Int,
    val kind: PartKind,
    val title: String,
    val sourceVersionId: UUID?,
    val sourceSha256: String?,
    val firstPage: Int,
    val pages: Int,
    val mode: PartMode,
)

/** The stored result of a build, written once when the record becomes READY. */
data class BuiltRecord(
    val fileId: UUID,
    val fileVersionId: UUID,
    val sha256: String,
    val sizeBytes: Long,
    val pages: Int,
    val classificationId: UUID,
    val classificationRank: Int,
    val compliant: Boolean,
    val validation: Map<String, Any?>,
    val flags: Map<String, Any?>,
    val renderer: Map<String, Any?>,
    val parts: List<RenditionPart>,
)

/** `memo_rendition` and `memo_rendition_part` (memo/V17); inside a tenant transaction. */
@Repository
class MemoRenditionStore(
    private val tenantDsl: TenantDsl,
    private val ids: IdGenerator,
    private val json: JsonMapper,
    private val clock: Clock,
) {
    /** A new PENDING version after the memo's highest; the caller has made sure none is open. */
    fun request(
        memoId: UUID,
        trigger: RenditionTrigger,
        reason: String?,
        requestedBy: UUID?,
    ): MemoRendition {
        val dsl = tenantDsl.current()
        val next =
            (
                dsl
                    .select(
                        DSL.max(MEMO_RENDITION.VERSION),
                    ).from(MEMO_RENDITION)
                    .where(MEMO_RENDITION.MEMO_ID.eq(memoId))
                    .fetchOne(0, Int::class.java)
                    ?: 0
            ) +
                1
        val id = ids.next()
        dsl
            .insertInto(MEMO_RENDITION)
            .set(MEMO_RENDITION.ID, id)
            .set(MEMO_RENDITION.TENANT_ID, tenantDsl.tenant().value)
            .set(MEMO_RENDITION.MEMO_ID, memoId)
            .set(MEMO_RENDITION.VERSION, next)
            .set(MEMO_RENDITION.STATE, RenditionState.PENDING.name)
            .set(MEMO_RENDITION.TRIGGER, trigger.name)
            .set(MEMO_RENDITION.REASON, reason)
            .set(MEMO_RENDITION.REQUESTED_BY, requestedBy)
            .set(MEMO_RENDITION.CREATED_AT, now())
            .execute()
        return checkNotNull(get(id))
    }

    fun get(id: UUID): MemoRendition? =
        tenantDsl
            .current()
            .selectFrom(MEMO_RENDITION)
            .where(MEMO_RENDITION.ID.eq(id))
            .fetchOne()
            ?.let(::of)

    /** The memo's versions, newest first. */
    fun of(memoId: UUID): List<MemoRendition> =
        tenantDsl
            .current()
            .selectFrom(MEMO_RENDITION)
            .where(MEMO_RENDITION.MEMO_ID.eq(memoId))
            .orderBy(MEMO_RENDITION.VERSION.desc())
            .fetch(::of)

    /** PENDING or FAILED → BUILDING, one attempt more; false if someone else has it or it is done. */
    fun claim(id: UUID): Boolean =
        tenantDsl
            .current()
            .update(MEMO_RENDITION)
            .set(MEMO_RENDITION.STATE, RenditionState.BUILDING.name)
            .set(MEMO_RENDITION.ATTEMPTS, MEMO_RENDITION.ATTEMPTS.plus(1))
            .where(MEMO_RENDITION.ID.eq(id), MEMO_RENDITION.STATE.`in`(RenditionState.PENDING.name, RenditionState.BUILDING.name))
            .execute() == 1

    /** BUILDING → PENDING (a retryable failure) or FAILED (final), with the reason's code. */
    fun failed(
        id: UUID,
        error: String,
        final: Boolean,
    ) {
        tenantDsl
            .current()
            .update(MEMO_RENDITION)
            .set(MEMO_RENDITION.STATE, if (final) RenditionState.FAILED.name else RenditionState.PENDING.name)
            .set(MEMO_RENDITION.ERROR, error)
            .set(MEMO_RENDITION.FINISHED_AT, if (final) now() else null)
            .where(MEMO_RENDITION.ID.eq(id), MEMO_RENDITION.STATE.eq(RenditionState.BUILDING.name))
            .execute()
    }

    /** FAILED → PENDING for another try (same version). */
    fun reopen(id: UUID): Boolean =
        tenantDsl
            .current()
            .update(MEMO_RENDITION)
            .set(MEMO_RENDITION.STATE, RenditionState.PENDING.name)
            .set(MEMO_RENDITION.ERROR, null as String?)
            .set(MEMO_RENDITION.FINISHED_AT, null as OffsetDateTime?)
            .where(MEMO_RENDITION.ID.eq(id), MEMO_RENDITION.STATE.eq(RenditionState.FAILED.name))
            .execute() == 1

    /** BUILDING → READY with what was stored, and its parts. */
    fun ready(
        id: UUID,
        built: BuiltRecord,
    ) {
        val dsl = tenantDsl.current()
        val updated =
            dsl
                .update(MEMO_RENDITION)
                .set(MEMO_RENDITION.STATE, RenditionState.READY.name)
                .set(MEMO_RENDITION.FILE_ID, built.fileId)
                .set(MEMO_RENDITION.FILE_VERSION_ID, built.fileVersionId)
                .set(MEMO_RENDITION.SHA256, built.sha256)
                .set(MEMO_RENDITION.SIZE_BYTES, built.sizeBytes)
                .set(MEMO_RENDITION.PAGES, built.pages)
                .set(MEMO_RENDITION.CLASSIFICATION_ID, built.classificationId)
                .set(MEMO_RENDITION.CLASSIFICATION_RANK, built.classificationRank)
                .set(MEMO_RENDITION.COMPLIANT, built.compliant)
                .set(MEMO_RENDITION.VALIDATION, jsonb(built.validation))
                .set(MEMO_RENDITION.FLAGS, jsonb(built.flags))
                .set(MEMO_RENDITION.RENDERER, jsonb(built.renderer))
                .set(MEMO_RENDITION.ERROR, null as String?)
                .set(MEMO_RENDITION.FINISHED_AT, now())
                .where(MEMO_RENDITION.ID.eq(id), MEMO_RENDITION.STATE.eq(RenditionState.BUILDING.name))
                .execute()
        check(updated == 1) { "rendition $id was not being built" }
        built.parts.forEach { part ->
            dsl
                .insertInto(MEMO_RENDITION_PART)
                .set(MEMO_RENDITION_PART.TENANT_ID, tenantDsl.tenant().value)
                .set(MEMO_RENDITION_PART.RENDITION_ID, id)
                .set(MEMO_RENDITION_PART.POSITION, part.position)
                .set(MEMO_RENDITION_PART.KIND, part.kind.name)
                .set(MEMO_RENDITION_PART.TITLE, part.title.take(MAX_TITLE))
                .set(MEMO_RENDITION_PART.SOURCE_VERSION_ID, part.sourceVersionId)
                .set(MEMO_RENDITION_PART.SOURCE_SHA256, part.sourceSha256)
                .set(MEMO_RENDITION_PART.FIRST_PAGE, part.firstPage)
                .set(MEMO_RENDITION_PART.PAGES, part.pages)
                .set(MEMO_RENDITION_PART.MODE, part.mode.name)
                .execute()
        }
    }

    private fun of(r: MemoRenditionRecord) =
        MemoRendition(
            id = r.id,
            memoId = r.memoId,
            version = r.version,
            state = RenditionState.valueOf(r.state),
            trigger = RenditionTrigger.valueOf(r.trigger),
            reason = r.reason,
            requestedBy = r.requestedBy,
            fileId = r.fileId,
            fileVersionId = r.fileVersionId,
            sha256 = r.sha256,
            sizeBytes = r.sizeBytes,
            pages = r.pages,
            classificationId = r.classificationId,
            classificationRank = r.classificationRank,
            compliant = r.compliant,
            validation = map(r.validation),
            flags = map(r.flags),
            renderer = map(r.renderer),
            attempts = r.attempts ?: 0,
            error = r.error,
            createdAt = r.createdAt.toInstant(),
            finishedAt = r.finishedAt?.toInstant(),
        )

    @Suppress("UNCHECKED_CAST")
    private fun map(value: JSONB?): Map<String, Any?> =
        if (value == null) emptyMap() else json.readValue(value.data(), Map::class.java) as Map<String, Any?>

    private fun jsonb(value: Map<String, Any?>): JSONB = JSONB.valueOf(json.writeValueAsString(value))

    private fun now(): OffsetDateTime = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC)

    private companion object {
        const val MAX_TITLE = 500
    }
}
