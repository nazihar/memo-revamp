package com.memo.search

import com.memo.search.internal.decision.DecisionRegisterAccess
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** How much of a decision the viewer sees in the register (ADR-0069, design E08 §1.3). */
enum class RegisterLevel {
    /** Every column the viewer may see. */
    FULL,

    /** The restricted line: committee, meeting, date, item number, number and its state, and the reason. */
    LINE,
}

/** Why a row is a restricted line, in words for the viewer (design E08 §1.3). */
enum class LineReason {
    /** "You declared an interest in this item." */
    RECUSED,

    /** "Restricted to named members" (or a hidden title, or a members-only section, for oversight). */
    RESTRICTED,
}

/** The register's orders (design E08 §1.4). NUMBER lists one committee's numbered decisions in number order. */
enum class DecisionSort { RELEVANCE, NEWEST, OLDEST, NUMBER }

/** The exception filters (design E08 §1.4); every one given must hold. They match FULL rows only. */
enum class DecisionFlag { WITH_CONDITIONS, WITH_DISSENT, TO_BE_RATIFIED, CORRECTED, VALIDITY_PASSED }

/** Where a page ended, for the next one (keyset, design E08 §1.4): the values of the order's columns on its last row. */
data class DecisionCursor(
    val decisionId: UUID,
    val meetingDate: LocalDate? = null,
    val decidedAt: Instant? = null,
    val score: Float? = null,
    val period: String? = null,
    val sequence: Long? = null,
)

/**
 * What to look for in the register (design E08 §1.4). Empty sets mean "any". [text] is matched against FULL rows only;
 * a number in it is looked up on FULL and LINE rows (a memo number on FULL rows). The API (E08 T2) checks the inputs'
 * limits and that [forumIds] are in the viewer's scope before calling.
 */
data class DecisionQuery(
    val text: String = "",
    /** The last word, matched as a prefix (3+ letters or digits), for search as you type. */
    val prefix: String? = null,
    val forumIds: Set<UUID> = emptySet(),
    val forumTypeIds: Set<UUID> = emptySet(),
    /** Meeting dates, inclusive. */
    val from: LocalDate? = null,
    val to: LocalDate? = null,
    val outcomes: Set<String> = emptySet(),
    val resolutionsOnly: Boolean = false,
    /** RECORDED, NUMBERED, RELEASED, FINAL, and VOID (a void number). */
    val states: Set<String> = emptySet(),
    val memoTypeIds: Set<UUID> = emptySet(),
    val flags: Set<DecisionFlag> = emptySet(),
    val sort: DecisionSort = DecisionSort.NEWEST,
    val cursor: DecisionCursor? = null,
    val limit: Int = DEFAULT_PAGE,
) {
    companion object {
        const val DEFAULT_PAGE = 50
    }
}

/**
 * One row of the register as the viewer may see it. A [RegisterLevel.LINE] row carries no title, outcome, text,
 * conditions, memo, dates beyond the meeting's or flags: they are null. [decidedAt] and [entrySeq] are on every row for
 * the cursor and the export's hash only (E08 T2, T3), never shown on a line.
 */
data class RegisterRow(
    val decisionId: UUID,
    val level: RegisterLevel,
    /** Set on a LINE. */
    val reason: LineReason?,
    val forumId: UUID,
    val forumTypeId: UUID,
    val meetingId: UUID,
    val meetingLabel: String?,
    val meetingDate: LocalDate,
    val itemId: UUID,
    val itemNumber: String?,
    /** RECORDED, NUMBERED, RELEASED or FINAL. */
    val state: String,
    val resolutionId: UUID?,
    val resolutionNumber: String?,
    /** IN_FORCE or VOID. */
    val resolutionState: String?,
    val numberPeriod: String?,
    val numberSequence: Long?,
    val decidedAt: Instant,
    val entrySeq: Int,
    val score: Float?,
    val title: String? = null,
    val outcomeKind: String? = null,
    val outcomeLabel: String? = null,
    val isResolution: Boolean? = null,
    val text: String? = null,
    val conditions: List<String>? = null,
    val validUntil: LocalDate? = null,
    val hasDissent: Boolean? = null,
    val voteMode: String? = null,
    val ratificationRequired: Boolean? = null,
    val ratifiedByDecisionId: UUID? = null,
    val corrected: Boolean? = null,
    val finalAt: Instant? = null,
    val submissionId: UUID? = null,
    val memoId: UUID? = null,
    val memoTypeId: UUID? = null,
    val memoNumber: String? = null,
)

/** Counts over the matching rows under the same rule: committees and years count FULL and LINE rows, outcomes FULL only. */
data class DecisionFacets(
    val forums: Map<UUID, Long>,
    val years: Map<Int, Long>,
    val outcomes: Map<String, Long>,
)

/** One page of the register, with the total (up to 10,000; [capped] above it, or when text candidates hit their cap). */
data class DecisionPage(
    val rows: List<RegisterRow>,
    val total: Int,
    val capped: Boolean,
    val facets: DecisionFacets,
    val next: DecisionCursor?,
)

/** The committees a viewer's register covers (design E08 §1.3); null from [DecisionSearch.scope] when they have none. */
data class RegisterScope(
    /** Every committee of the tenant (`forum.read` or `forums.manage`). */
    val all: Boolean,
    /** Where they are a secretary, the chair, or the vice-chair with no chair in office (empty when [all] via manage). */
    val officerForums: Set<UUID>,
    /** Where they hold a seat in office today: chair, vice-chair or member. */
    val memberForums: Set<UUID>,
    val manager: Boolean,
    val reader: Boolean,
)

/**
 * The decision register for other modules and the register's API (ADR-0069, design E08 §1.3–§1.4): the signed-in actor's
 * scope, and a search under the one rule with their parameters computed from live state (roles and seats in office today,
 * clearance, `forum.read`, `forums.manage`, and the items they are recused from right now). Inside a tenant transaction.
 */
@Component
class DecisionSearch(
    private val access: DecisionRegisterAccess,
) {
    /** The actor's scope; null when the register is not for them (former members, guests, attendees, memo readers). */
    fun scope(): RegisterScope? = access.scope()

    /** One page of the register for the actor (an empty page when they have no scope). */
    fun find(query: DecisionQuery): DecisionPage = access.find(query)
}
