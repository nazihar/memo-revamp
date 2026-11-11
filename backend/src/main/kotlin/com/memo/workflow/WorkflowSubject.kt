package com.memo.workflow

import com.memo.common.events.TenantScopedEvent
import io.swagger.v3.oas.annotations.media.Schema
import java.util.UUID

/**
 * What the engine cannot know about the thing it routes (ADR-0034), implemented by the owning module once per subject
 * type and registered as a Spring bean (for example the memo in E01). Every call runs inside the action's tenant
 * transaction.
 */
interface WorkflowSubject {
    /** Stable key, for example `memo`: lower case, dots and dashes. */
    val type: String

    /** Which of [users] may not hold a task on [subjectId] now, and why (inactive account, clearance); absent means they may. */
    fun ineligible(
        subjectId: UUID,
        users: Collection<UUID>,
    ): Map<UUID, BlockedReason>

    /**
     * What the action applies to, bound to the action row and to the round it starts (ids and hashes only, at most 4 KiB as
     * JSON): for memos, the document version and its SHA-256 (ADR-0036).
     */
    fun snapshot(subjectId: UUID): Map<String, Any?>

    /** What lists show of [subjectIds], in one query per page (absent ids are shown as unknown). */
    fun summaries(subjectIds: Collection<UUID>): Map<UUID, SubjectSummary> = emptyMap()

    /**
     * Called before the instance row is locked, for every action: a subject that keeps its own rows locks them here, so
     * every action takes the subject's lock before the instance's and two actions cannot deadlock.
     */
    fun lock(subjectId: UUID) {}

    /** Called before an accepted action is written; throw to veto it (the whole action rolls back). */
    fun beforeAction(
        subjectId: UUID,
        kind: ActionKind,
    ) {}
}

/** Where an instance is now, returned by every [Workflows] call. */
data class WorkflowView(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val instanceId: UUID,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val subjectType: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val subjectId: UUID,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val status: InstanceStatus,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val round: Int,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val currentAssignees: List<UUID>,
    /** The open task, while the instance runs (one at a time in sequential rounds). */
    val openTaskId: UUID?,
    /** Set while the open task waits for reassignment (invariant 12). */
    val openTaskBlocked: BlockedReason?,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val version: Long,
)

/** A subject as lists show it; [classificationRank] decides whether a reader may see the [title] (design E01 §5). */
data class SubjectSummary(
    val reference: String,
    val title: String,
    val classificationRank: Int,
)

/** What an instance routes: the subject's type and id. */
data class SubjectRef(
    val type: String,
    val id: UUID,
)

/** The engine refused the command; [reason] is the engine's refusal name (the web layer maps it to a Problem type). */
class WorkflowRefusedException(
    val reason: String,
) : RuntimeException("Workflow action refused: $reason")

/** An action was taken on an instance (ids only, ADR-0017); subjects update their own state from it in the same transaction. */
data class WorkflowActionTaken(
    override val tenantId: UUID,
    val instanceId: UUID,
    val subjectType: String,
    val subjectId: UUID,
    val actionId: UUID,
    val kind: ActionKind,
    val round: Int,
    val status: InstanceStatus,
) : TenantScopedEvent

/** A task was created; [blocked] set means it waits for a tenant admin to reassign it. */
data class WorkflowTaskCreated(
    override val tenantId: UUID,
    val instanceId: UUID,
    val subjectType: String,
    val subjectId: UUID,
    val taskId: UUID,
    val assigneeId: UUID,
    val blocked: BlockedReason?,
    /** The action that created it: SUBMIT, APPROVE (next step), RESUBMIT or REASSIGN. */
    val action: ActionKind,
) : TenantScopedEvent

/** A task was completed, cancelled or reassigned away from [assigneeId]. */
data class WorkflowTaskClosed(
    override val tenantId: UUID,
    val instanceId: UUID,
    val subjectType: String,
    val subjectId: UUID,
    val taskId: UUID,
    val assigneeId: UUID,
    val closure: TaskClosure,
    /** The action that closed it. */
    val action: ActionKind,
) : TenantScopedEvent
