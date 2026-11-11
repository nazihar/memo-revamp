package com.memo.workflow

import com.memo.common.context.Actor
import com.memo.common.context.ActorContext
import com.memo.workflow.engine.Approve
import com.memo.workflow.engine.Reassign
import com.memo.workflow.engine.Reject
import com.memo.workflow.engine.Resubmit
import com.memo.workflow.engine.Return
import com.memo.workflow.engine.Withdraw
import com.memo.workflow.internal.WorkflowRunner
import com.memo.workflow.internal.WorkflowSettings
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * The workflow module's entry point (ADR-0034, ADR-0035). Every call runs inside the caller's tenant transaction, as the
 * signed-in user of the current unit of work: the instance is locked, the engine decides, and the outcome is written with
 * guarded updates, audited and published in that one transaction. Who may call (for example reassignment by a tenant
 * admin) is the caller's check; who may act on a task is the engine's. Refusals throw [WorkflowRefusedException];
 * concurrent changes throw a `CONFLICT` refusal (409).
 */
@Service
class Workflows(
    private val runner: WorkflowRunner,
    private val settings: WorkflowSettings,
) {
    /** Most approvers a route may have in this tenant (`workflow.route.max`); subjects check it before they start. */
    fun maxApprovers(): Int = settings.maxApprovers()

    /**
     * Round 1 on the newest published version of [definitionKey] (ADR-0038); [route] fills its route step, and must be
     * empty when the definition has none. The default is the built-in sequential approval of the route.
     */
    fun start(
        subjectType: String,
        subjectId: UUID,
        route: List<UUID>,
        remark: String? = null,
        definitionKey: String = SEQUENTIAL_APPROVAL,
    ): WorkflowView = runner.start(subjectType, subjectId, route, remark, currentUser(), definitionKey)

    fun approve(
        taskId: UUID,
        remark: String? = null,
    ): WorkflowView = runner.onTask(taskId, currentUser()) { actor -> Approve(actor, taskId, remark) }

    fun returnToInitiator(
        taskId: UUID,
        remark: String?,
    ): WorkflowView = runner.onTask(taskId, currentUser()) { actor -> Return(actor, taskId, remark) }

    fun reject(
        taskId: UUID,
        remark: String?,
    ): WorkflowView = runner.onTask(taskId, currentUser()) { actor -> Reject(actor, taskId, remark) }

    fun withdraw(
        instanceId: UUID,
        remark: String? = null,
    ): WorkflowView = runner.onInstance(instanceId, currentUser()) { actor -> Withdraw(actor, remark) }

    /** The next round, from the first approver of [route] (ADR-0035). */
    fun resubmit(
        instanceId: UUID,
        route: List<UUID>,
        remark: String? = null,
    ): WorkflowView = runner.onInstance(instanceId, currentUser()) { actor -> Resubmit(actor, route, remark) }

    /** Moves an open task to [newAssignee]; the caller has checked that the actor may reassign (design E01 §6). */
    fun reassign(
        taskId: UUID,
        newAssignee: UUID,
        reason: String?,
    ): WorkflowView = runner.onTask(taskId, currentUser()) { actor -> Reassign(actor, taskId, newAssignee, reason) }

    companion object {
        /** The built-in definition every tenant has: sequential approval of the initiator's route. */
        const val SEQUENTIAL_APPROVAL = "sequential-approval"
    }
}

private fun currentUser(): Actor.User =
    ActorContext.current().actor as? Actor.User ?: throw IllegalStateException("workflow actions need a signed-in user")
