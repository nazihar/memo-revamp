package com.memo.workflow

import com.memo.common.events.TenantScopedEvent
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

    /** Called before an accepted action is written; throw to veto it (the whole action rolls back). */
    fun beforeAction(
        subjectId: UUID,
        kind: ActionKind,
    ) {}
}

/** Where an instance is now, returned by every [Workflows] call. */
data class WorkflowView(
    val instanceId: UUID,
    val subjectType: String,
    val subjectId: UUID,
    val status: InstanceStatus,
    val round: Int,
    val currentAssignees: List<UUID>,
    /** The open task, while the instance runs (one at a time in sequential rounds). */
    val openTaskId: UUID?,
    val version: Long,
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
) : TenantScopedEvent
