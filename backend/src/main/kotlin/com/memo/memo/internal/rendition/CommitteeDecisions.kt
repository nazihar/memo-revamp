package com.memo.memo.internal.rendition

import com.memo.workflow.ActionEntry
import com.memo.workflow.ActionKind

/**
 * A committee's decisions on a memo as its final record prints them (ADR-0053, ADR-0065): the FORUM_DECIDED actions of
 * its workflow with the committee's name, the outcome in the committee's words, the resolution number and the meeting,
 * and the label of their remark on the note sheet. Pure.
 */
object CommitteeDecisions {
    /** A committee's outcomes in words (ADR-0056's kinds), when the action does not carry the committee's own label. */
    private val OUTCOMES =
        mapOf(
            "APPROVED" to "Approved",
            "APPROVED_WITH_CONDITIONS" to "Approved with conditions",
            "NOTED" to "Noted",
            "REJECTED" to "Rejected",
            "RETURNED" to "Returned",
        )

    /** The lines of [decisions] (FORUM_DECIDED actions); [names] gives the committees' names by key, read once if needed. */
    fun lines(
        decisions: List<ActionEntry>,
        names: () -> Map<String, String>,
    ): List<CommitteeDecisionLine> {
        val byKey by lazy(names)
        return decisions.map { a ->
            val forum = a.details["forum"] as? String
            val outcome = a.details["outcome"] as? String
            CommitteeDecisionLine(
                round = a.round,
                committee = forum?.let { byKey[it] ?: it } ?: "The committee",
                outcome = (a.details["outcomeLabel"] as? String) ?: OUTCOMES[outcome] ?: outcome.orEmpty(),
                resolution = a.details["resolution"] as? String,
                meeting = a.details["meetingLabel"] as? String,
                at = a.occurredAt,
            )
        }
    }

    /** An older action's remark on the note sheet: "Approve (remark)"; a committee's decision says so. */
    fun remarkLabel(a: ActionEntry): String =
        if (a.kind == ActionKind.FORUM_DECIDED) {
            "The committee's decision (remark)"
        } else {
            "${a.kind.name.lowercase().replaceFirstChar(Char::uppercase)} (remark)"
        }
}
