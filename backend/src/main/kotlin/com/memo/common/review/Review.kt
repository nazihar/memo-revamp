package com.memo.common.review

import com.memo.common.web.FieldProblem
import com.memo.common.web.refuseIf
import java.util.UUID

/**
 * The states of configuration that a second administrator reviews before it applies (maker-checker, AGENTS §3.5,
 * ADR-0043): a draft is edited and submitted; a version in review is published or sent back to draft; a published
 * version is retired when a newer one is published.
 */
enum class ReviewState {
    DRAFT,
    IN_REVIEW,
    PUBLISHED,
    RETIRED,
    ;

    /** Whether the version is still being worked on (at most one per reviewed object). */
    val open: Boolean get() = this == DRAFT || this == IN_REVIEW
}

/**
 * The maker-checker rules every reviewed object shares (ADR-0043). Pure: the owning module keeps its own versions and
 * guarded moves, and calls these before moving. Refusals answer 409 with a reason; remark problems are field problems the
 * module reports in its own validation answer.
 */
object ReviewRules {
    const val MAX_REMARK = 4000

    /** Refuses (`WRONG_STATE`) unless the version is in [expected]. */
    fun requireState(
        actual: ReviewState,
        expected: ReviewState,
        what: String,
    ) = refuseIf(actual != expected, "WRONG_STATE") { "Only a ${label(expected)} can be $what." }

    /** Refuses (`SAME_PERSON`) when the reviewer submitted the version themselves. */
    fun requireOtherReviewer(
        submittedBy: UUID?,
        reviewer: UUID,
    ) = refuseIf(submittedBy == reviewer, "SAME_PERSON") { "Someone other than the submitter has to review it." }

    /** The remark as stored: trimmed, null when empty. */
    fun remark(text: String?): String? = text?.trim()?.ifEmpty { null }

    /** What is wrong with a review remark, if anything; sending back needs one, approving does not. */
    fun remarkProblem(
        text: String?,
        required: Boolean,
    ): FieldProblem? {
        val remark = remark(text)
        return when {
            remark == null && required -> FieldProblem("remark", "REQUIRED", "Say why the version goes back.")
            remark != null && remark.length > MAX_REMARK -> FieldProblem("remark", "TOO_LONG", "At most $MAX_REMARK characters.")
            else -> null
        }
    }

    private fun label(state: ReviewState) =
        when (state) {
            ReviewState.DRAFT -> "draft"
            ReviewState.IN_REVIEW -> "version in review"
            ReviewState.PUBLISHED -> "published version"
            ReviewState.RETIRED -> "retired version"
        }
}
