package com.memo.memo.internal

import com.memo.common.authz.StepUpCheck
import com.memo.identity.SecurityLimit
import com.memo.identity.SecuritySettings
import com.memo.workflow.WorkflowQueries
import com.memo.workflow.Workflows
import org.springframework.stereotype.Service
import java.time.Duration
import java.util.UUID

/**
 * An approver's actions on a memo task (design E01 §5): approve, return, reject. A task on a memo the actor cannot see
 * answers 404, like the memo; who may act on a visible task is the engine's decision (403 for anyone but the assignee).
 * Approving needs a fresh second factor within the approval window when the memo's type asks for it (ADR-0036).
 * Inside a tenant transaction.
 */
@Service
class MemoTasks(
    private val memos: Memos,
    private val types: MemoTypes,
    private val workflows: Workflows,
    private val queries: WorkflowQueries,
    private val security: SecuritySettings,
    private val stepUp: StepUpCheck,
) {
    fun approve(
        taskId: UUID,
        remark: String?,
    ): Memo {
        val memo = memoOf(taskId)
        if (types.get(memo.typeId).stepUpOnApprove) {
            stepUp.require(Duration.ofMinutes(security.int(SecurityLimit.APPROVAL_STEP_UP_MINUTES).toLong()))
        }
        workflows.approve(taskId, remark)
        return memos.get(memo.id)
    }

    fun returnToInitiator(
        taskId: UUID,
        remark: String?,
    ): Memo {
        val memo = memoOf(taskId)
        workflows.returnToInitiator(taskId, remark)
        return memos.get(memo.id)
    }

    fun reject(
        taskId: UUID,
        remark: String?,
    ): Memo {
        val memo = memoOf(taskId)
        workflows.reject(taskId, remark)
        return memos.get(memo.id)
    }

    /** The memo the task is about, if the actor may see it (the workflow locks it before acting, [MemoWorkflowSubject.lock]). */
    fun memoOf(taskId: UUID): Memo {
        val subject = queries.subjectOfTask(taskId)?.takeIf { it.type == MemoWorkflowSubject.TYPE } ?: throw MemoNotFoundException("task")
        return memos.get(subject.id)
    }
}
