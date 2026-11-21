package com.memo.minutes.internal

import com.memo.forum.OutcomeKind
import com.memo.minutes.ResolutionState
import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** The memo a resolution decided on, when the viewer reads it. */
data class ResolutionMemo(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val id: UUID,
    /** Its number; null until it is numbered. */
    val reference: String?,
)

/**
 * A line of a committee's resolution register (design E06 §1.9, flow AC1), in number order. A row whose item the viewer
 * does not see in full ([restricted]: recused, restricted to others, above their clearance) carries the number, its
 * state, the meeting and the item's number only.
 */
data class ResolutionEntryResponse(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val id: UUID,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val number: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val sequence: Long,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val state: ResolutionState,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val numberedAt: Instant,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val meetingId: UUID,
    val meetingLabel: String?,
    val meetingDate: LocalDate?,
    val itemNumber: String?,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val restricted: Boolean,
    /** In full only, from here on. */
    val itemTitle: String? = null,
    val outcome: OutcomeKind? = null,
    val outcomeLabel: String? = null,
    val memo: ResolutionMemo? = null,
    val voidedAt: Instant? = null,
    /** Why it is void (the correction's reason). */
    val voidReason: String? = null,
)

data class ResolutionRegisterResponse(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val forumId: UUID,
    /** The period shown; null when the committee has given no number yet. */
    val period: String?,
    /** The periods the committee has numbers or a counter in, the latest last. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val periods: List<String>,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val entries: List<ResolutionEntryResponse>,
    /** The cursor of the next page (older numbers); null on the last. */
    val next: String?,
)

/**
 * One resolution (design E06 §1.9, flow AC2) as the viewer may see it: whoever sees its item's decision in full reads all
 * of it; a person recused from the item who sees its line, and a reader of the memo it decided on, read the outcome, the
 * number, the text, the conditions and the validity, never the vote or the names (the decision's own page has them).
 */
data class ResolutionResponse(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val id: UUID,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val number: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val state: ResolutionState,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val numberedAt: Instant,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val forumId: UUID,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val forumName: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val meetingId: UUID,
    val meetingLabel: String?,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val meetingDate: LocalDate,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val decisionId: UUID,
    val itemNumber: String?,
    /** The item's title, when the viewer sees its line (not for a reader of the memo only). */
    val itemTitle: String?,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val outcome: OutcomeKind,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val outcomeLabel: String,
    val text: String?,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val conditions: List<String>,
    val validUntil: LocalDate?,
    /** When the memo moved on with it; null while it waits (or for an item without a memo). */
    val releasedAt: Instant?,
    val memo: ResolutionMemo?,
    /** The viewer declared an interest in the item. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val viewerRecused: Boolean,
    /** The viewer sees the item's decision in full (its page has the vote and the names). */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val full: Boolean,
    val voidedAt: Instant?,
    /** Why it is void: for those who see the decision in full. */
    val voidReason: String?,
)

/** The register check (design E06 §1.3, flow AC1 "Check the register"): "1 to 16 are all accounted for (15 in force, 1 void)". */
data class ResolutionCheckResponse(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val forumId: UUID,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val period: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val firstNumber: Long,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val lastNumber: Long,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val inForce: Int,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val void: Int,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val missing: List<Long>,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val missingCount: Long,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val duplicates: List<Long>,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val outOfRange: List<Long>,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val complete: Boolean,
)

/** Where a committee's numbers start in a period, set once before the first is given (go-live, ADR-0044). */
data class ResolutionCounterRequest(
    /** The first number to give, 1 to 999,999,999. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val next: Long,
)
