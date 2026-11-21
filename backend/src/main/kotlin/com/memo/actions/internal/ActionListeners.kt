package com.memo.actions.internal

import com.memo.actions.ActionChange
import com.memo.forum.ActionsPolicy
import com.memo.forum.ForumDirectory
import com.memo.meeting.AgendaPublished
import com.memo.meeting.MeetingItems
import com.memo.meeting.Meetings
import com.memo.meeting.Recusals
import com.memo.minutes.DecisionRecorded
import com.memo.workflow.InstanceStatus
import com.memo.workflow.WorkflowInstanceEnded
import com.memo.workflow.WorkflowQueries
import com.memo.workflow.WorkflowTaskCreated
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.temporal.ChronoUnit

/**
 * Keeps an action in step with its workflow (design E07 §1.4), in the engine's transaction (the subject's lock is held):
 * who holds the owner's task, the open step's name, and the status. The first task of an instance is the owner's (the
 * definition starts with the owner, ADR-0068's check); a task on any later step means the owner marked it done
 * (DONE_PENDING), a new task on the owner's step after that means it was sent back (IN_PROGRESS). The instance approved
 * closes the action; stopped any other way, it is cancelled.
 */
@Component
class ActionWorkflowListener(
    private val store: ActionStore,
    private val tracking: ActionTracking,
    private val queries: WorkflowQueries,
    private val effects: ActionItemEffects,
    private val clock: Clock,
) {
    @EventListener
    fun onTask(event: WorkflowTaskCreated) {
        val row = store.find(event.subjectId)?.takeIf { event.subjectType == ActionsPolicy.SUBJECT && it.status.open } ?: return
        val ownerStep = row.ownerStep ?: event.nodeId
        val firstStep = ownerStep.takeIf { row.ownerStep == null }
        val stepName = queries.task(event.taskId)?.stepName
        if (event.nodeId == null || event.nodeId == ownerStep) {
            val sentBack = ActionStatus.IN_PROGRESS.takeIf { row.status == ActionStatus.DONE_PENDING }
            tracking.track(row.id, holder = event.assigneeId, ownerStep = firstStep, stepName = stepName, status = sentBack)
        } else {
            val done = row.status != ActionStatus.DONE_PENDING
            val now = clock.instant().truncatedTo(ChronoUnit.MICROS)
            tracking.track(
                row.id,
                ownerStep = firstStep,
                stepName = stepName,
                status = ActionStatus.DONE_PENDING.takeIf { done },
                doneAt = now.takeIf { done },
            )
        }
    }

    @EventListener
    fun onEnded(event: WorkflowInstanceEnded) {
        val row =
            store.find(event.subjectId)?.takeIf {
                event.subjectType == ActionsPolicy.SUBJECT && it.status.open && it.workflowInstanceId in setOf(null, event.instanceId)
            } ?: return
        val now = clock.instant().truncatedTo(ChronoUnit.MICROS)
        if (event.status == InstanceStatus.APPROVED) {
            tracking.track(row.id, stepName = null, status = ActionStatus.CLOSED, closedAt = now)
            effects.changed(row, ActionChange.CLOSED)
        } else {
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
}

/**
 * Keeps an action's source current (design E07 §1.1, §1.2), in the publisher's transaction: a meeting's later agenda
 * revision refreshes the copied item columns (and the label's numbers) of the actions raised from its items, so the
 * visibility rule follows the item's restriction, hidden title, level and section; a decision corrected (a new entry)
 * leaves a SOURCE_CORRECTED entry on each issued action raised from it, and its secretaries not recused are told in the
 * app. Nothing else changes: the secretary decides whether to edit, cancel or keep each action.
 */
@Component
class ActionSourceListener(
    private val store: ActionStore,
    private val lines: ActionLines,
    private val tracking: ActionTracking,
    private val items: MeetingItems,
    private val meetings: Meetings,
    private val directory: ForumDirectory,
    private val recusals: Recusals,
    private val viewers: ActionViewers,
    private val effects: ActionItemEffects,
    private val clock: Clock,
) {
    @EventListener
    fun onPublished(event: AgendaPublished) {
        val actions = store.ofMeetingItems(event.meetingId)
        val meeting = meetings.find(event.meetingId)?.takeIf { actions.isNotEmpty() } ?: return
        val committee = directory.forum(meeting.forumId)?.name.orEmpty()
        val agenda = items.agenda(event.meetingId)
        actions.forEach { action ->
            val item = action.itemId?.let(agenda::item)?.takeIf { it.published } ?: return@forEach
            val copy = ItemCopy(item.parentId, item.classificationId, item.restrictedTo, item.titleHidden, item.membersOnly)
            val label = ActionReferences.label(action.source, committee, meeting.label, item.number, action.conditionIndex)
            if (copy != action.item || label != action.sourceLabel) tracking.refreshItem(action.id, copy, label)
        }
    }

    @EventListener
    fun onDecision(event: DecisionRecorded) {
        if (event.seq <= 1) return
        val now = clock.instant().truncatedTo(ChronoUnit.MICROS)
        val recused = recusals.recusedFrom(event.itemId)
        store.ofDecision(event.decisionId).filter { it.reference != null }.forEach { action ->
            store.lock(action.id)
            val details = mapOf("decisionId" to event.decisionId.toString(), "seq" to event.seq)
            lines.entry(action.id, "SOURCE_CORRECTED", actorOrNull(), details, now)
            val secretaries = viewers.secretaries(action.forumId) - recused - setOfNotNull(actorOrNull())
            effects.sourceCorrected(action, event.seq, secretaries)
        }
    }
}
