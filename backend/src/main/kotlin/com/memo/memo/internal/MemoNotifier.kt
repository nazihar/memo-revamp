package com.memo.memo.internal

import com.memo.common.authz.AccessAdministration
import com.memo.common.authz.Permission
import com.memo.notification.NotificationKind
import com.memo.notification.Notifications
import com.memo.notification.RecipientLookup
import com.memo.tenancy.TenancyProperties
import com.memo.tenancy.TenantRegistry
import com.memo.workflow.ActionKind
import com.memo.workflow.InstanceStatus
import com.memo.workflow.TaskClosure
import com.memo.workflow.WorkflowActionTaken
import com.memo.workflow.WorkflowTaskClosed
import com.memo.workflow.WorkflowTaskCreated
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Workflow notifications of memos (design E01 §12), in the action's transaction: a rolled-back action notifies nobody.
 * Emails carry the memo's reference and a link, never its subject or a remark (AGENTS.md §3.4).
 */
@Component
class MemoNotifier(
    private val store: MemoStore,
    private val recipients: RecipientLookup,
    private val notifications: Notifications,
    private val access: AccessAdministration,
    private val registry: TenantRegistry,
    private val tenancy: TenancyProperties,
) {
    @EventListener
    fun onTaskCreated(event: WorkflowTaskCreated) {
        if (event.subjectType != MemoWorkflowSubject.TYPE) return
        when {
            event.blocked != null -> {
                val memo = store.find(event.subjectId) ?: return
                val admins = access.tenantWideHolders(Permission.WORKFLOW_TASKS_REASSIGN)
                (admins + memo.initiatorId).forEach { notify(it, NotificationKind.TASK_BLOCKED, event.subjectId) }
            }

            event.action == ActionKind.REASSIGN -> {
                notify(event.assigneeId, NotificationKind.TASK_REASSIGNED, event.subjectId)
            }

            else -> {
                notify(event.assigneeId, NotificationKind.TASK_ASSIGNED, event.subjectId)
            }
        }
    }

    @EventListener
    fun onTaskClosed(event: WorkflowTaskClosed) {
        if (event.subjectType != MemoWorkflowSubject.TYPE) return
        val kind =
            when {
                event.closure == TaskClosure.REASSIGNED -> NotificationKind.TASK_REASSIGNED
                event.closure == TaskClosure.CANCELLED && event.action == ActionKind.WITHDRAW -> NotificationKind.MEMO_WITHDRAWN
                else -> null
            }
        kind?.let { notify(event.assigneeId, it, event.subjectId) }
    }

    @EventListener
    fun onAction(event: WorkflowActionTaken) {
        if (event.subjectType != MemoWorkflowSubject.TYPE) return
        val kind =
            when {
                event.kind == ActionKind.RETURN -> NotificationKind.MEMO_RETURNED
                event.kind == ActionKind.REJECT -> NotificationKind.MEMO_REJECTED
                event.status == InstanceStatus.APPROVED -> NotificationKind.MEMO_APPROVED
                else -> null
            } ?: return
        store.find(event.subjectId)?.let { notify(it.initiatorId, kind, it.id) }
    }

    private fun notify(
        user: UUID,
        kind: NotificationKind,
        memoId: UUID,
    ) {
        val recipient = recipients.recipient(user) ?: return
        val memo = store.find(memoId) ?: return
        val tenant = registry.current()
        val link = "/memos/$memoId"
        notifications.notify(
            recipient,
            kind,
            mapOf(
                "reference" to memo.reference,
                "organisation" to tenant?.name.orEmpty(),
                "link" to (tenant?.let { tenancy.publicUrl(it.slug) }.orEmpty() + link),
            ),
            link,
        )
    }
}
