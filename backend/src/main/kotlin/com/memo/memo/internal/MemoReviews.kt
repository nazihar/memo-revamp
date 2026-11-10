package com.memo.memo.internal

import com.memo.common.web.FieldProblem
import com.memo.common.web.refuseIf
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
) {
    /** Round 1: the memo needs a document and a valid route; the first approver gets the task. */
    fun submit(
        id: UUID,
        remark: String?,
    ): Memo {
        val memo = locked(id)
        refuseIf(memo.status != MemoStatus.DRAFT, "WRONG_STATE") { "Only a draft can be submitted." }
        val route = routes.route(id)
        val problems =
            listOfNotNull(FieldProblem("document", "required", "Upload the memo document first.").takeIf { memo.documentFileId == null }) +
                routes.problems(memo, route)
        if (problems.isNotEmpty()) throw MemoRejectedException(problems)
        workflows.start(MemoWorkflowSubject.TYPE, id, route, remark)
        return memos.get(id)
    }

    /** Round N+1 after a return (design E01 §3): from the first approver of the route as it is now; earlier approvals lapse. */
    fun resubmit(
        id: UUID,
        remark: String?,
    ): Memo {
        val memo = locked(id)
        refuseIf(memo.status != MemoStatus.RETURNED, "WRONG_STATE") { "Only a memo returned to you can be resubmitted." }
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

    /** The actor's own memo, its row locked so uploads and edits cannot slip in between the checks and the action. */
    private fun locked(id: UUID): Memo {
        memos.own(id)
        return store.lock(id) ?: throw MemoNotFoundException()
    }
}
