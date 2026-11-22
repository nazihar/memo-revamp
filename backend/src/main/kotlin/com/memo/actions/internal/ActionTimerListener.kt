package com.memo.actions.internal

import com.memo.forum.ActionsPolicy
import com.memo.forum.ForumDirectory
import com.memo.identity.UserSummaries
import com.memo.meeting.Recusals
import com.memo.notification.NotificationKind
import com.memo.org.OrgDirectory
import com.memo.workflow.TimerKind
import com.memo.workflow.WorkflowEscalated
import com.memo.workflow.WorkflowTimerFired
import com.memo.workflow.WorkingDays
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * The engine's timers on an action (ADR-0041, ADR-0068, design E07 §1.8, §9), in the timer's transaction. On the owner's
 * step: 5 working days before the date the holder hears it is due soon; at the end of the date the holder and the
 * committee's secretaries hear it is overdue; each reminder after tells the holder again; at the escalation the
 * secretaries hear it was escalated (the engine tells the holder's supervisor, [onEscalated], or nobody when there is
 * none). On a later step (verification) its reminders tell the verifiers. Every notice is in the app at once, its email
 * waiting for the daily digest; an escalation is emailed at once. A timer of an earlier run, or of an action no longer
 * open, tells nobody.
 */
@Component
class ActionTimerListener(
    private val store: ActionStore,
    private val viewers: ActionViewers,
    private val recusals: Recusals,
    private val directory: ForumDirectory,
    private val users: UserSummaries,
    private val org: OrgDirectory,
    private val workingDays: WorkingDays,
    private val effects: ActionItemEffects,
) {
    @EventListener
    fun onTimer(event: WorkflowTimerFired) {
        val row = current(event.subjectType, event.subjectId, event.instanceId) ?: return
        val date = mapOf("date" to row.dueDate.toString())
        val holder = event.assigneeId
        if (row.status == ActionStatus.DONE_PENDING) {
            if (event.kind != TimerKind.ESCALATE) holder?.let { effects.notify(it, NotificationKind.ACTION_TO_VERIFY, row, date) }
            return
        }
        when (event.kind) {
            TimerKind.REMIND_BEFORE -> {
                holder?.let { effects.notify(it, NotificationKind.ACTION_DUE_SOON, row, date) }
            }

            TimerKind.DUE -> {
                holder?.let { effects.notify(it, NotificationKind.ACTION_OVERDUE, row, date + (STAGE to "DUE")) }
                secretaries(row, holder).forEach { effects.notify(it, NotificationKind.ACTION_OVERDUE, row, date + (STAGE to "DUE")) }
            }

            TimerKind.REMIND_AFTER -> {
                holder?.let { effects.notify(it, NotificationKind.ACTION_OVERDUE, row, date + (STAGE to "REMINDER")) }
            }

            TimerKind.ESCALATE -> {
                secretaries(row, holder).forEach { effects.notify(it, NotificationKind.ACTION_OVERDUE, row, date + (STAGE to "ESCALATED")) }
            }
        }
    }

    /**
     * The engine escalated the action's task to [WorkflowEscalated.recipientId] (the holder's supervisor by the reporting
     * line, or the definition's target such as the chair): told at once, by email too, with the reference, the date, who
     * holds it and how many working days it is overdue, never the action's text. The link opens the action for someone
     * with a seat on the committee, and memo for the others, who do not see it.
     */
    @EventListener
    fun onEscalated(event: WorkflowEscalated) {
        val row = current(event.subjectType, event.subjectId, event.instanceId) ?: return
        val holderId = row.holderUserId ?: row.owner?.userId
        val name = holderId?.let { users.names(setOf(it))[it] }.orEmpty()
        val position = row.owner?.positionId?.let { org.positions(setOf(it))[it]?.title }
        val holder = if (position != null) "$name ($position)" else name
        val days = row.dueDate?.let { workingDays.since(ActionReferences.dueAt(it, org.zone())) } ?: 0
        val seated = directory.rolesOf(event.recipientId)[row.forumId] != null
        effects.notify(
            event.recipientId,
            NotificationKind.ACTION_ESCALATED,
            row,
            mapOf("date" to row.dueDate.toString(), "holder" to holder, "days" to days.toString()),
            link = if (seated) "/actions/${row.id}" else HOME,
        )
    }

    /** The action [subjectId] while open on [instanceId] (its current run); null otherwise. */
    private fun current(
        subjectType: String,
        subjectId: UUID,
        instanceId: UUID,
    ): ActionRow? =
        store.find(subjectId)?.takeIf {
            subjectType == ActionsPolicy.SUBJECT && it.status.open && it.workflowInstanceId == instanceId
        }

    /** The committee's secretaries in office not recused from the item, but the holder. */
    private fun secretaries(
        row: ActionRow,
        holder: UUID?,
    ): List<UUID> {
        val recused = row.itemId?.let { recusals.recusedFrom(it) }.orEmpty()
        return viewers.secretaries(row.forumId) - recused - setOfNotNull(holder)
    }

    private companion object {
        const val STAGE = "stage"

        /** Where a person who does not see the action lands: their inbox. */
        const val HOME = "/inbox"
    }
}
