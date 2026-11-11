package com.memo.memo.internal

import com.memo.common.web.FieldProblem
import com.memo.common.web.refuseIf
import com.memo.docedit.DocumentEditing
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
    private val types: MemoTypeStore,
) {
    /** Round 1: the memo needs a document and a valid route; the first approver gets the task. */
    fun submit(
        id: UUID,
        remark: String?,
    ): Memo {
        val memo = locked(id)
        refuseIf(memo.status != MemoStatus.DRAFT, "WRONG_STATE") { "Only a draft can be submitted." }
        notBeingEdited(memo)
        val route = routes.route(id)
        val problems =
            listOfNotNull(FieldProblem("document", "required", "Upload the memo document first.").takeIf { memo.documentFileId == null }) +
                routes.problems(memo, route)
        if (problems.isNotEmpty()) throw MemoRejectedException(problems)
        // The memo type's workflow (E03): new memos pin its newest published version.
        val definition = types.find(memo.typeId)?.definitionKey ?: Workflows.SEQUENTIAL_APPROVAL
        workflows.start(MemoWorkflowSubject.TYPE, id, route, remark, definition)
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
        val route = routes.route(id)
        val problems = routes.problems(memo, route)
        if (problems.isNotEmpty()) throw MemoRejectedException(problems)
        workflows.resubmit(checkNotNull(memo.workflowInstanceId), route, remark)
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
