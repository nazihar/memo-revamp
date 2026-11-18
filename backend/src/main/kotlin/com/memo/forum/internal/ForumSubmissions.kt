package com.memo.forum.internal

import com.memo.common.authz.ForbiddenException
import com.memo.common.authz.Permission
import com.memo.common.events.TenantScopedEvent
import com.memo.common.web.refuseIf
import com.memo.workflow.ForumOutcome
import com.memo.workflow.WorkflowForums
import org.springframework.stereotype.Service
import java.util.UUID

/** A submission changed (ADR-0059); E03 listens to place accepted items on agendas. */
data class ForumSubmissionChanged(
    override val tenantId: UUID,
    val submissionId: UUID,
) : TenantScopedEvent

/**
 * Reading submissions and recording a forum's decision (ADR-0059): the queue for a forum's officers, one submission for
 * them and its author, and [decide], which resumes the waiting item. Intake is [SubmissionIntake]; triage
 * [SubmissionTriage].
 */
@Service
class ForumSubmissions(
    private val store: SubmissionStore,
    private val access: ForumAccess,
    private val support: SubmissionSupport,
    private val workflow: WorkflowForums,
) {
    /** The forum's queue in [statuses] (none: the open ones), for its officers. */
    fun queue(
        forumId: UUID,
        statuses: Set<SubmissionStatus>,
    ): List<SubmissionRow> {
        val forum = access.requireVisible(forumId)
        if (!support.officer(forum)) throw ForbiddenException(Permission.FORUMS_MANAGE)
        return store.queue(forum.id, statuses.ifEmpty { OPEN }, MAX_QUEUE)
    }

    /** One submission, for its author and presenter, and for the forum's officers. */
    fun get(
        forumId: UUID,
        id: UUID,
    ): SubmissionRow {
        val row = store.find(id)?.takeIf { it.forumId == forumId } ?: throw ForumNotFoundException("submission")
        val me = actorOrNull()
        if (me != null && (me == row.submittedBy || me == row.presenterId)) return row
        val forum = access.requireVisible(forumId)
        if (!support.officer(forum)) throw ForumNotFoundException("submission")
        return row
    }

    /**
     * The forum's decision (ADR-0058, ADR-0059): resumes the waiting item and closes the submission as DECIDED. E06 calls
     * it from recorded decisions; in E02 it serves tests and the demo. The caller has checked who may decide.
     */
    fun decide(
        id: UUID,
        outcome: ForumOutcome,
        remark: String?,
    ): SubmissionRow {
        val row = store.find(id) ?: throw ForumNotFoundException("submission")
        refuseIf(row.status != SubmissionStatus.ACCEPTED && row.status != SubmissionStatus.QUEUED, "WRONG_STATE") {
            "Only a queued or accepted submission can be decided."
        }
        support.move(row, SubmissionStatus.DECIDED, row.version, SubmissionStore.decided(outcome, actor(), store.now()))
        row.stepId?.let { workflow.decided(row.instanceId, it, outcome, remark) }
        return support.done("forum.submission.decided", row)
    }

    companion object {
        const val OBJECT_TYPE = "forum_submission"
        const val MAX_TEXT = 2000
        const val MAX_QUEUE = 500
        val OPEN = SubmissionStatus.entries.filter { it.open }.toSet()

        fun audited(row: SubmissionRow): Map<String, Any?> =
            mapOf(
                "forumId" to row.forumId.toString(),
                "subject" to "${row.subjectType}:${row.subjectId}",
                "instanceId" to row.instanceId.toString(),
                "stepId" to row.stepId?.toString(),
                "previousId" to row.previousId?.toString(),
                "purpose" to row.purpose.name,
                "status" to row.status.name,
                "targetDate" to row.targetDate?.toString(),
                "reason" to row.reason,
                "outcome" to row.outcome?.name,
            )
    }
}
