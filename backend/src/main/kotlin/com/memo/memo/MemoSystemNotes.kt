package com.memo.memo

import com.memo.memo.internal.MemoNotes
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Entries other modules write on a memo's note sheet (design E06 §2, ADR-0065), in the caller's tenant transaction, for
 * the signed-in actor: append-only like every entry, never correctable, audited as `memo.note.posted`. The caller has
 * decided who may write them.
 */
@Component
class MemoSystemNotes(
    private val notes: MemoNotes,
) {
    /**
     * "The committee's decision was corrected on {date}: …" — the committee corrected the wording, conditions or vote of a
     * decision already sent to the memo (the outcome itself never changes once sent). Its id.
     */
    fun forumDecisionCorrected(
        memoId: UUID,
        text: String,
    ): UUID = notes.system(memoId, FORUM_DECISION_CORRECTED, text)

    companion object {
        /** The note's kind on the sheet (`actionKind`). */
        const val FORUM_DECISION_CORRECTED = "FORUM_DECISION_CORRECTED"
    }
}
