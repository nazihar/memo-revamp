package com.memo.minutes.internal

import com.memo.forum.OutcomeKind
import java.time.LocalDate
import java.util.UUID

/** What the release sends to a decision's submission through `ForumDecisions` (ADR-0065 "Back to the memo"). */
sealed interface ReleaseCommand {
    /** The outcome the decision row records as released (`released_kind`). */
    val kind: OutcomeKind

    /** DECIDED with [outcome]: the memo waiting at the forum step resumes with [remark]. */
    data class Decide(
        val outcome: OutcomeKind,
        val remark: String,
    ) : ReleaseCommand {
        override val kind: OutcomeKind get() = outcome
    }

    /** DEFERRED, off the agenda, for a meeting on or after [targetDate] (null: the next one); the memo keeps waiting. */
    data class Defer(
        val reason: String,
        val targetDate: LocalDate?,
    ) : ReleaseCommand {
        override val kind: OutcomeKind get() = OutcomeKind.DEFERRED
    }

    /** REFERRED to [forumId], with a new linked submission there; the memo keeps waiting, now for that committee. */
    data class Refer(
        val forumId: UUID,
        val reason: String,
    ) : ReleaseCommand {
        override val kind: OutcomeKind get() = OutcomeKind.REFERRED
    }
}

/**
 * The decision as the release sends it (ADR-0065's table, design E06 §1.4). Pure:
 *  - APPROVED, APPROVED_WITH_CONDITIONS, NOTED, REJECTED, RETURNED → decided with that outcome;
 *  - WITHDRAWN (by its presenter, at the meeting) → decided RETURNED, "Withdrawn at the meeting. …";
 *  - DEFERRED → deferred to the named meeting's date, the date, or the next meeting;
 *  - REFERRED → referred to the other committee with the reason;
 *  - a submission whose memo does not wait (sent "for information") → decided NOTED whatever the outcome.
 * The remark carries the outcome's label, the resolution number and text and the conditions, cut at the workflow's 4,000
 * characters with "(see the resolution)".
 */
object ReleaseMapping {
    /** The workflow's longest remark. */
    const val MAX_REMARK = 4000

    /** The forum's longest reason for a deferral. */
    const val MAX_REASON = 2000

    const val SEE_RESOLUTION = "(see the resolution)"
    const val WITHDRAWN = "Withdrawn at the meeting."

    /** What a decision of the committee is, as the release reads it. */
    data class Released(
        val kind: OutcomeKind,
        val label: String,
        val text: String?,
        val conditions: List<String>,
        /** DEFERRED: the date of the meeting or the date it is deferred to; null for "the next meeting". */
        val deferDate: LocalDate? = null,
        /** REFERRED: the committee and why. */
        val referToForumId: UUID? = null,
        val referReason: String? = null,
    )

    fun command(
        decision: Released,
        number: String?,
        waits: Boolean,
        meetingLabel: String?,
    ): ReleaseCommand {
        val remark = remark(decision, number)
        if (!waits) return ReleaseCommand.Decide(OutcomeKind.NOTED, remark)
        return when (decision.kind) {
            OutcomeKind.WITHDRAWN -> {
                ReleaseCommand.Decide(OutcomeKind.RETURNED, remark)
            }

            OutcomeKind.DEFERRED -> {
                val at = meetingLabel?.let { " at $it" } ?: " at the meeting"
                val reason = listOfNotNull("${decision.label}$at.", decision.text).joinToString(" ")
                ReleaseCommand.Defer(cut(reason, MAX_REASON), decision.deferDate)
            }

            OutcomeKind.REFERRED -> {
                val reason = decision.referReason?.takeIf { it.isNotBlank() } ?: "${decision.label}."
                ReleaseCommand.Refer(checkNotNull(decision.referToForumId) { "a referral names its committee" }, cut(reason, MAX_REASON))
            }

            else -> {
                ReleaseCommand.Decide(decision.kind, remark)
            }
        }
    }

    /**
     * "{label}. Resolution {number}: {text} Conditions: 1. … 2. …" ("Withdrawn at the meeting." first for a withdrawal),
     * at most [MAX_REMARK] characters.
     */
    fun remark(
        decision: Released,
        number: String?,
    ): String {
        val head = if (decision.kind == OutcomeKind.WITHDRAWN) "${decision.label}. $WITHDRAWN" else "${decision.label}."
        val body =
            when {
                number != null -> "Resolution $number:" + (decision.text?.let { " $it" } ?: "")
                else -> decision.text
            }
        val conditions =
            decision.conditions
                .takeIf { it.isNotEmpty() }
                ?.mapIndexed { i, c -> "${i + 1}. $c" }
                ?.joinToString(" ", prefix = "Conditions: ")
        return cut(listOfNotNull(head, body, conditions).joinToString(" "), MAX_REMARK)
    }

    /** [text] at most [max] characters, ending with [SEE_RESOLUTION] when it was cut. */
    fun cut(
        text: String,
        max: Int,
    ): String {
        if (text.length <= max) return text
        return text.take(max - SEE_RESOLUTION.length - 1).trimEnd() + " " + SEE_RESOLUTION
    }
}
