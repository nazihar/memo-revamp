package com.memo.workflow.internal

import com.memo.audit.AuditLog
import com.memo.common.tenant.TenantDsl
import com.memo.workflow.ActionKind
import com.memo.workflow.TaskClosure
import com.memo.workflow.WorkflowActionTaken
import com.memo.workflow.WorkflowEscalatedToForum
import com.memo.workflow.WorkflowInstanceEnded
import com.memo.workflow.WorkflowNotifyRequested
import com.memo.workflow.WorkflowStepCompleted
import com.memo.workflow.WorkflowTaskClosed
import com.memo.workflow.WorkflowTaskCreated
import com.memo.workflow.engine.Effect
import com.memo.workflow.engine.InstanceState
import com.memo.workflow.engine.Outcome
import com.memo.workflow.engine.StepState
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * The audit entries and events of an accepted action (design E01 §7), in the action's transaction. Audit entries carry
 * ids, the round and the subject snapshot, never the remark; events carry ids only (ADR-0017).
 */
@Component
class ActionEffects(
    private val audit: AuditLog,
    private val events: ApplicationEventPublisher,
    private val tenantDsl: TenantDsl,
) {
    fun audit(
        instance: LoadedInstance,
        outcome: Outcome,
        snapshot: Map<String, Any?>,
    ) {
        val action = outcome.action
        val created = outcome.effects.filterIsInstance<Effect.TaskCreated>()
        val details =
            mapOf(
                "instance" to instance.id.toString(),
                "subjectType" to instance.subjectType,
                "subject" to instance.subjectId.toString(),
                "round" to action.round,
                "snapshot" to snapshot,
            ) +
                if (action.kind == ActionKind.REASSIGN) {
                    mapOf(
                        "to" to
                            created
                                .single()
                                .task.assignee
                                .toString(),
                    )
                } else {
                    emptyMap()
                }
        audit.record(
            AUDIT_ACTIONS.getValue(action.kind),
            if (action.taskId != null) TASK else INSTANCE,
            (action.taskId ?: instance.id).toString(),
            after = details,
        )
        created.filter { it.task.blocked != null }.forEach {
            audit.record(
                "workflow.task.blocked",
                TASK,
                it.task.id.toString(),
                after =
                    mapOf(
                        "instance" to instance.id.toString(),
                        "assignee" to it.task.assignee.toString(),
                        "reason" to it.task.blocked?.name,
                    ),
            )
        }
        outcome.effects.filterIsInstance<Effect.InstanceEnded>().forEach {
            audit.record("workflow.instance.ended", INSTANCE, instance.id.toString(), after = mapOf("status" to it.status.name))
        }
    }

    /** Task events first, then the action, so a subject's listener sees the tasks before it updates its own status. */
    fun publish(
        instance: LoadedInstance,
        before: InstanceState?,
        outcome: Outcome,
        actionId: UUID,
    ) {
        val tenant = tenantDsl.tenant().value
        val assignees =
            before
                ?.round
                ?.steps
                ?.flatMap { it.tasks }
                ?.associate { it.id to it.assignee }
                .orEmpty()
        // Casts, not a `when` with an empty branch: that compiles to a type switch SpotBugs reports as missing a default.
        outcome.effects
            .mapNotNull { effect ->
                (effect as? Effect.TaskClosed)?.let {
                    WorkflowTaskClosed(
                        tenant,
                        instance.id,
                        instance.subjectType,
                        instance.subjectId,
                        it.taskId,
                        assignees.getValue(it.taskId),
                        TaskClosure.valueOf(it.state.name),
                        outcome.action.kind,
                    )
                } ?: (effect as? Effect.TaskCreated)?.task?.let {
                    WorkflowTaskCreated(
                        tenant,
                        instance.id,
                        instance.subjectType,
                        instance.subjectId,
                        it.id,
                        it.assignee,
                        it.blocked,
                        outcome.action.kind,
                    )
                }
            }.forEach(events::publishEvent)
        publishCompleted(instance, before, outcome, tenant)
        publishSteps(instance, outcome, tenant)
        val action = outcome.action
        events.publishEvent(
            WorkflowActionTaken(
                tenant,
                instance.id,
                instance.subjectType,
                instance.subjectId,
                actionId,
                action.kind,
                action.round,
                outcome.state.status,
            ),
        )
        // Last, so listeners of the end see the subject already updated from the action.
        outcome.effects.filterIsInstance<Effect.InstanceEnded>().forEach {
            events.publishEvent(WorkflowInstanceEnded(tenant, instance.id, instance.subjectType, instance.subjectId, it.status))
        }
    }

    /** Steps that hold tasks and became done in this action (design E03 §1). */
    private fun publishCompleted(
        instance: LoadedInstance,
        before: InstanceState?,
        outcome: Outcome,
        tenant: UUID,
    ) {
        val done =
            before
                ?.round
                ?.steps
                .orEmpty()
                .filter { it.state == StepState.DONE }
                .map { it.id }
                .toSet()
        outcome.state.round.steps
            .filter { it.state == StepState.DONE && it.id !in done }
            .forEach {
                events.publishEvent(
                    WorkflowStepCompleted(
                        tenant,
                        instance.id,
                        instance.subjectType,
                        instance.subjectId,
                        it.id,
                        it.nodeId,
                        outcome.state.round.number,
                    ),
                )
            }
    }

    /** The events of notify and escalate-to-forum steps the action passed (design E03 §1). */
    private fun publishSteps(
        instance: LoadedInstance,
        outcome: Outcome,
        tenant: UUID,
    ) {
        outcome.effects.forEach { effect ->
            (effect as? Effect.Notify)?.let { notify ->
                notify.recipients.forEach { recipient ->
                    events.publishEvent(
                        WorkflowNotifyRequested(
                            tenant,
                            instance.id,
                            instance.subjectType,
                            instance.subjectId,
                            notify.nodeId,
                            recipient,
                            notify.message,
                        ),
                    )
                }
            }
            (effect as? Effect.EscalateToForum)?.let {
                events.publishEvent(
                    WorkflowEscalatedToForum(tenant, instance.id, instance.subjectType, instance.subjectId, it.nodeId, it.forum),
                )
            }
        }
    }

    private companion object {
        const val TASK = "workflow_task"
        const val INSTANCE = "workflow_instance"
        val AUDIT_ACTIONS =
            mapOf(
                ActionKind.SUBMIT to "workflow.instance.started",
                ActionKind.APPROVE to "workflow.task.approved",
                ActionKind.RECOMMEND to "workflow.task.recommended",
                ActionKind.RECALL to "workflow.instance.recalled",
                ActionKind.CANCEL to "workflow.instance.cancelled",
                ActionKind.MIGRATE to "workflow.instance.migrated",
                ActionKind.CLAIM to "workflow.task.claimed",
                ActionKind.RELEASE to "workflow.task.released",
                ActionKind.SEND_BACK to "workflow.task.sent_back",
                ActionKind.FORWARD to "workflow.task.forwarded",
                ActionKind.DELEGATE to "workflow.task.delegated",
                ActionKind.ASK to "workflow.task.asked",
                ActionKind.ANSWER to "workflow.task.answered",
                ActionKind.REFER to "workflow.task.referred",
                ActionKind.OPINION to "workflow.task.opinion_given",
                ActionKind.RETURN to "workflow.task.returned",
                ActionKind.REJECT to "workflow.task.rejected",
                ActionKind.WITHDRAW to "workflow.instance.withdrawn",
                ActionKind.RESUBMIT to "workflow.instance.resubmitted",
                ActionKind.REASSIGN to "workflow.task.reassigned",
            )
    }
}
