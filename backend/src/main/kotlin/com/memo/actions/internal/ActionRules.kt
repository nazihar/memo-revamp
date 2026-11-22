package com.memo.actions.internal

import com.memo.common.web.FieldProblem
import com.memo.common.web.RefusedException
import com.memo.meeting.MeetingStatus
import java.security.MessageDigest
import java.time.LocalDate
import java.util.UUID

/** Where an action comes from (design E07 §1.1). */
enum class ActionSource { DECISION, ITEM, MEETING, DIRECT }

/** Where an action stands (design E07 §1.4). Overdue is not a status: it is derived from the due date. */
enum class ActionStatus {
    DRAFT,
    OPEN,
    IN_PROGRESS,

    /** Done, to be verified. */
    DONE_PENDING,
    CLOSED,
    CANCELLED,
    ;

    /** Issued and not finished: the owner's work is still open. */
    val open: Boolean get() = this == OPEN || this == IN_PROGRESS || this == DONE_PENDING
}

/** Who owns an action: a person, or a position (whoever holds it, through the engine). */
enum class OwnerKind { PERSON, POSITION }

enum class ActionPriority { NORMAL, HIGH }

/** Why an action was cancelled (design E07 §1.10); DISCARDED is a draft that was never issued. */
enum class CancelKind { NO_LONGER_NEEDED, SUPERSEDED, RISK_ACCEPTED, DISCARDED, DUPLICATE }

/** The owner as a request names it: a person or a position, exactly one. */
data class OwnerInput(
    val kind: OwnerKind,
    val userId: UUID? = null,
    val positionId: UUID? = null,
)

/** An action's owner as stored. */
data class ActionOwner(
    val kind: OwnerKind,
    val userId: UUID?,
    val positionId: UUID?,
) {
    companion object {
        fun person(userId: UUID) = ActionOwner(OwnerKind.PERSON, userId, null)

        fun position(positionId: UUID) = ActionOwner(OwnerKind.POSITION, null, positionId)
    }
}

/** What the secretary writes on a draft (design E07 §1.1): every field but the source, which never changes. */
data class ActionFields(
    val title: String?,
    val description: String? = null,
    val context: String? = null,
    val owner: OwnerInput? = null,
    val contributors: List<UUID> = emptyList(),
    val dueDate: LocalDate? = null,
    val priority: ActionPriority = ActionPriority.NORMAL,
    /** Null: the source's level (the item's, the meeting's or the committee's). */
    val classificationId: UUID? = null,
    /** DIRECT only: "Chair's instruction of 3 December". */
    val sourceNote: String? = null,
)

/**
 * The pure rules of an action (design E07 §1.1, §1.4): the lengths and shapes of its fields, the due date, what issuing
 * needs, the level, the condition and the raise windows (the reference, the label and the due time are [ActionReferences]).
 * Every problem names its field and a stable code (`REQUIRED`, `LENGTH`, `TOO_MANY`, `SAME_PERSON`, `DUE_IN_PAST`,
 * `CLASSIFICATION`, `CONDITION`).
 */
object ActionRules {
    const val TITLE_MAX = 200
    const val DESCRIPTION_MAX = 4000
    const val CONTEXT_MAX = 2000
    const val SOURCE_NOTE_MAX = 300
    const val REASON_MAX = 500
    const val MAX_CONTRIBUTORS = 5

    /** Drafts are raised from a meeting while it runs or after it (design E07 §1.1 `RAISE_WINDOW`). */
    val RAISE_WINDOW = setOf(MeetingStatus.IN_PROGRESS, MeetingStatus.HELD, MeetingStatus.MINUTES_APPROVED)

    /** The fields' own problems: lengths, the owner's shape, the contributors' number and the owner among them. */
    fun fields(fields: ActionFields): List<FieldProblem> {
        val title = fields.title?.trim().orEmpty()
        val owner = fields.owner
        val contributors = fields.contributors
        val ownerUser = owner?.userId.takeIf { owner?.kind == OwnerKind.PERSON }
        return listOfNotNull(
            FieldProblem("title", "REQUIRED", "Say what is to be done.").takeIf { title.isEmpty() },
            FieldProblem("title", "LENGTH", "At most $TITLE_MAX characters.").takeIf { title.length > TITLE_MAX },
            length("description", fields.description, DESCRIPTION_MAX),
            length("context", fields.context, CONTEXT_MAX),
            length("sourceNote", fields.sourceNote, SOURCE_NOTE_MAX),
            owner?.let(::ownerShape),
            FieldProblem("contributors", "TOO_MANY", "At most $MAX_CONTRIBUTORS people besides the owner.")
                .takeIf { contributors.size > MAX_CONTRIBUTORS },
            FieldProblem("contributors", "DUPLICATE", "Name each person once.").takeIf { contributors.toSet().size < contributors.size },
            FieldProblem("contributors", "SAME_PERSON", "The owner is not also among the people involved.")
                .takeIf { ownerUser != null && ownerUser in contributors },
        )
    }

    /** A due date, when set, is today or later in the tenant's zone. */
    fun due(
        dueDate: LocalDate?,
        today: LocalDate,
    ): FieldProblem? =
        FieldProblem("dueDate", "DUE_IN_PAST", "Please enter a date on or after today.").takeIf { dueDate != null && dueDate < today }

    /** What issuing needs beyond a valid draft (`OWNER_REQUIRED`): an owner and a due date, not in the past. */
    fun issuable(
        owner: ActionOwner?,
        dueDate: LocalDate?,
        today: LocalDate,
    ): List<FieldProblem> =
        listOfNotNull(
            FieldProblem("owner", "REQUIRED", "Choose who owns the action.").takeIf { owner == null },
            FieldProblem("dueDate", "REQUIRED", "Choose when it is due.").takeIf { dueDate == null },
            due(dueDate, today),
        )

    /** The level of an action is its source's or higher, never lower (design E07 §1.1). */
    fun classification(
        chosenRank: Int?,
        sourceRank: Int,
    ): FieldProblem? =
        when {
            chosenRank == null -> FieldProblem("classificationId", "unknown", "No such classification level.")
            chosenRank < sourceRank -> FieldProblem("classificationId", "CLASSIFICATION", "An action is never below its source's level.")
            else -> null
        }

    /** A condition line of the decision's entry, counted from 0. */
    fun condition(
        index: Int?,
        conditions: Int,
    ): FieldProblem? =
        FieldProblem("conditionIndex", "CONDITION", "The decision has no such condition.").takeIf {
            index != null &&
                index !in 0 until conditions
        }

    /** 409 `WRONG_STATE` outside the raise window of the meeting's sources. */
    fun requireRaiseWindow(status: MeetingStatus) {
        if (status !in RAISE_WINDOW) {
            throw RefusedException(WRONG_STATE, "Actions are raised once the meeting is in progress, held or minuted.")
        }
    }

    /** 409 `WRONG_STATE` unless the action is still a draft. */
    fun requireDraft(status: ActionStatus) {
        if (status != ActionStatus.DRAFT) throw RefusedException(WRONG_STATE, "Only a draft action can be changed or issued this way.")
    }

    /** SHA-256 of what someone wrote, for the audit (never the text). */
    fun sha256(text: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    const val WRONG_STATE = "WRONG_STATE"
    const val RECUSED = "RECUSED"

    private fun length(
        field: String,
        value: String?,
        max: Int,
    ): FieldProblem? = FieldProblem(field, "LENGTH", "At most $max characters.").takeIf { value != null && value.length > max }

    private fun ownerShape(owner: OwnerInput): FieldProblem? =
        when (owner.kind) {
            OwnerKind.PERSON -> {
                FieldProblem("owner/userId", "REQUIRED", "Choose the person.").takeIf {
                    owner.userId == null ||
                        owner.positionId != null
                }
            }

            OwnerKind.POSITION -> {
                FieldProblem("owner/positionId", "REQUIRED", "Choose the position.").takeIf {
                    owner.positionId == null ||
                        owner.userId != null
                }
            }
        }
}
