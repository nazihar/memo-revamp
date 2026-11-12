package com.memo.memo.internal

import com.memo.common.authz.Clearances
import com.memo.common.web.FieldProblem
import com.memo.files.Files
import com.memo.identity.UserStatus
import com.memo.identity.UserSummaries
import com.memo.workflow.ActionKind
import com.memo.workflow.AttributeType
import com.memo.workflow.BlockedReason
import com.memo.workflow.InstanceStatus
import com.memo.workflow.WorkflowActionTaken
import com.memo.workflow.WorkflowSubject
import com.memo.workflow.WorkflowTaskCreated
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * The memo as the workflow's subject (ADR-0034, ADR-0036). An approver may hold a task only while active and cleared for
 * the memo's classification; otherwise the task is created blocked (invariant 12). Every action is bound to the
 * document version current at that moment, by id and SHA-256.
 */
@Component
class MemoWorkflowSubject(
    private val store: MemoStore,
    private val lists: MemoListStore,
    private val files: Files,
    private val users: UserSummaries,
    private val clearances: Clearances,
    private val interests: MemoInterestStore,
) : WorkflowSubject {
    override val type = TYPE

    /** People with a declared interest in the memo are never asked to decide on it (SOD-4, design E04 §4.4). */
    override fun interested(subjectId: UUID): Set<UUID> = interests.of(subjectId)

    override fun ineligible(
        subjectId: UUID,
        users: Collection<UUID>,
    ): Map<UUID, BlockedReason> {
        val memo = store.find(subjectId) ?: return emptyMap()
        val people = this.users.of(users.toSet())
        val ranks = clearances.rankOf(users.toSet())
        return users
            .associateWith { user ->
                when {
                    people[user]?.status != UserStatus.ACTIVE -> BlockedReason.INACTIVE
                    ranks.getValue(user) < memo.classificationRank -> BlockedReason.NO_CLEARANCE
                    else -> null
                }
            }.filterValues { it != null }
            .mapValues { it.value!! }
    }

    override fun summaries(subjectIds: Collection<UUID>) = lists.summaries(subjectIds)

    /** The memo row, so uploads, edits and every workflow action on one memo are serialised in the same order. */
    override fun lock(subjectId: UUID) {
        store.lock(subjectId)
    }

    override val attributes: Map<String, AttributeType> =
        mapOf("memoTypeId" to AttributeType.STRING, "classificationRank" to AttributeType.INT)

    override fun attributeValues(subjectId: UUID): Map<String, Any?> =
        store.find(subjectId)?.let { mapOf("memoTypeId" to it.typeId.toString(), "classificationRank" to it.classificationRank) }.orEmpty()

    override fun snapshot(subjectId: UUID): Map<String, Any?> {
        val version =
            store
                .find(subjectId)
                ?.documentFileId
                ?.let { files.info(it) }
                ?.currentVersionId
                ?.let { files.version(it) } ?: return emptyMap()
        return mapOf("documentVersionId" to version.id.toString(), "documentNumber" to version.number, "sha256" to version.sha256)
    }

    /** No round starts without a document to review. */
    override fun beforeAction(
        subjectId: UUID,
        kind: ActionKind,
    ) {
        if ((kind == ActionKind.SUBMIT || kind == ActionKind.RESUBMIT) && store.find(subjectId)?.documentFileId == null) {
            throw MemoRejectedException(listOf(FieldProblem("document", "required", "Upload the memo document first.")))
        }
    }

    companion object {
        const val TYPE = "memo"
    }
}

/** Keeps the memo in step with its workflow, in the action's transaction: status, round, and who takes part. */
@Component
class MemoWorkflowListener(
    private val store: MemoStore,
    private val clock: Clock,
) {
    @EventListener
    fun onAction(event: WorkflowActionTaken) {
        if (event.subjectType != MemoWorkflowSubject.TYPE) return
        store.applyWorkflow(event.subjectId, event.instanceId, event.round, STATUS.getValue(event.status), now())
    }

    /** Approvers become participants when their task is created, so they can open the memo from then on. */
    @EventListener
    fun onTask(event: WorkflowTaskCreated) {
        val assignee = event.assigneeId
        if (event.subjectType != MemoWorkflowSubject.TYPE || assignee == null) return
        store.addParticipant(event.subjectId, assignee, ParticipantKind.APPROVER, now())
    }

    private fun now() = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC)

    private companion object {
        val STATUS =
            mapOf(
                InstanceStatus.RUNNING to MemoStatus.IN_REVIEW,
                InstanceStatus.RETURNED to MemoStatus.RETURNED,
                InstanceStatus.APPROVED to MemoStatus.APPROVED,
                InstanceStatus.REJECTED to MemoStatus.REJECTED,
                InstanceStatus.WITHDRAWN to MemoStatus.WITHDRAWN,
                InstanceStatus.CANCELLED to MemoStatus.CANCELLED,
            )
    }
}
