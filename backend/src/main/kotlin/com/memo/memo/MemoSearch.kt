package com.memo.memo

import com.memo.common.events.TenantScopedEvent
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * What the search index holds about a memo (ADR-0054, design E05 §3): its details, with the type's field values flattened
 * to text. The search module derives its rows from these; the memo module stays the source of truth.
 */
data class MemoSearchFacts(
    val memoId: UUID,
    val classificationRank: Int,
    val originUnitId: UUID?,
    val status: String,
    val typeId: UUID,
    val initiatorId: UUID,
    /** The value of the type's first amount field, if any (the search filter "Amount"). */
    val amount: BigDecimal?,
    val createdAt: Instant,
    val updatedAt: Instant,
    /** The memo's number; null while it is unnumbered. */
    val reference: String?,
    val subject: String,
    /** The type's name and the readable field values, one per line. */
    val fieldsText: String,
)

/** A posted note's plain text (drafts are private and never indexed). */
data class MemoNoteText(
    val noteId: UUID,
    val memoId: UUID,
    val text: String,
)

/**
 * A memo file whose text the index may hold: the memo's document or a present attachment. [rank] is the file's own
 * classification (an attachment's stricter level, else the memo's); the index takes the higher of it and the memo's.
 */
data class MemoFileFacts(
    val fileId: UUID,
    val memoId: UUID,
    val rank: Int,
    /** The version whose text is searchable: the file's current version. */
    val currentVersionId: UUID?,
)

/** Memo facts for the search index, implemented by the memo module. Callers run inside a tenant transaction. */
interface MemoSearchSource {
    /** The facts of [memoId]; null when there is no such memo in the current tenant. */
    fun facts(memoId: UUID): MemoSearchFacts?

    /** A posted note's text; null when there is no such note. */
    fun note(noteId: UUID): MemoNoteText?

    /** The posted notes of [memoId], oldest first. */
    fun notes(memoId: UUID): List<MemoNoteText>

    /** [fileId] as a searchable memo file; null when it is not one (any more): removed, or not a memo's file. */
    fun file(fileId: UUID): MemoFileFacts?

    /** The searchable files of [memoId]: its document and its present attachments. */
    fun files(memoId: UUID): List<MemoFileFacts>
}

/**
 * Memo data the search index follows changed in this transaction. Published once per memo, note or file, just before the
 * transaction commits (so listeners read the final state and write in the same transaction); ids only.
 */
sealed interface MemoSearchEvent : TenantScopedEvent {
    val memoId: UUID
}

/** The memo's details changed (or it was created). */
data class MemoChanged(
    override val tenantId: UUID,
    override val memoId: UUID,
) : MemoSearchEvent

/** A note was posted. */
data class MemoNotePosted(
    override val tenantId: UUID,
    override val memoId: UUID,
    val noteId: UUID,
) : MemoSearchEvent

/** A memo file's level changed, or it left the memo (an attachment removed). */
data class MemoFileChanged(
    override val tenantId: UUID,
    override val memoId: UUID,
    val fileId: UUID,
) : MemoSearchEvent
