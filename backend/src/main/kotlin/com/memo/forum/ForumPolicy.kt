package com.memo.forum

/**
 * The roles a member can hold on a forum (ADR-0056). A role is what the charter says; whether the member votes is a
 * separate flag that defaults from the role ([votesByDefault]).
 */
enum class ForumRole(
    val votesByDefault: Boolean,
    /** Whether the role ever counts towards quorum (observers and standing invitees never do, ADR-0057). */
    val countable: Boolean,
) {
    CHAIR(votesByDefault = true, countable = true),
    VICE_CHAIR(votesByDefault = true, countable = true),
    SECRETARY(votesByDefault = false, countable = true),
    MEMBER(votesByDefault = true, countable = true),
    STANDING_INVITEE(votesByDefault = false, countable = false),
    OBSERVER(votesByDefault = false, countable = false),
}

/**
 * What a decision means to the system (ADR-0056). Tenants label each kind as they like; what a kind does to a memo
 * waiting for the forum is fixed in code (Phase 4 E02), so a label can never change meaning.
 */
enum class OutcomeKind {
    APPROVED,
    APPROVED_WITH_CONDITIONS,
    REJECTED,
    DEFERRED,
    RETURNED,
    REFERRED,
    NOTED,
    WITHDRAWN,
}

/** A member attribute a forum type records, for example `independent`; quorum composition rules read them. */
data class MemberAttribute(
    val key: String,
    val label: String,
)

/** An outcome a forum type uses, with its tenant label and whether a decision of this kind gets a resolution number. */
data class Outcome(
    val kind: OutcomeKind,
    val label: String,
    val resolution: Boolean,
)

/** Terms of office (ADR-0056): a default length, a soft limit on consecutive terms, and reminder days before a term ends. */
data class TermPolicy(
    val termMonths: Int? = null,
    val maxTerms: Int? = null,
    val reminderDays: List<Int> = DEFAULT_REMINDER_DAYS,
)

/** How long meeting records of the forum are kept; stored only, acted on in Phase 7. */
data class ForumRetention(
    val years: Int,
    val from: String = "MEETING",
)

/** Six weeks and one day before a term ends, as BoardPro defaults (research §3.1). */
private const val SIX_WEEKS = 42
val DEFAULT_REMINDER_DAYS: List<Int> = listOf(SIX_WEEKS, 1)
