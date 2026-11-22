package com.memo.actions.internal

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

/** What the viewer may do with the action now. */
data class ActionAbilities(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val edit: Boolean,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val issue: Boolean,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val discard: Boolean,
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
