package com.memo.memo.internal.rendition

import com.memo.common.tenant.TenantDsl
import com.memo.jooq.tables.references.MEMO_
import com.memo.jooq.tables.references.MEMO_RENDITION
import com.memo.jooq.tables.references.MEMO_RENDITION_PART
import com.memo.memo.MemoOrigin
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import java.util.UUID

/** Reads over the records beside their lifecycle (design E04 §2): parts, the rank behind a file, and memos still without one. */
@Repository
class MemoRenditionQueries(
    private val tenantDsl: TenantDsl,
) {
    /** Where the memo's final record is, for the memo page: NONE until it is approved. */
    fun state(
        memoId: UUID,
        approved: Boolean,
    ): RecordState {
        if (!approved) return RecordState.NONE
        val states =
            tenantDsl
                .current()
                .select(MEMO_RENDITION.STATE)
                .from(MEMO_RENDITION)
                .where(MEMO_RENDITION.MEMO_ID.eq(memoId))
                .fetch(MEMO_RENDITION.STATE)
                .map { RenditionState.valueOf(it!!) }
        return RecordState.of(states)
    }

    fun parts(id: UUID): List<RenditionPart> =
        tenantDsl
            .current()
            .selectFrom(MEMO_RENDITION_PART)
            .where(MEMO_RENDITION_PART.RENDITION_ID.eq(id))
            .orderBy(MEMO_RENDITION_PART.POSITION)
            .fetch { r ->
                RenditionPart(
                    r.position,
                    PartKind.valueOf(r.kind),
                    r.title,
                    r.sourceVersionId,
                    r.sourceSha256,
                    r.firstPage,
                    r.pages,
                    PartMode.valueOf(r.mode),
                )
            }

    /** The classification rank of the record stored as [fileId] (any of its versions); null if it is not one. */
    fun rankOfFile(fileId: UUID): Int? =
        tenantDsl
            .current()
            .select(DSL.max(MEMO_RENDITION.CLASSIFICATION_RANK))
            .from(MEMO_RENDITION)
            .where(MEMO_RENDITION.FILE_ID.eq(fileId))
            .fetchOne(0, Int::class.java)

    /** Approved or closed memos with no record at all (approved before E04), oldest first. */
    fun missing(limit: Int): List<UUID> =
        tenantDsl
            .current()
            .select(MEMO_.ID)
            .from(MEMO_)
            .where(
                MEMO_.APPROVED_AT.isNotNull,
                // The originals are an imported memo's record: it never gets one of ours (design E06 §11).
                MEMO_.ORIGIN.eq(MemoOrigin.NATIVE.name),
                DSL.notExists(DSL.selectOne().from(MEMO_RENDITION).where(MEMO_RENDITION.MEMO_ID.eq(MEMO_.ID))),
            ).orderBy(MEMO_.APPROVED_AT)
            .limit(limit)
            .fetch(MEMO_.ID)
            .filterNotNull()

    /** A made record version, as the fixity check reads it. */
    data class ReadyRecord(
        val id: UUID,
        val memoId: UUID,
        val version: Int,
        val fileVersionId: UUID,
        val sha256: String,
    )

    /** Made versions after [after] (by id), at most [limit]: the fixity check's pages. */
    fun ready(
        after: UUID?,
        limit: Int,
    ): List<ReadyRecord> =
        tenantDsl
            .current()
            .select(
                MEMO_RENDITION.ID,
                MEMO_RENDITION.MEMO_ID,
                MEMO_RENDITION.VERSION,
                MEMO_RENDITION.FILE_VERSION_ID,
                MEMO_RENDITION.SHA256,
            ).from(MEMO_RENDITION)
            .where(MEMO_RENDITION.STATE.eq(RenditionState.READY.name), after?.let { MEMO_RENDITION.ID.gt(it) } ?: DSL.noCondition())
            .orderBy(MEMO_RENDITION.ID)
            .limit(limit)
            .fetch { r -> ReadyRecord(r.value1()!!, r.value2()!!, r.value3()!!, r.value4()!!, r.value5()!!) }
}
