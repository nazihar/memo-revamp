package com.memo.memo.internal

import com.memo.common.authz.AccessAdministration
import com.memo.common.authz.Clearances
import com.memo.common.authz.Permission
import com.memo.notification.NotificationKind
import com.memo.notification.Notifications
import com.memo.notification.RecipientLookup
import com.memo.tenancy.TenancyProperties
import com.memo.tenancy.TenantRegistry
import com.memo.workflow.ActionKind
import com.memo.workflow.InstanceStatus
import com.memo.workflow.TaskClosure
import com.memo.workflow.TimerKind
import com.memo.workflow.WorkflowActionTaken
import com.memo.workflow.WorkflowEscalated
import com.memo.workflow.WorkflowNotifyRequested
import com.memo.workflow.WorkflowTaskClosed
import com.memo.workflow.WorkflowTaskCreated
import com.memo.workflow.WorkflowTimerFired
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset
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
    private val clearances: Clearances,
    private val clock: Clock,
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

    /**
     * A definition's notify step (design E03 §2): the recipient, if cleared for the memo, becomes a participant (so the
     * link opens it) and is told; the step's message key goes with the in-app notification, never into the email.
     */
    @EventListener
    fun onNotify(event: WorkflowNotifyRequested) {
        if (event.subjectType != MemoWorkflowSubject.TYPE) return
        val memo = store.find(event.subjectId)
        val rank = clearances.rankOf(listOf(event.recipientId))[event.recipientId]
        if (memo == null || rank == null || rank < memo.classificationRank) return
        store.addParticipant(
            memo.id,
            event.recipientId,
            ParticipantKind.NOTIFIED,
            OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC),
        )
        notify(event.recipientId, NotificationKind.MEMO_UPDATE, memo.id, mapOf("message" to event.messageKey))
    }

    /**
     * SLA timers (design E05 §4.3): reminders go to the holder; when the task becomes overdue the holder and the
     * initiator are told. Escalations are the workflow's (they reassign or tell the escalation targets).
     */
    @EventListener
    fun onTimer(event: WorkflowTimerFired) {
        if (event.subjectType != MemoWorkflowSubject.TYPE) return
        when (event.kind) {
            TimerKind.REMIND_BEFORE, TimerKind.REMIND_AFTER -> {
                notify(event.assigneeId, NotificationKind.TASK_REMINDER, event.subjectId)
            }

            TimerKind.DUE -> {
                notify(event.assigneeId, NotificationKind.TASK_OVERDUE, event.subjectId)
                store.find(event.subjectId)?.let { notify(it.initiatorId, NotificationKind.TASK_OVERDUE, it.id) }
            }

            // The workflow escalates on its own: it reassigns or tells the escalation targets.
            TimerKind.ESCALATE -> {}
        }
    }

    /**
     * An overdue task escalated to [WorkflowEscalated.recipientId] who is to be told (design E05 §4.3): if cleared for the
     * memo, they become a participant (so the link opens it) and hear that it is overdue.
     */
    @EventListener
    fun onEscalated(event: WorkflowEscalated) {
        if (event.subjectType != MemoWorkflowSubject.TYPE) return
        val memo = store.find(event.subjectId)
        val rank = clearances.rankOf(listOf(event.recipientId))[event.recipientId]
        if (memo == null || rank == null || rank < memo.classificationRank) return
        store.addParticipant(
            memo.id,
            event.recipientId,
            ParticipantKind.NOTIFIED,
            OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC),
        )
        notify(event.recipientId, NotificationKind.TASK_OVERDUE, memo.id)
    }

    /** Tells [user] (nobody when null: a step without assignee, which the reassigners hear about instead). */
    private fun notify(
        user: UUID?,
        kind: NotificationKind,
        memoId: UUID,
        extra: Map<String, String> = emptyMap(),
    ) {
        val recipient = user?.let(recipients::recipient) ?: return
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
            ) + extra,
            link,
        )
    }
}
