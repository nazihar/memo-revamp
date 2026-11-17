package com.memo.memo

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

/** Memo facts for the search index, implemented by the memo module. Callers run inside a tenant transaction. */
interface MemoSearchSource {
    /** The facts of [memoId]; null when there is no such memo in the current tenant. */
    fun facts(memoId: UUID): MemoSearchFacts?

    /** Up to [limit] memo ids after [after] in id order (for backfill). */
    fun ids(
        after: UUID?,
        limit: Int,
    ): List<UUID>
}
