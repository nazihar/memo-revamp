package com.memo.minutes.internal

import com.memo.common.web.RefusedException
import com.memo.forum.MinutesApproval

/** What moves the minutes along their path (design E06 §1.5, ADR-0066). */
enum class MinutesTransition {
    /** The secretary sends the draft to the chair (or vice-chair). */
    SEND_FOR_REVIEW,

    /** The reviewer agrees ("Ready to circulate"), or a secretary records it on their behalf. */
    AGREE,

    /** The reviewer sends the draft back with comments. */
    RETURN,

    /** The secretary circulates: after the reviewer agreed, or with a reason when nobody is able to review. */
    CIRCULATE,

    /** The secretary takes circulated minutes back to the draft, with a reason. */
    WITHDRAW,

    /** A new comment deadline (never earlier than tomorrow). */
    CHANGE_DEADLINE,

    /** Readers comment; secretaries ask more people for comments. */
    COMMENT,

    /** A secretary answers a suggestion. */
    RESPOND,

    /** BY_CIRCULATION: the secretary ends the comment period early, with a reason. */
    END_CIRCULATION,

    /** BY_CIRCULATION: the sweep finds the deadline passed, or the last reader answered. */
    CLOSE_CIRCULATION,

    /** AT_NEXT_MEETING: the next meeting approves them on its "Minutes of the previous meeting" item. */
    APPROVED_AT_MEETING,

    /** AT_NEXT_MEETING: that decision is corrected so it no longer approves them. */
    APPROVAL_UNDONE,

    /** The chair (or vice-chair) confirms them with a fresh step-up. */
    CONFIRM,

    /** A decision of the meeting is corrected (T3's window): minutes in review go back to the draft. */
    DECISION_CORRECTED,
}

/**
 * The minutes' lifecycle (design E06 §1.5), pure: where each step may be taken and where it leads. Forbidden steps answer
 * 409 `FROZEN` once the minutes are approved and `WRONG_STATE` otherwise; the steps of one approval mode are
 * `WRONG_STATE` under the other. [MinutesStateMachineTest] covers every pair.
 */
object MinutesStateMachine {
    /**
     * The stage after [step] from [stage] under [approval]. AGREE leads to CIRCULATED when the draft may circulate now
     * ([ready]: no tracked changes or comments left), else back to the DRAFT with the agreement standing, for the secretary
     * to accept the reviewer's suggested changes and circulate.
     */
    fun next(
        stage: MinutesStage,
        step: MinutesTransition,
        approval: MinutesApproval,
        ready: Boolean = true,
    ): MinutesStage {
        refuseIf(stage == MinutesStage.APPROVED, "FROZEN") { "The minutes have been approved." }
        val rule = RULES.getValue(step)
        val allowed = stage in rule.from && (rule.mode == null || rule.mode == approval)
        if (!allowed) throw RefusedException("WRONG_STATE", message(stage, step))
        return when {
            step == MinutesTransition.AGREE && !ready -> MinutesStage.DRAFT
            else -> rule.to ?: stage
        }
    }

    /** Where a step may be taken from, under which mode only (null: both), and where it leads (null: the same stage). */
    private data class Rule(
        val from: Set<MinutesStage>,
        val to: MinutesStage?,
        val mode: MinutesApproval? = null,
    )

    private val RULES: Map<MinutesTransition, Rule> =
        mapOf(
            MinutesTransition.SEND_FOR_REVIEW to Rule(setOf(MinutesStage.DRAFT), MinutesStage.CHAIR_REVIEW),
            MinutesTransition.AGREE to Rule(setOf(MinutesStage.CHAIR_REVIEW), MinutesStage.CIRCULATED),
            MinutesTransition.RETURN to Rule(setOf(MinutesStage.CHAIR_REVIEW), MinutesStage.DRAFT),
            MinutesTransition.CIRCULATE to Rule(setOf(MinutesStage.DRAFT), MinutesStage.CIRCULATED),
            MinutesTransition.WITHDRAW to Rule(setOf(MinutesStage.CIRCULATED), MinutesStage.DRAFT),
            MinutesTransition.CHANGE_DEADLINE to Rule(setOf(MinutesStage.CIRCULATED), null),
            MinutesTransition.COMMENT to Rule(setOf(MinutesStage.CIRCULATED), null),
            MinutesTransition.RESPOND to Rule(setOf(MinutesStage.CIRCULATED, MinutesStage.AWAITING_CHAIR), null),
            MinutesTransition.END_CIRCULATION to
                Rule(setOf(MinutesStage.CIRCULATED), MinutesStage.AWAITING_CHAIR, MinutesApproval.BY_CIRCULATION),
            MinutesTransition.CLOSE_CIRCULATION to
                Rule(setOf(MinutesStage.CIRCULATED), MinutesStage.AWAITING_CHAIR, MinutesApproval.BY_CIRCULATION),
            MinutesTransition.APPROVED_AT_MEETING to
                Rule(setOf(MinutesStage.CIRCULATED), MinutesStage.AWAITING_CHAIR, MinutesApproval.AT_NEXT_MEETING),
            MinutesTransition.APPROVAL_UNDONE to
                Rule(setOf(MinutesStage.AWAITING_CHAIR), MinutesStage.DRAFT, MinutesApproval.AT_NEXT_MEETING),
            MinutesTransition.CONFIRM to Rule(setOf(MinutesStage.AWAITING_CHAIR), MinutesStage.APPROVED),
            MinutesTransition.DECISION_CORRECTED to
                Rule(setOf(MinutesStage.CHAIR_REVIEW, MinutesStage.CIRCULATED, MinutesStage.AWAITING_CHAIR), MinutesStage.DRAFT),
        )

    /** Whether [step] may be taken from [stage] under [approval] (the page's buttons). */
    fun allows(
        stage: MinutesStage,
        step: MinutesTransition,
        approval: MinutesApproval,
    ): Boolean = runCatching { next(stage, step, approval) }.isSuccess

    private fun message(
        stage: MinutesStage,
        step: MinutesTransition,
    ): String =
        when (step) {
            MinutesTransition.SEND_FOR_REVIEW -> {
                "Only a draft is sent to the chair."
            }

            MinutesTransition.AGREE, MinutesTransition.RETURN -> {
                "The minutes are not with the chair for review."
            }

            MinutesTransition.CIRCULATE -> {
                "Only a draft the chair agreed is circulated."
            }

            MinutesTransition.WITHDRAW, MinutesTransition.CHANGE_DEADLINE, MinutesTransition.COMMENT -> {
                "The minutes are not circulated for comments."
            }

            MinutesTransition.RESPOND -> {
                "The comment period is over."
            }

            MinutesTransition.END_CIRCULATION, MinutesTransition.CLOSE_CIRCULATION -> {
                "Only minutes circulated for approval by circulation end so."
            }

            MinutesTransition.APPROVED_AT_MEETING, MinutesTransition.APPROVAL_UNDONE -> {
                "These minutes are not approved at the next meeting."
            }

            MinutesTransition.CONFIRM -> {
                "The minutes are not waiting for the chair's approval."
            }

            MinutesTransition.DECISION_CORRECTED -> {
                "The minutes are a draft."
            }
        } + " (${stage.name})"

    private inline fun refuseIf(
        condition: Boolean,
        reason: String,
        message: () -> String,
    ) {
        if (condition) throw RefusedException(reason, message())
    }
}
