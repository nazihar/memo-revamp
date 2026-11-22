package com.memo.actions.internal

import com.memo.actions.ActionChange
import com.memo.audit.AuditLog
import com.memo.forum.ActionsPolicy
import com.memo.meeting.Recusals
import com.memo.notification.NotificationKind
import com.memo.org.OrgDirectory
import com.memo.workflow.InstanceStatus
import com.memo.workflow.WorkflowQueries
import com.memo.workflow.Workflows
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.temporal.ChronoUnit

/** What one pass of the safety net found and did. */
data class SafetyRun(
    val checked: Int = 0,
    /** Issued actions without a workflow, reported to the secretaries. */
    val reported: Int = 0,
    /** Actions whose owner's reminders were planned again from their due date. */
    val replanned: Int = 0,
    /** Actions whose workflow had ended or moved on while the action did not follow: brought in step. */
    val reconciled: Int = 0,
)

/**
 * The daily task's safety net (design E07 §9 (c)), as the system: every issued action not finished is compared with its
 * workflow.
 * - **No running workflow** (for example a start that failed): the committee's secretaries not recused from the item are
 *   told in the app, once per run of the action (an entry `NOT_RUNNING` keyed by the instance), and it is audited.
 * - **The workflow ended or moved to a newer run** without the action following (an event lost): the action takes the
 *   engine's end, as [ActionWorkflowListener] would (approved: CLOSED; otherwise CANCELLED), or the newer run's id; an
 *   entry `RECONCILED` and an audit record say so.
 * - **The owner's step is not due when the action is** (a lost re-plan): `Workflows.redue` plans its reminders, due time
 *   and escalation again from the action's date (a reminder already past is skipped), audited.
 * Each action is locked (the subject's lock, before the instance's) only when it is acted on.
 */
@Component
class ActionSafetyNet(
    private val store: ActionStore,
    private val lines: ActionLines,
    private val tracking: ActionTracking,
    private val queries: WorkflowQueries,
    private val workflows: Workflows,
    private val viewers: ActionViewers,
    private val recusals: Recusals,
    private val org: OrgDirectory,
    private val audit: AuditLog,
    private val effects: ActionItemEffects,
    private val clock: Clock,
) {
    fun check(): SafetyRun {
        var reported = 0
        var replanned = 0
        var reconciled = 0
        val open = store.where(OPEN_ACTIONS, LIMIT)
        open.forEach { candidate ->
            val view = queries.viewOf(ActionsPolicy.SUBJECT, candidate.id)
            when {
                view == null -> {
                    if (report(candidate)) reported++
                }

                view.status != InstanceStatus.RUNNING || view.instanceId != candidate.workflowInstanceId -> {
                    if (reconcile(candidate, view.instanceId, view.status)) reconciled++
                }

                candidate.status in ActionChecks.UNDERWAY && drifted(candidate) -> {
                    if (replan(candidate)) replanned++
                }

                else -> {}
            }
        }
        return SafetyRun(open.size, reported, replanned, reconciled)
    }

    /** [candidate] has no workflow: its secretaries hear it once per run. */
    private fun report(candidate: ActionRow): Boolean {
        val key = candidate.workflowInstanceId?.toString()
        val row =
            store.lock(candidate.id)?.takeIf { locked ->
                locked.status.open && lines.entries(locked.id).none { it.kind == NOT_RUNNING && it.details["instanceId"] == key }
            } ?: return false
        lines.entry(row.id, NOT_RUNNING, null, mapOf("instanceId" to key), now())
        audit.record("actions.action.not_running", ActionItemEffects.OBJECT_TYPE, row.id.toString(), after = mapOf("instanceId" to key))
        val recused = row.itemId?.let { recusals.recusedFrom(it) }.orEmpty()
        (viewers.secretaries(row.forumId) - recused).forEach { effects.notify(it, NotificationKind.ACTION_NOT_RUNNING, row) }
        return true
    }

    /** [candidate]'s workflow is [instanceId], now [status]: the action follows it. */
    private fun reconcile(
        candidate: ActionRow,
        instanceId: java.util.UUID,
        status: InstanceStatus,
    ): Boolean {
        val row = store.lock(candidate.id)?.takeIf { it.status.open } ?: return false
        val now = now()
        val before = mapOf("status" to row.status.name, "instanceId" to row.workflowInstanceId?.toString())
        if (instanceId != row.workflowInstanceId) tracking.setInstance(row.id, instanceId)
        when (status) {
            InstanceStatus.RUNNING -> {}

            InstanceStatus.APPROVED -> {
                tracking.track(row.id, stepName = null, status = ActionStatus.CLOSED, closedAt = now)
                effects.changed(row, ActionChange.CLOSED)
            }

            else -> {
                tracking.track(
                    row.id,
                    stepName = null,
                    status = ActionStatus.CANCELLED,
                    closedAt = now,
                    cancelKind = CancelKind.NO_LONGER_NEEDED,
                )
                effects.changed(row, ActionChange.CANCELLED)
            }
        }
        val after = mapOf("instanceId" to instanceId.toString(), "instanceStatus" to status.name)
        lines.entry(row.id, RECONCILED, null, after, now)
        audit.record("actions.action.reconciled", ActionItemEffects.OBJECT_TYPE, row.id.toString(), before = before, after = after)
        return true
    }

    /** The owner's open step is due at another time than [candidate]'s date (a step without a due time is left alone). */
    private fun drifted(candidate: ActionRow): Boolean {
        val due = candidate.dueDate?.let { ActionReferences.dueAt(it, org.zone()) }
        val planned = due?.let { queries.dueOf(ActionsPolicy.SUBJECT, candidate.id) }
        return planned != null && planned != due
    }

    /** [candidate]'s owner's step is planned again from its due date. */
    private fun replan(candidate: ActionRow): Boolean {
        val row = store.lock(candidate.id)?.takeIf { it.status in ActionChecks.UNDERWAY } ?: return false
        val tasks = workflows.redue(ActionsPolicy.SUBJECT, row.id)
        if (tasks > 0) {
            audit.record(
                "actions.timers.replanned",
                ActionItemEffects.OBJECT_TYPE,
                row.id.toString(),
                after = mapOf("dueDate" to row.dueDate?.toString(), "tasks" to tasks),
            )
        }
        return tasks > 0
    }

    private fun now() = clock.instant().truncatedTo(ChronoUnit.MICROS)

    private companion object {
        const val NOT_RUNNING = "NOT_RUNNING"
        const val RECONCILED = "RECONCILED"

        /** At most this many actions checked a day (design E07 §8: 3,000 open per tenant). */
        const val LIMIT = 10_000
    }
}
