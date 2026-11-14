package com.memo.memo.internal

import com.memo.audit.AuditLog
import com.memo.common.concurrency.ConcurrentModificationException
import com.memo.common.web.FieldProblem
import com.memo.common.web.refuseIf
import com.memo.docedit.DocumentEditing
import com.memo.workflow.RouteEntry
import com.memo.workflow.Workflows
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Sends memos through their approval (design E01 §3): the memo is the workflow's subject `memo`, and its status follows
 * the workflow's events ([MemoWorkflowListener]). The workflow audits each action. Inside a tenant transaction.
 */
@Service
class MemoReviews(
    private val memos: Memos,
    private val store: MemoStore,
    private val routes: MemoRoutes,
    private val workflows: Workflows,
    private val editing: DocumentEditing,
    private val types: MemoTypes,
    private val metadata: MemoMetadata,
    private val audit: AuditLog,
) {
    /**
     * Round 1: the memo needs a document, a valid route and its required details; the first approver gets the task. The
     * memo is pinned to its type's published version with its final metadata (ADR-0043): values for fields that version
     * no longer has are dropped and recorded.
     */
    fun submit(
        id: UUID,
        remark: String?,
    ): Memo {
        val memo = locked(id)
        refuseIf(memo.status != MemoStatus.DRAFT, "WRONG_STATE") { "Only a draft can be submitted." }
        notBeingEdited(memo)
        val type = types.get(memo.typeId)
        val version = types.published(memo.typeId)
        val route = route(memo, type)
        val fields = version?.settings?.fields.orEmpty()
        val keys = fields.map { it.key }.toSet()
        val checked = metadata.check(fields, memo.metadata.filterKeys { it in keys }, requireAll = true)
        val problems =
            listOfNotNull(
                FieldProblem("document", "required", "Upload the memo document first.").takeIf { memo.documentFileId == null },
                FieldProblem("typeId", "inactive", "This memo type is no longer used.").takeIf { !type.active || version == null },
            ) + checked.problems + problems(memo, type, route)
        if (problems.isNotEmpty()) throw MemoRejectedException(problems)
        val pinned = checkNotNull(version)
        if (!store.pin(id, memo.version, pinned.id, checked.values)) throw ConcurrentModificationException("memo $id", memo.version)
        audit.record(
            "memo.type_pinned",
            Memos.OBJECT_TYPE,
            id.toString(),
            after =
                mapOf(
                    "typeId" to type.id.toString(),
                    "version" to pinned.number,
                    "dropped" to (memo.metadata.keys - checked.values.keys).sorted(),
                ),
        )
        // The memo type's workflow (E03): new memos pin its newest published version.
        workflows.start(MemoWorkflowSubject.TYPE, id, emptyList(), remark, pinned.settings.definitionKey, route)
        return memos.get(id)
    }

    /** Round N+1 after a return (design E01 §3): from the first approver of the route as it is now; earlier approvals lapse. */
    fun resubmit(
        id: UUID,
        remark: String?,
    ): Memo {
        val memo = locked(id)
        refuseIf(memo.status != MemoStatus.RETURNED, "WRONG_STATE") { "Only a memo returned to you can be resubmitted." }
        notBeingEdited(memo)
        // The version the memo was submitted under, not today's (ADR-0043).
        val type = types.forMemo(memo)
        val route = route(memo, type)
        val problems = problems(memo, type, route)
        if (problems.isNotEmpty()) throw MemoRejectedException(problems)
        workflows.resubmit(checkNotNull(memo.workflowInstanceId), emptyList(), remark, route)
        return memos.get(id)
    }

    /** Ends the approval for good, in review or returned; the open task, if any, is cancelled. */
    fun withdraw(
        id: UUID,
        remark: String?,
    ): Memo {
        val memo = locked(id)
        refuseIf(memo.status != MemoStatus.IN_REVIEW && memo.status != MemoStatus.RETURNED, "WRONG_STATE") {
            "Only a memo in review or returned to you can be withdrawn."
        }
        workflows.withdraw(checkNotNull(memo.workflowInstanceId), remark)
        return memos.get(id)
    }

    /** Pulls the memo back before any approver acted (design E03 §4.4); it returns to the initiator to edit and resubmit. */
    fun recall(
        id: UUID,
        remark: String?,
    ): Memo {
        val memo = locked(id)
        refuseIf(memo.status != MemoStatus.IN_REVIEW, "WRONG_STATE") { "Only a memo in review can be recalled." }
        workflows.recall(checkNotNull(memo.workflowInstanceId), remark)
        return memos.get(id)
    }

    /** The initiator's route, unless the type's definition names every step (`FIXED`): then there is none. */
    private fun route(
        memo: Memo,
        type: MemoType?,
    ): List<RouteEntry> = if (type?.routePolicy == RoutePolicy.FIXED) emptyList() else routes.route(memo.id)

    private fun problems(
        memo: Memo,
        type: MemoType?,
        route: List<RouteEntry>,
    ) = if (type?.routePolicy ==
        RoutePolicy.FIXED
    ) {
        emptyList()
    } else {
        routes.problems(memo, route).ifEmpty { routes.authority(memo, type, route) }
    }

    /** A review never starts on a document that may still change in an open editor (ADR-0037). */
    private fun notBeingEdited(memo: Memo) {
        refuseIf(memo.documentFileId?.let(editing::beingEdited) == true, "DOCUMENT_BEING_EDITED") {
            "The document is open in the editor. Close it, wait for the save, then submit."
        }
    }

    /** The actor's own memo, its row locked so uploads and edits cannot slip in between the checks and the action. */
    private fun locked(id: UUID): Memo {
        memos.own(id)
        return store.lock(id) ?: throw MemoNotFoundException()
    }
}
