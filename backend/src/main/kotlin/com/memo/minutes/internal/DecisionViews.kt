package com.memo.minutes.internal

import com.memo.forum.OutcomeKind
import com.memo.minutes.DecisionState
import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** A person a decision names, with their display name. */
data class DecisionPerson(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val id: UUID,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val name: String,
)

data class DecisionDissentResponse(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val person: DecisionPerson,
    /** The reason they asked to be recorded, if any. */
    val reason: String?,
)

data class DecisionVoteResponse(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val mode: VoteMode,
    val votesFor: Int?,
    val votesAgainst: Int?,
    val votesAbstain: Int?,
)

/**
 * An item's decision as the viewer may see it (design E06 §1.9): in full for whoever sees the item in full (and
 * `forum.read` on unrestricted items); a person recused from the item who sees its line gets the outcome, the text, the
 * conditions and the validity, with [viewerRecused], and never the vote, the names or the notes. Officers also get who
 * recorded it and whether attendance was corrected since.
 */
data class DecisionResponse(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val id: UUID,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val itemId: UUID,
    val itemNumber: String?,
    /** The decision's version, for If-Match on a correction (also the ETag). */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val version: Long,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val seq: Int,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val state: DecisionState,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val outcome: OutcomeKind,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val outcomeLabel: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val resolution: Boolean,
    val text: String?,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val conditions: List<String>,
    val validUntil: LocalDate?,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val decidedAt: Instant,
    /** Recorded under E05's override: to be ratified at a quorate meeting (and not ratified yet: [toBeRatified]). */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val ratificationRequired: Boolean,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val toBeRatified: Boolean,
    /** The viewer declared an interest in the item: "You declared an interest in this item and took no part." */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val viewerRecused: Boolean,
    /** Its resolution number in force ("BRC/2026/014"), given when the meeting is marked as held or at once after. */
    val resolutionNumber: String? = null,
    val resolutionId: UUID? = null,
    /** In full only, from here on. */
    val deferTo: DeferTarget? = null,
    val referToForumId: UUID? = null,
    val referReason: String? = null,
    val vote: DecisionVoteResponse? = null,
    val dissent: List<DecisionDissentResponse>? = null,
    val abstentions: List<DecisionPerson>? = null,
    val tookNoPart: List<DecisionPerson>? = null,
    val ratifiesDecisionId: UUID? = null,
    val consentBatchId: UUID? = null,
    val snapshotId: UUID? = null,
    /** Officers only. */
    val recordedBy: DecisionPerson? = null,
    val recordedAt: Instant? = null,
    /** Officers only: attendance was corrected after the decision and its quorum would now read differently. */
    val drifted: Boolean? = null,
)

data class MeetingDecisionsResponse(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val meetingId: UUID,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val decisions: List<DecisionResponse>,
)

/** One entry of a decision's history (design E06 §1.1): what was recorded, and for a correction why. */
data class DecisionEntryResponse(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val seq: Int,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val outcome: OutcomeKind,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val outcomeLabel: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val resolution: Boolean,
    val text: String?,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val conditions: List<String>,
    val validUntil: LocalDate?,
    val deferTo: DeferTarget?,
    val referToForumId: UUID?,
    val referReason: String?,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val vote: DecisionVoteResponse,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val dissent: List<DecisionDissentResponse>,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val abstentions: List<DecisionPerson>,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val tookNoPart: List<DecisionPerson>,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val snapshotId: UUID,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val ratificationRequired: Boolean,
    val ratifiesDecisionId: UUID?,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val decidedAt: Instant,
    val consentBatchId: UUID?,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val correction: Boolean,
    val reason: String?,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val recordedBy: DecisionPerson,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val recordedAt: Instant,
)

data class DecisionHistoryResponse(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val decisionId: UUID,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val itemId: UUID,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val entries: List<DecisionEntryResponse>,
)

/** The secretary's notes on an item: the latest version (seq 0 and empty before the first save). */
data class ItemNoteResponse(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val itemId: UUID,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val seq: Int,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val text: String,
    val savedBy: DecisionPerson?,
    val savedAt: Instant?,
)

/** A decision of the committee taken without a quorum and not ratified yet: ids, numbers and the outcome only. */
data class ToBeRatifiedResponse(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val decisionId: UUID,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val meetingId: UUID,
    val meetingLabel: String?,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val itemId: UUID,
    val itemNumber: String?,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val outcomeLabel: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val decidedAt: Instant,
)
