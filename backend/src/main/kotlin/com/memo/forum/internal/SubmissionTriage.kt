package com.memo.forum.internal

import com.memo.audit.AuditLog
import com.memo.common.concurrency.ConcurrentModificationException
import com.memo.common.web.FieldProblem
import com.memo.common.web.refuseIf
import com.memo.forum.ForumStatus
import com.memo.workflow.ForumOutcome
import com.memo.workflow.WorkflowForums
import org.springframework.stereotype.Service
import java.time.LocalDate
import java.util.UUID

/** The cover sheet a secretary keeps (design E02 §3). */
data class CoverSheet(
    val purpose: SubmissionPurpose,
    val resolutionSought: String?,
    val presenterId: UUID?,
    val targetDate: LocalDate?,
)

/**
 * A secretary's triage (ADR-0059): accept, defer, return for more information, decline or refer, and the cover sheet.
 * Return and decline resume the waiting item through the workflow (ADR-0058); a referral carries it to another forum.
 */
@Service
class SubmissionTriage(
    private val store: SubmissionStore,
    private val forums: ForumStore,
    private val support: SubmissionSupport,
    private val workflow: WorkflowForums,
    private val notifier: SubmissionNotifier,
    private val audit: AuditLog,
) {
    fun edit(
        forumId: UUID,
        id: UUID,
        version: Long,
        sheet: CoverSheet,
    ): SubmissionRow {
        val row = support.triageable(forumId, id, emptySet())
        val text = sheet.resolutionSought?.trim()?.ifEmpty { null }
        requireNoProblems(listOfNotNull(support.tooLong("resolutionSought", text), support.pastTarget(sheet.targetDate)))
        val after =
            row.copy(
                purpose = sheet.purpose,
                resolutionSought = text,
                presenterId = sheet.presenterId,
                targetDate = sheet.targetDate,
            )
        if (!store.edit(after, version)) throw ConcurrentModificationException("submission $id", version)
        return support.done("forum.submission.updated", row, notify = false)
    }

    fun accept(
        forumId: UUID,
        id: UUID,
        version: Long,
        targetDate: LocalDate?,
    ): SubmissionRow {
        val row = support.triageable(forumId, id, setOf(SubmissionStatus.QUEUED, SubmissionStatus.DEFERRED))
        requireNoProblems(listOfNotNull(support.pastTarget(targetDate)))
        val fields = SubmissionStore.triaged(actor(), store.now(), null) + SubmissionStore.target(targetDate ?: row.targetDate)
        support.move(row, SubmissionStatus.ACCEPTED, version, fields)
        return support.done("forum.submission.accepted", row)
    }

    fun defer(
        forumId: UUID,
        id: UUID,
        version: Long,
        reason: String?,
        targetDate: LocalDate?,
    ): SubmissionRow {
        val row = support.triageable(forumId, id, setOf(SubmissionStatus.QUEUED, SubmissionStatus.ACCEPTED))
        val text = support.requiredReason(reason)
        requireNoProblems(listOfNotNull(support.pastTarget(targetDate)))
        val fields = SubmissionStore.triaged(actor(), store.now(), text) + SubmissionStore.target(targetDate)
        support.move(row, SubmissionStatus.DEFERRED, version, fields)
        return support.done("forum.submission.deferred", row)
    }

    /** Back to its author for more information: the waiting item returns to its initiator (ADR-0058). */
    fun returnForInformation(
        forumId: UUID,
        id: UUID,
        version: Long,
        reason: String?,
    ): SubmissionRow {
        val row = support.triageable(forumId, id, emptySet())
        val text = support.requiredReason(reason)
        support.move(row, SubmissionStatus.RETURNED, version, SubmissionStore.triaged(actor(), store.now(), text))
        row.stepId?.let { workflow.decided(row.instanceId, it, ForumOutcome.RETURNED, text) }
        return support.done("forum.submission.returned", row)
    }

    /** Not for this forum: the waiting item returns to its initiator with the reason (ADR-0058). */
    fun decline(
        forumId: UUID,
        id: UUID,
        version: Long,
        reason: String?,
    ): SubmissionRow {
        val row = support.triageable(forumId, id, emptySet())
        val text = support.requiredReason(reason)
        support.move(row, SubmissionStatus.DECLINED, version, SubmissionStore.triaged(actor(), store.now(), text))
        row.stepId?.let { workflow.declined(row.instanceId, it, text) }
        return support.done("forum.submission.declined", row)
    }

    /** To another forum: this submission closes as REFERRED and a new one, with the same waiting step, opens there. */
    fun refer(
        forumId: UUID,
        id: UUID,
        version: Long,
        toForumId: UUID,
        reason: String?,
    ): SubmissionRow {
        val row = support.triageable(forumId, id, emptySet())
        val text = support.requiredReason(reason)
        val target = forums.find(toForumId)?.takeIf { it.status == ForumStatus.ACTIVE }
        requireNoProblems(listOfNotNull(FieldProblem("forumId", "unknown", "No such active committee.").takeIf { target == null }))
        refuseIf(toForumId == forumId, "SAME_FORUM") { "Choose another committee." }
        support.move(row, SubmissionStatus.REFERRED, version, SubmissionStore.triaged(actor(), store.now(), text))
        val referred =
            checkNotNull(store.find(store.insert(row.copy(forumId = toForumId, previousId = row.id, status = SubmissionStatus.QUEUED))))
        audit.record(
            "forum.submission.created",
            ForumSubmissions.OBJECT_TYPE,
            referred.id.toString(),
            after = ForumSubmissions.audited(referred),
        )
        notifier.received(checkNotNull(target), referred)
        return support.done("forum.submission.referred", row)
    }
}
