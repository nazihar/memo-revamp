package com.memo.meeting.internal

import com.memo.common.web.FieldProblem
import com.memo.common.web.refuseIf
import com.memo.meeting.DeclarationHow
import com.memo.meeting.DeclarationIntent
import com.memo.meeting.DeclarationState
import com.memo.meeting.MeetingStatus
import com.memo.meeting.RulingOutcome
import java.security.MessageDigest

enum class DeclarationEventKind { DECLARED, INTENT_CHANGED, RULED, WITHDRAWN }

/** What someone does to declarations, for the windows by the meeting's status (design E04 §1.1). */
enum class DeclarationAct {
    DECLARE,
    RECORD,
    INTENT,
    RULE,
    WITHDRAW,
    REVIEW,
    ;

    /** A secretary's correction after the meeting is held: a declaration made in the room, or one entered in error. */
    val correctable: Boolean get() = this == RECORD || this == WITHDRAW
}

/**
 * The pure rules of declarations (ADR-0062, design E04 §1.1, §1.2): the states each change leads to, the windows by the
 * meeting's status, and the lengths of what people write. The trigger on `meeting_declaration` repeats the transitions.
 */
object DeclarationRules {
    const val MAX_NATURE = 1000
    const val MAX_REASON = 500
    const val MAX_HOW_NOTE = 200
    const val MAX_RULING_NOTE = 500

    /** The text shown instead of a title the viewer may not see. */
    const val RESTRICTED = "Restricted item"

    private val OPEN = setOf(MeetingStatus.DRAFT, MeetingStatus.SCHEDULED, MeetingStatus.IN_PROGRESS)

    /** A new declaration's state: leaving is recused at once; asking the chair waits for a ruling. */
    fun initial(intent: DeclarationIntent): DeclarationState =
        if (intent == DeclarationIntent.LEAVE) DeclarationState.RECUSED else DeclarationState.AWAITING_RULING

    /**
     * The state after the declarant changes their intent to [intent]: asking the chair from RECUSED waits for a ruling;
     * choosing to leave from a pending ruling or from taking part is recused. Anything else is 409 `WRONG_STATE`.
     */
    fun afterIntent(
        state: DeclarationState,
        intent: DeclarationIntent,
    ): DeclarationState {
        val next =
            when (intent) {
                DeclarationIntent.ASK_CHAIR -> {
                    DeclarationState.AWAITING_RULING.takeIf { state == DeclarationState.RECUSED }
                }

                DeclarationIntent.LEAVE -> {
                    DeclarationState.RECUSED.takeIf { state == DeclarationState.AWAITING_RULING || state == DeclarationState.TAKING_PART }
                }
            }
        refuseIf(next == null, "WRONG_STATE") { "The declaration is ${state.name.lowercase()}: that does not change it." }
        return checkNotNull(next)
    }

    /** The state after the chair's [outcome]: from a pending ruling either; otherwise only a change. */
    fun afterRuling(
        state: DeclarationState,
        outcome: RulingOutcome,
    ): DeclarationState {
        val next = if (outcome == RulingOutcome.LEAVE) DeclarationState.RECUSED else DeclarationState.TAKING_PART
        refuseIf(state == DeclarationState.WITHDRAWN || state == next, "WRONG_STATE") {
            "The declaration is ${state.name.lowercase()}: the ruling does not change it."
        }
        return next
    }

    /** Withdrawing: from any state but WITHDRAWN. */
    fun afterWithdrawal(state: DeclarationState): DeclarationState {
        refuseIf(state == DeclarationState.WITHDRAWN, "WRONG_STATE") { "The declaration is already withdrawn." }
        return DeclarationState.WITHDRAWN
    }

    /**
     * Whether [act] is open while the meeting is [status] (design E04 §1.1): until it is held, for everyone allowed to act;
     * once held and until the minutes are approved, only a secretary's correction ([secretary]); afterwards, and for a
     * cancelled meeting, nothing (409 `FROZEN`). True when the act is a correction.
     */
    fun window(
        status: MeetingStatus,
        act: DeclarationAct,
        secretary: Boolean,
    ): Boolean {
        val correction = status == MeetingStatus.HELD && act.correctable && secretary
        refuseIf(status !in OPEN && !correction, "FROZEN") {
            if (status == MeetingStatus.HELD) {
                "The meeting has been held: only a secretary can record or withdraw a declaration, as a correction."
            } else {
                "The meeting's declarations are closed."
            }
        }
        return correction
    }

    /** The nature: 1 to [MAX_NATURE] characters after trimming (422 `NATURE_REQUIRED`, `LENGTH`). */
    fun nature(text: String?): Pair<String, FieldProblem?> {
        val clean = text?.trim().orEmpty()
        val problem =
            when {
                clean.isEmpty() -> FieldProblem("nature", "NATURE_REQUIRED", "Say what the interest is.")
                clean.length > MAX_NATURE -> FieldProblem("nature", "LENGTH", "At most $MAX_NATURE characters.")
                else -> null
            }
        return clean to problem
    }

    /** A required reason (a withdrawal, a correction): 1 to [MAX_REASON] characters (422 `REASON_REQUIRED`, `LENGTH`). */
    fun reason(text: String?): Pair<String?, FieldProblem?> {
        val clean = text?.trim()?.ifEmpty { null }
        val problem =
            when {
                clean == null -> FieldProblem("reason", "REASON_REQUIRED", "Say why.")
                clean.length > MAX_REASON -> FieldProblem("reason", "LENGTH", "At most $MAX_REASON characters.")
                else -> null
            }
        return clean to problem
    }

    /** How a declaration or ruling recorded for someone was given: required, and not IN_APP or SYSTEM (422 `HOW_REQUIRED`). */
    fun how(
        how: DeclarationHow?,
        field: String = "how",
    ): FieldProblem? =
        FieldProblem(field, "HOW_REQUIRED", "Say how it was given: at the meeting, in person, by email or by phone.")
            .takeIf { how == null || !how.recorded }

    /** An optional text of at most [max] characters at [field], trimmed (422 `LENGTH`). */
    fun optional(
        field: String,
        text: String?,
        max: Int,
    ): Pair<String?, FieldProblem?> {
        val clean = text?.trim()?.ifEmpty { null }
        return clean to FieldProblem(field, "LENGTH", "At most $max characters.").takeIf { (clean?.length ?: 0) > max }
    }

    /** The SHA-256 of business text, hex: the audit carries it instead of the text (design E04 §5). */
    fun sha256(text: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
