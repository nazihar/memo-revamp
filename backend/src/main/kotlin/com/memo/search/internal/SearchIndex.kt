package com.memo.search.internal

import com.memo.common.tenant.TenantDsl
import com.memo.jooq.routines.references.searchPutChunks
import com.memo.jooq.routines.references.searchPutMemo
import com.memo.jooq.routines.references.searchPutNote
import com.memo.jooq.routines.references.searchSetChunkRank
import com.memo.jooq.tables.references.SEARCH_UNINDEXED
import com.memo.memo.MemoFileFacts
import com.memo.memo.MemoSearchFacts
import com.memo.memo.MemoSearchSource
import org.springframework.stereotype.Component
import java.time.ZoneOffset
import java.util.UUID

/**
 * Writes the search index (ADR-0054) through the search functions, the only way the application role reaches the search
 * tables. Every method runs inside a tenant transaction: memo and note rows move with their source's transaction.
 */
@Component
class SearchIndex(
    private val source: MemoSearchSource,
    private val tenantDsl: TenantDsl,
) {
    /** Indexes [memoId]'s details as they are now; false when the memo does not exist. */
    fun putMemo(memoId: UUID): Boolean = source.facts(memoId)?.also(::put) != null

    /** Indexes a posted note; false when there is no such note or its memo is not indexed yet (the backfill follows). */
    fun putNote(noteId: UUID): Boolean {
        val note = source.note(noteId) ?: return false
        return searchPutNote(tenantDsl.current().configuration(), tenant(), note.noteId, note.memoId, note.text) == true
    }

    /**
     * Follows a memo file's level, or drops its chunks when it is no longer a memo's file (an attachment removed). Returns
     * the file as the memo module describes it now; null when it is not a memo's file.
     */
    fun refreshFile(fileId: UUID): MemoFileFacts? {
        val file = source.file(fileId)
        if (file == null) {
            dropFile(fileId)
        } else {
            searchSetChunkRank(tenantDsl.current().configuration(), tenant(), fileId, file.rank)
        }
        return file
    }

    /** Replaces [file]'s chunks with [chunks] of its version [versionId]; false when its memo is not indexed yet. */
    fun putChunks(
        file: MemoFileFacts,
        versionId: UUID,
        chunks: List<String>,
    ): Boolean =
        searchPutChunks(
            tenantDsl.current().configuration(),
            tenant(),
            file.fileId,
            versionId,
            file.memoId,
            file.rank,
            chunks.toTypedArray<String?>(),
        ) == true

    fun dropFile(fileId: UUID) {
        searchPutChunks(tenantDsl.current().configuration(), tenant(), fileId, null, null, null, emptyArray())
    }

    /** Up to [limit] memos of the tenant not indexed yet, in id order (the backfill's work list). */
    fun unindexed(limit: Int): List<UUID> =
        tenantDsl
            .current()
            .select(SEARCH_UNINDEXED.SEARCH_UNINDEXED_)
            .from(SEARCH_UNINDEXED.call(tenant(), limit))
            .fetch(SEARCH_UNINDEXED.SEARCH_UNINDEXED_)
            .filterNotNull()

    private fun put(facts: MemoSearchFacts) {
        searchPutMemo(
            tenantDsl.current().configuration(),
            tenant(),
            facts.memoId,
            facts.classificationRank,
            facts.originUnitId,
            facts.status,
            facts.typeId,
            facts.initiatorId,
            facts.amount,
            facts.createdAt.atOffset(ZoneOffset.UTC),
            facts.updatedAt.atOffset(ZoneOffset.UTC),
            facts.reference?.let(SearchReferences::norm),
            facts.reference?.let(SearchReferences::tail),
            facts.subject,
            facts.fieldsText,
            facts.origin.name,
        )
    }

    private fun tenant(): UUID = tenantDsl.tenant().value
}
