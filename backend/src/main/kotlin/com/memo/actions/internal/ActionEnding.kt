package com.memo.actions.internal

import com.memo.actions.internal.ActionStore.Column
import com.memo.common.concurrency.ConcurrentModificationException
import com.memo.common.web.FieldProblem
import com.memo.common.web.refuseIf
import com.memo.forum.ActionsPolicy
import com.memo.forum.ForumPolicies
import com.memo.org.OrgDirectory
import com.memo.workflow.InstanceStatus
import com.memo.workflow.WorkflowQueries
import com.memo.workflow.Workflows
import com.memo.workflow.WorkingDays
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID

/** Cancelling an issued action (design E07 §1.10, `POST /actions/{id}/cancel`). */
data class CancelInput(
    val kind: CancelKind?,
    val reason: String?,
    /** SUPERSEDED: the action of the same committee that replaces it. */
    val supersededBy: UUID? = null,
    /** RISK_ACCEPTED recorded by a secretary for the chair: how they heard. */
    val how: How? = null,
)

/**
 * Cancelling and reopening (ADR-0067, design E07 §1.10):
 * - **Cancel:** a secretary or the chair not recused, with a kind and a reason. "Risk accepted" is the chair's alone, or
 *   recorded by a secretary on the chair's behalf with how they heard (422 `REQUIRED` at `how` without it); the chair is
 *   told at once. The workflow instance is cancelled, a pending date request withdrawn, the owner and contributors told.
 * - **Reopen:** a secretary or `forums.manage` not recused, within 90 days of closing or cancelling (409 `TOO_LATE`:
 *   raise a new action instead), never a discarded draft: a new instance starts on the definition the committee type
 *   names today (the item's next run, ADR-0068 amendment), and the action is IN_PROGRESS with its new date.
 * These rules are fixed, not the committee type's (design E07 §1.13).
 */
@Service
class ActionEnding(
    private val store: ActionStore,
    private val lines: ActionLines,
    private val requests: ActionDueRequests,
    private val dates: ActionDates,
    private val scenes: ActionScenes,
    private val tracking: ActionTracking,
    private val viewers: ActionViewers,
    private val policies: ForumPolicies,
    private val workingDays: WorkingDays,
    private val workflows: Workflows,
    private val queries: WorkflowQueries,
    private val org: OrgDirectory,
    private val effects: ActionProgressEffects,
    private val clock: Clock,
) {
    /** Cancels [actionId], seen at [version], as [input] says. */
    fun cancel(
        actionId: UUID,
        version: Long,
        input: CancelInput,
    ): ActionRow {
        val scene = scenes.scene(actionId)
        val row = scene.row
        scenes.requireChairOrSecretary(scene)
        refuseIf(row.status == ActionStatus.DRAFT, ActionRules.WRONG_STATE) { "A draft is discarded, not cancelled." }
        ActionChecks.requireOpen(row.status, "Only an open action can be cancelled.")
        val kind = input.kind
        val chairs = scene.viewer.chairs(row.forumId)
        val trimmed = input.reason?.trim()
        val replacing = input.supersededBy?.let { store.find(it) }
        requireNoProblems(cancelProblems(row, input, trimmed, chairs, replacing))
        val chair = viewers.chair(row.forumId).takeIf { kind == CancelKind.RISK_ACCEPTED && !chairs }
        val now = now()
        val values =
            mapOf<Column, Any?>(
                Column.STATUS to ActionStatus.CANCELLED.name,
                Column.CANCEL_KIND to checkNotNull(kind).name,
                Column.CANCEL_REASON to trimmed,
                Column.SUPERSEDED_BY to replacing?.id,
                Column.CLOSED_AT to now.atOffset(ZoneOffset.UTC),
                Column.CLOSED_BY to scene.viewer.userId,
                Column.STEP_NAME to null,
            )
        if (version != row.version || !store.change(row.id, version, ActionChecks.OPEN, values)) {
            throw ConcurrentModificationException("action ${row.id}", version)
        }
        requests.pending(row.id)?.let { dates.withdraw(row, it, CANCELLED) }
        // The action is cancelled first, so the engine's end of the instance leaves it as it is (ActionWorkflowListener).
        row.workflowInstanceId?.takeIf { running(it) }?.let { workflows.cancel(it, trimmed) }
        val more = EntryMore(onBehalfOf = chair, how = input.how.takeIf { chair != null }, text = trimmed)
        val details = mapOf("kind" to kind.name, "supersededBy" to replacing?.id?.toString())
        lines.entry(row.id, CANCELLED, scene.viewer.userId, details, now, more)
        effects.cancelled(row, kind, checkNotNull(trimmed), replacing?.id, more, chair, scene.viewer.userId)
        return checkNotNull(store.find(row.id))
    }

    /** Reopens the closed or cancelled [actionId], seen at [version], due [dueDate], for [reason]. */
    fun reopen(
        actionId: UUID,
        version: Long,
        dueDate: LocalDate?,
        reason: String?,
    ): ActionRow {
        val scene = scenes.scene(actionId)
        val row = scene.row
        scenes.requireManager(scene)
        refuseIf(row.status !in ENDED || row.cancelKind == CancelKind.DISCARDED, ActionRules.WRONG_STATE) {
            "Only a closed or cancelled action can be reopened."
        }
        val today = org.today()
        val ended = row.closedAt?.atZone(org.zone())?.toLocalDate() ?: today
        refuseIf(ChronoUnit.DAYS.between(ended, today) > REOPEN_DAYS, TOO_LATE) {
            "It ended more than $REOPEN_DAYS days ago: raise a new action instead."
        }
        val trimmed = reason?.trim()
        requireNoProblems(
            listOfNotNull(
                ActionChecks.reason(trimmed),
                FieldProblem("dueDate", "REQUIRED", "Choose when it is due.").takeIf { dueDate == null },
                ActionRules.due(dueDate, today),
            ),
        )
        val due = checkNotNull(dueDate)
        val policy = policies.current(row.forumId)?.actions ?: ActionsPolicy()
        val values =
            mapOf<Column, Any?>(
                Column.STATUS to ActionStatus.IN_PROGRESS.name,
                Column.DUE_DATE to due,
                Column.DUE_REVISIONS to row.dueRevisions + (if (due != row.dueDate) 1 else 0),
                Column.AMBER_FROM to workingDays.before(due, policy.amberWorkingDays),
                Column.STALE_FROM to policy.staleWorkingDays.takeIf { it != ActionsPolicy.OFF }?.let { workingDays.after(today, it) },
                Column.CONFIDENCE to null,
                Column.CANCEL_KIND to null,
                Column.CANCEL_REASON to null,
                Column.SUPERSEDED_BY to null,
                Column.CLOSED_AT to null,
                Column.CLOSED_BY to null,
                Column.DONE_AT to null,
                Column.HOLDER_USER_ID to null,
                Column.OWNER_STEP to null,
                Column.STEP_NAME to null,
                Column.WORKFLOW_INSTANCE_ID to null,
                Column.REOPEN_COUNT to row.reopenCount + 1,
            ) + ActionProgress.CLEAR_OVERRIDE
        if (version != row.version || !store.change(row.id, version, ENDED, values)) {
            throw ConcurrentModificationException("action ${row.id}", version)
        }
        // The next run: a new instance on the definition the committee type names today; its first task is the owner's.
        val started = workflows.start(ActionsPolicy.SUBJECT, row.id, emptyList(), definitionKey = policy.definitionKey)
        tracking.setInstance(row.id, started.instanceId)
        val after = checkNotNull(store.find(row.id))
        val details =
            mapOf(
                "previousInstanceId" to row.workflowInstanceId?.toString(),
                "instanceId" to started.instanceId.toString(),
                "dueDate" to due.toString(),
                "was" to (row.cancelKind?.name ?: row.status.name),
            )
        lines.entry(row.id, "REOPENED", scene.viewer.userId, details, now(), EntryMore(text = trimmed))
        effects.reopened(row, after, checkNotNull(trimmed), scene.viewer.userId)
        return after
    }

    private fun running(instanceId: UUID): Boolean =
        queries.viewOf(ActionsPolicy.SUBJECT, actionIdOf(instanceId) ?: return false)?.let {
            it.instanceId == instanceId && it.status == InstanceStatus.RUNNING
        } == true

    private fun actionIdOf(instanceId: UUID): UUID? = queries.subjectOfInstance(instanceId)?.id

    private fun now(): Instant = clock.instant().truncatedTo(ChronoUnit.MICROS)

    companion object {
        const val TOO_LATE = "TOO_LATE"
        const val SUPERSEDED_BY = "SUPERSEDED_BY"
        const val CANCELLED = "CANCELLED"

        /** An action closed or cancelled more than this many days ago is not reopened (design E07 §1.10). */
        const val REOPEN_DAYS = 90L

        val ENDED = setOf(ActionStatus.CLOSED, ActionStatus.CANCELLED)
    }
}

/** What cancelling [row] as [input] says needs: a kind and a reason, `how` for a secretary's "risk accepted", the action replacing it. */
private fun cancelProblems(
    row: ActionRow,
    input: CancelInput,
    reason: String?,
    chairs: Boolean,
    replacing: ActionRow?,
): List<FieldProblem> {
    val kind = input.kind
    val wrongReplacement =
        replacing == null || replacing.id == row.id || replacing.forumId != row.forumId || replacing.reference == null
    return listOfNotNull(
        FieldProblem("kind", "REQUIRED", "Say why the action is cancelled.").takeIf { kind == null || kind == CancelKind.DISCARDED },
        ActionChecks.reason(reason),
        FieldProblem("how", "REQUIRED", "Say how the chair told you they accept the risk.")
            .takeIf { kind == CancelKind.RISK_ACCEPTED && !chairs && input.how == null },
        FieldProblem("supersededBy", ActionEnding.SUPERSEDED_BY, "Choose the issued action of this committee that replaces it.")
            .takeIf { kind == CancelKind.SUPERSEDED && wrongReplacement },
        FieldProblem("supersededBy", "SOURCE", "Only a superseded action names the one that replaces it.")
            .takeIf { kind != CancelKind.SUPERSEDED && input.supersededBy != null },
    )
}
