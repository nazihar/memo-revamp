package com.memo.workflow

import com.memo.common.context.Actor
import com.memo.common.context.ActorContext
import com.memo.workflow.engine.ForumDecided
import com.memo.workflow.engine.ForumDeclined
import com.memo.workflow.internal.TaskQueryStore
import com.memo.workflow.internal.WorkflowRunner
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Items waiting for a forum (ADR-0058): the forum module resumes them with the forum's decision, recorded by the signed-in
 * forum officer, after its own checks; other modules ask which forum an item waits on. There is no workflow endpoint for
 * these. Inside the caller's tenant transaction.
 */
@Service
class WorkflowForums(
    private val runner: WorkflowRunner,
    private val tasks: TaskQueryStore,
    private val targets: List<EscalationTargets>,
) {
    /**
     * The forum each of [subjectIds] waits on, named by the forum module; a forum it no longer lists keeps its key as its
     * name. Subjects that wait on none are absent.
     */
    fun waitingOn(
        subjectType: String,
        subjectIds: Collection<UUID>,
    ): Map<UUID, EscalationTarget> {
        val keys = tasks.waitingForums(subjectType, subjectIds)
        if (keys.isEmpty()) return emptyMap()
        val known = targets.flatMap { it.list() }.associateBy { it.key }
        return keys.mapValues { (_, key) -> known[key] ?: EscalationTarget(key, key, key) }
    }

    /** The names of the forums escalation steps may name, by key (ADR-0056); empty without the forum module. */
    fun forumNames(): Map<String, String> = targets.flatMap { it.list() }.associate { it.key to it.name }

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
