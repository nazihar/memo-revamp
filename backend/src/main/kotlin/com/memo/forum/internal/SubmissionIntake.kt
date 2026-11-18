package com.memo.forum.internal

import com.memo.audit.AuditLog
import com.memo.common.web.refuseIf
import com.memo.forum.ForumStatus
import com.memo.workflow.WorkflowEscalatedToForum
import com.memo.workflow.WorkflowInstanceEnded
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Submissions come and go with their workflow items (ADR-0059): an escalation queues one in the escalating transaction
 * (an inactive forum refuses the action that led there); an item that ends while waiting closes its submission.
 */
@Component
class SubmissionIntake(
    private val store: SubmissionStore,
    private val forums: ForumStore,
    private val support: SubmissionSupport,
    private val notifier: SubmissionNotifier,
    private val audit: AuditLog,
) {
    @EventListener
    fun onEscalated(event: WorkflowEscalatedToForum) {
        val forum = forums.findByKey(event.forumKey)
        refuseIf(forum == null || forum.status != ForumStatus.ACTIVE, "FORUM_INACTIVE") {
            "The committee ${event.forumKey} is not active; the item cannot be sent to it."
        }
        checkNotNull(forum)
        val author = event.initiatorId ?: actor()
        val id =
            store.insert(
                SubmissionRow(
                    id = UUID(0, 0),
                    forumId = forum.id,
                    subjectType = event.subjectType,
                    subjectId = event.subjectId,
                    instanceId = event.instanceId,
                    stepId = event.waitingStepId,
                    previousId = store.latest(forum.id, event.subjectType, event.subjectId)?.id,
                    purpose = if (event.waitingStepId != null) SubmissionPurpose.DECISION else SubmissionPurpose.INFORMATION,
                    resolutionSought = null,
                    presenterId = author,
                    status = SubmissionStatus.QUEUED,
                    targetDate = null,
                    submittedBy = author,
                    submittedAt = store.now().toInstant(),
                    triagedBy = null,
                    triagedAt = null,
                    reason = null,
                    outcome = null,
                    decidedAt = null,
                    remindedAt = null,
                    version = 0,
                ),
            )
        val row = checkNotNull(store.find(id))
        audit.record("forum.submission.created", ForumSubmissions.OBJECT_TYPE, id.toString(), after = ForumSubmissions.audited(row))
        notifier.received(forum, row)
        support.changed(row)
    }

    /** An item withdrawn or cancelled while waiting closes its open submissions; pass-through ones stay for information. */
    @EventListener
    fun onEnded(event: WorkflowInstanceEnded) {
        store.openOf(event.instanceId).filter { it.waits }.forEach { row ->
            if (store.move(row, SubmissionStatus.WITHDRAWN, row.version)) {
                audit.record(
                    "forum.submission.withdrawn",
                    ForumSubmissions.OBJECT_TYPE,
                    row.id.toString(),
                    after = mapOf("status" to event.status.name),
                )
                support.changed(row)
            }
        }
    }
}
