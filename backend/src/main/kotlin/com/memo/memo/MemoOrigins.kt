package com.memo.memo

import io.swagger.v3.oas.annotations.media.Schema
import java.util.UUID

/** How a record of another module is tied to a memo (design E07 §1.11). */
enum class MemoOriginKind {
    /** The memo was raised from the record ("Raised from action BRC A14/2026"). */
    RAISED_FROM,

    /** The memo was linked to the record afterwards as its follow-up. */
    LINKED_TO,
}

/**
 * A record another module ties to a memo, as the memo's reader may see it: what it is and its reference, with
 * [recordId] only when the reader may open the record itself (others see the reference as text, without a link).
 */
data class MemoOriginLabel(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val kind: MemoOriginKind,
    /** What the record is: `ACTION` (an action item of a committee). */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val recordType: String,
    /** "BRC A14/2026". */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val reference: String,
    /** Whose record it is: "Board Risk Committee". */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val owner: String,
    /** The record's id, when the reader may open it. */
    val recordId: UUID? = null,
)

/**
 * Where a memo came from, for the memo view (design E07 §1.11, §2.3): a port another module implements (the actions
 * module, for follow-up memos), so the memo module never depends on it. Asked for the signed-in reader of the memo,
 * inside the tenant transaction; the implementation decides what that reader may see of its records.
 */
interface MemoOrigins {
    /** The records tied to [memoId] that the signed-in reader may know of, oldest first. */
    fun labels(memoId: UUID): List<MemoOriginLabel>
}

/** No records are tied to memos: the memo module's answer when no module provides [MemoOrigins]. Not a bean. */
object NoMemoOrigins : MemoOrigins {
    override fun labels(memoId: UUID): List<MemoOriginLabel> = emptyList()
}
