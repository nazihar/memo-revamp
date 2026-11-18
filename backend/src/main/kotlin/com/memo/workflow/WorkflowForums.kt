package com.memo.workflow

import com.memo.common.context.Actor
import com.memo.common.context.ActorContext
import com.memo.workflow.engine.ForumDecided
import com.memo.workflow.engine.ForumDeclined
import com.memo.workflow.internal.WorkflowRunner
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Items waiting for a forum (ADR-0058): the forum module resumes them with the forum's decision, recorded by the signed-in
 * forum officer, after its own checks. There is no workflow endpoint for these. Inside the caller's tenant transaction.
 */
@Service
class WorkflowForums(
    private val runner: WorkflowRunner,
) {
    /** The forum decided on the item waiting at its step [stepId]. */
    fun decided(
        instanceId: UUID,
        stepId: UUID,
        outcome: ForumOutcome,
        remark: String?,
    ): WorkflowView = runner.onInstance(instanceId, officer()) { actor -> ForumDecided(actor, stepId, outcome, remark) }

    /** The forum will not consider the item waiting at [stepId]: back to its initiator with [reason]. */
    fun declined(
        instanceId: UUID,
        stepId: UUID,
        reason: String,
    ): WorkflowView = runner.onInstance(instanceId, officer()) { actor -> ForumDeclined(actor, stepId, reason) }

    private fun officer(): Actor.User =
        ActorContext.current().actor as? Actor.User ?: throw IllegalStateException("forum decisions need a signed-in user")
}
