package com.memo.actions.internal

import com.memo.forum.DueChange
import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** A person on an action: their id and name. */
data class ActionPerson(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val id: UUID,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val name: String,
)

/** A position owner: "Head of Treasury". */
data class ActionPosition(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val id: UUID,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val title: String,
)

/**
 * Who owns an action (design E07 §1.3): a person, or a position with whoever holds the owner's task now ("Head of
 * Treasury (A. Karim)"); the holder may be a substitute.
 */
data class ActionOwnerResponse(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val kind: OwnerKind,
    val person: ActionPerson? = null,
    val position: ActionPosition? = null,
    /** Who holds the owner's task now; null for a draft or while the task waits for an administrator. */
    val holder: ActionPerson? = null,
)

/**
 * Where an action comes from, as the viewer may see it (design E07 §1.14): the label carries the meeting's number and the
 * item's, never the item's title; [itemTitle] is there only when the viewer may read it.
 */
data class ActionSourceResponse(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val kind: ActionSource,
    /** "BRC 3/2026, item 5 — decision, condition 2". */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val label: String,
    /** DIRECT: what the secretary noted ("Chair's instruction of 3 December"). */
    val note: String? = null,
    val meetingId: UUID? = null,
    val itemId: UUID? = null,
    val itemNumber: String? = null,
    /** Only when the viewer may read the item's title (the item rule, ADR-0061). */
    val itemTitle: String? = null,
    val decisionId: UUID? = null,
    /** The condition line of the decision, counted from 0. */
    val conditionIndex: Int? = null,
)

/** What the viewer may do with the action now (design E07 §4). */
data class ActionAbilities(
    /** Edit the draft, or the issued action with a reason. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val edit: Boolean,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val issue: Boolean,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val discard: Boolean,
    /** Post an update: the owner, a contributor, or a secretary for the owner ([updateOnBehalf]). */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val update: Boolean = false,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val updateOnBehalf: Boolean = false,
    /** Say how it is going with an update (the owner). */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val confidence: Boolean = false,
    /** "I have started". */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val start: Boolean = false,
    /** Ask for a new date (the owner, when the committee agrees dates). */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val requestDue: Boolean = false,
    /** Agree to or decline the pending request. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val decideDue: Boolean = false,
    /** Change the date directly (a secretary, or the owner when the committee lets them). */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val changeDue: Boolean = false,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val overrideRag: Boolean = false,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val changeOwner: Boolean = false,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val cancel: Boolean = false,
    /** Cancel with the risk accepted without saying how (the chair). */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val acceptRisk: Boolean = false,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val reopen: Boolean = false,
)

/**
 * A warning for the secretary, in words (flow AJ2): the owner declared an interest in the item (`OWNER_RECUSED`: they will
 * see this action, but not the item), or a person named is not cleared or not active (`NOT_CLEARED`).
 */
data class ActionWarning(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val code: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val userId: UUID,
)

/** An action item as the viewer may see it (design E07 §3, `GET /actions/{id}`). */
data class ActionResponse(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val id: UUID,
    /** The ETag; If-Match on every change. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val version: Long,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val forumId: UUID,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val committee: String,
    /** "BRC A14/2026"; null for a draft ("Draft action"). */
    val reference: String? = null,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val status: ActionStatus,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val priority: ActionPriority,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val title: String,
    val description: String? = null,
    /** What the owner is told of the source. */
    val context: String? = null,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val classificationId: UUID,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val source: ActionSourceResponse,
    val owner: ActionOwnerResponse? = null,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val contributors: List<ActionPerson>,
    val dueDate: LocalDate? = null,
    /** Set at issue; never changes. */
    val originalDueDate: LocalDate? = null,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val dueRevisions: Int,
    /** The open step's name ("Verification"), while the workflow runs. */
    val stepName: String? = null,
    val issuedAt: Instant? = null,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val createdAt: Instant,
    /** The viewer is recused from the source item and sees the action because they are involved in it. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val viewerRecused: Boolean,
    /** For the secretaries only. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val warnings: List<ActionWarning>,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val can: ActionAbilities,
    /** Red, amber or green, in words (design E07 §1.5). */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val rag: ActionRagResponse,
    /** The owner's word with their latest update. */
    val confidence: Confidence? = null,
    val lastUpdateAt: Instant? = null,
    /** The committee type's rule for new dates today: whether the owner asks or changes it. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val dueChange: DueChange,
    /** "New date requested: 20 January". */
    val pendingRequest: DueRequestResponse? = null,
    val cancelKind: CancelKind? = null,
    val cancelReason: String? = null,
    /** SUPERSEDED: the action that replaces it. */
    val supersededBy: UUID? = null,
    /** When it was closed or cancelled. */
    val closedAt: Instant? = null,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val reopenCount: Int = 0,
)

/** An action in a list: the meeting's actions and "My actions". */
data class ActionSummary(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val id: UUID,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val version: Long,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val forumId: UUID,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val committee: String,
    val reference: String? = null,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val status: ActionStatus,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val priority: ActionPriority,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val title: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val sourceKind: ActionSource,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val sourceLabel: String,
    val meetingId: UUID? = null,
    val itemId: UUID? = null,
    val decisionId: UUID? = null,
    val conditionIndex: Int? = null,
    val owner: ActionOwnerResponse? = null,
    val dueDate: LocalDate? = null,
    val originalDueDate: LocalDate? = null,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val dueRevisions: Int,
    val stepName: String? = null,
    /** Red, amber or green in words, from the SQL reading of the rule; without the override's reason. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val rag: ActionRagResponse,
    /** The owner's word with their latest update. */
    val confidence: Confidence? = null,
    val lastUpdateAt: Instant? = null,
)

/**
 * The tracker's totals per colour (flow AN: "▲ Overdue 3 ● Due soon or at risk 2 ✓ On track 7 ◐ To verify 2"), from the
 * same rule and predicate as its rows, under every filter but the colour ones (`rag`, `overdue`).
 */
data class ActionTotals(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val red: Int,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val amber: Int,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val green: Int,
    /** Blue: done, to be verified. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val toVerify: Int,
    /** Grey: closed or cancelled. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val closed: Int,
    /** Direct drafts not yet issued (secretaries and `forums.manage`). */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val drafts: Int,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val all: Int,
)

/**
 * The key the tracker prints with this committee type's numbers (design E07 §1.5): "Amber: due within 10 working days, at
 * risk, or no update for 20 working days".
 */
data class ActionRagKey(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val amberWorkingDays: Int,
    /** Null: the committee type has no "no recent update" rule. */
    val staleWorkingDays: Int? = null,
)

/** What the viewer may do on the tracker. */
data class TrackerAbilities(
    /** "New action": a direct draft (secretaries and `forums.manage`). */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val raise: Boolean,
    /** "Export" (secretaries, the chair, `forums.manage` and `forum.read`). */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val export: Boolean,
)

/** A page of the committee's action tracker (design E07 §3, flow AN). */
data class ForumActionsResponse(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val forumId: UUID,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val committee: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val items: List<ActionSummary>,
    /** Pass it as `cursor` (with the same filters and sort) for the next page; null on the last. */
    val nextCursor: String? = null,
    /** The rows under every filter, colour included. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val matching: Int,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val totals: ActionTotals,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val key: ActionRagKey,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val can: TrackerAbilities,
)

/** A meeting's actions as the viewer may see them (drafts for its secretaries and `forums.manage`). */
data class MeetingActionsResponse(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val meetingId: UUID,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val actions: List<ActionSummary>,
    /** The drafts among [actions]: "3 draft actions not sent". */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val drafts: Int,
)

/** The tabs of "My actions" (design E07 §3): T3 has MINE; TO_VERIFY and CONTRIBUTING come with closure (T5). */
enum class MyActionsTab { MINE, }

/** The counts of "My actions" for the inbox badge, from the same predicate as the rows. */
data class MyActionCounts(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val mine: Int,
)

/** A page of "My actions", by due date. */
data class MyActionsResponse(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val tab: MyActionsTab,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val items: List<ActionSummary>,
    val nextCursor: String? = null,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val counts: MyActionCounts,
)

/** How issuing one draft of a bulk request went. */
data class IssueResult(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val actionId: UUID,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val issued: Boolean,
    val reference: String? = null,
    /** Why not: a problem code (`REQUIRED`, `OWNER_NOT_CLEARED`, `WRONG_STATE`, …). */
    val reason: String? = null,
)

/** `POST /meetings/{id}/actions/issue`: one result per draft the actor may issue, each in its own transaction. */
data class IssueResultsResponse(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val results: List<IssueResult>,
)

/** An override of an action's colour, as it is shown: "set by {name}: {reason}". */
data class ActionRagOverride(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val rag: RagColour,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val reason: String,
    val by: ActionPerson? = null,
    /** Until this day; null: until the next update. */
    val until: LocalDate? = null,
)

/** An action's red, amber or green, never a colour alone: the [word] always comes with it (design E07 §1.5). */
data class ActionRagResponse(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val colour: RagColour,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val word: RagWord,
    /** The override in force, on the action's page. */
    val override: ActionRagOverride? = null,
)

/** The owner's pending request for a new date. */
data class DueRequestResponse(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val id: UUID,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val newDate: LocalDate,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val reason: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val requestedBy: ActionPerson,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val requestedAt: Instant,
)

/** An evidence file on an entry: its name and size (read through the files endpoints by those who see the action). */
data class EvidenceFile(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val id: UUID,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val name: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val mediaType: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val sizeBytes: Long,
)

/**
 * One entry of the timeline (design E07 §1.6): an update with its confidence and evidence, a date asked for or changed,
 * a new owner, an override, an edit, a cancellation, a reopening, a source correction. [details] carries its ids, dates
 * and kinds; what people wrote is [text].
 */
data class ActionEntryResponse(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val seq: Int,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val kind: String,
    val actor: ActionPerson? = null,
    /** The owner a secretary wrote for, and how they heard. */
    val onBehalfOf: ActionPerson? = null,
    val how: How? = null,
    val text: String? = null,
    val confidence: Confidence? = null,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val files: List<EvidenceFile>,
    /** The earlier entry this one corrects ("Correction to the update of 3 December"). */
    val corrects: Int? = null,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val details: Map<String, Any?>,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val at: Instant,
)

/** An action's timeline, the newest first. */
data class ActionEntriesResponse(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val actionId: UUID,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val entries: List<ActionEntryResponse>,
)
