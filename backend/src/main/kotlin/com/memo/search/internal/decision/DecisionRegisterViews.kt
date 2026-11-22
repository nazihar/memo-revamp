package com.memo.search.internal.decision

import com.fasterxml.jackson.annotation.JsonInclude
import com.memo.forum.OutcomeKind
import com.memo.search.DecisionFlag
import com.memo.search.DecisionSort
import com.memo.search.LineReason
import com.memo.search.RegisterLevel
import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** The register's states a search filters on (design E08 §1.4): the decision's, and VOID for a void resolution number. */
enum class RegisterState { RECORDED, NUMBERED, RELEASED, FINAL, VOID }

/**
 * The body of `POST /decision-register/search` (design E08 §1.4). A body, not a query string: words stay out of URLs and
 * logs (ADR-0054, threat model E05 I4). Empty or absent lists mean "any".
 */
data class DecisionSearchRequest(
    /** Words, a resolution number or a memo number: 1–200 characters, at most 12 words. */
    val text: String? = null,
    /** Committees of the viewer's scope (`GET /decision-register/scope`); another one is 422 `FORUM_OUT_OF_SCOPE`. */
    val forumIds: List<UUID>? = null,
    val forumTypeIds: List<UUID>? = null,
    /** Meeting dates, inclusive, at most 25 years apart. */
    val from: LocalDate? = null,
    val to: LocalDate? = null,
    /** Matches decisions the viewer sees in full only. */
    val outcomes: List<OutcomeKind>? = null,
    val resolutionsOnly: Boolean? = null,
    val states: List<RegisterState>? = null,
    /** Matches decisions the viewer sees in full only. */
    val memoTypeIds: List<UUID>? = null,
    /** Every flag given must hold; they match decisions the viewer sees in full only. */
    val flags: List<DecisionFlag>? = null,
    /** RELEVANCE (the default with words), NEWEST (the default without), OLDEST, or NUMBER (one committee). */
    val sort: DecisionSort? = null,
    /** The previous page's `nextCursor`, with the same search. */
    val cursor: String? = null,
    /** Rows a page, 1–50 (50 by default). */
    val limit: Int? = null,
)

/** One row of the register. A LINE carries the committee, meeting, item number, state and number only, and why. */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class DecisionRowView(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val decisionId: UUID,
    /** FULL, or LINE: a restricted line. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val level: RegisterLevel,
    /** On a LINE: RECUSED ("You declared an interest in this item") or RESTRICTED ("Restricted to named members"). */
    val reason: LineReason? = null,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val forumId: UUID,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val forumName: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val meetingId: UUID,
    /** "BRC 3/2026" */
    val meetingLabel: String? = null,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val meetingDate: LocalDate,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val itemId: UUID,
    val itemNumber: String? = null,
    /** RECORDED, NUMBERED, RELEASED or FINAL. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val state: String,
    val resolutionId: UUID? = null,
    val resolutionNumber: String? = null,
    /** IN_FORCE or VOID. */
    val resolutionState: String? = null,
    // The rest is on FULL rows only.
    val title: String? = null,
    val outcomeKind: String? = null,
    val outcomeLabel: String? = null,
    /** A resolution (numbered, or to be numbered), not a procedural decision. */
    val resolution: Boolean? = null,
    val text: String? = null,
    val conditions: List<String>? = null,
    val validUntil: LocalDate? = null,
    val hasDissent: Boolean? = null,
    val voteMode: String? = null,
    val ratificationRequired: Boolean? = null,
    val ratifiedByDecisionId: UUID? = null,
    val corrected: Boolean? = null,
    val decidedAt: Instant? = null,
    /** When the minutes were approved. */
    val finalAt: Instant? = null,
    val submissionId: UUID? = null,
    val memoId: UUID? = null,
    val memoTypeId: UUID? = null,
    val memoTypeName: String? = null,
    val memoNumber: String? = null,
)

data class ForumCountView(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val forumId: UUID,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val name: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val count: Long,
)

data class OutcomeCountView(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val outcome: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val count: Long,
)

data class YearCountView(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val year: Int,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val count: Long,
)

/** Counts over every matching row under the same rule: committees and years count FULL and LINE rows, outcomes FULL only. */
data class DecisionFacetsView(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val forums: List<ForumCountView>,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val outcomes: List<OutcomeCountView>,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val years: List<YearCountView>,
)

/** A page of the register: [total] exact up to 10,000, [capped] above it ("more than 10,000"). */
data class DecisionSearchView(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val rows: List<DecisionRowView>,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val total: Int,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val capped: Boolean,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val facets: DecisionFacetsView,
    /** The next page's cursor (sealed: opaque, for this search and person only); null on the last page. */
    val nextCursor: String? = null,
)

data class RegisterCommitteeView(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val id: UUID,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val name: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val shortName: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val typeId: UUID,
    /** A secretary, the chair, or the vice-chair with no chair (or forums.manage): sees every row in full, but recused ones. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val officer: Boolean,
    /** A seat in office today. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val member: Boolean,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val dissolved: Boolean,
)

data class NamedView(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val id: UUID,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val name: String,
)

/** What the viewer may filter the register by (design E08 §3), and what they may do with it. */
data class RegisterScopeView(
    /** Every committee for forum.read and forums.manage; otherwise those where they are an officer or hold a seat. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val committees: List<RegisterCommitteeView>,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val committeeTypes: List<NamedView>,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val outcomes: List<OutcomeKind>,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val states: List<RegisterState>,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val flags: List<DecisionFlag>,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val memoTypes: List<NamedView>,
    /** Officers, forums.manage and forum.read export the register (E08 T3); members read it. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val canExport: Boolean,
    /** The number check of E06's per-committee register: as [canExport]. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val canCheckNumbers: Boolean,
)
