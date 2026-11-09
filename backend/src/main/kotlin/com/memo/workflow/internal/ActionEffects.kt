package com.memo.workflow.internal

import com.memo.audit.AuditLog
import com.memo.common.tenant.TenantDsl
import com.memo.workflow.ActionKind
import com.memo.workflow.TaskClosure
import com.memo.workflow.WorkflowActionTaken
import com.memo.workflow.WorkflowTaskClosed
import com.memo.workflow.WorkflowTaskCreated
import com.memo.workflow.engine.Effect
import com.memo.workflow.engine.InstanceState
import com.memo.workflow.engine.Outcome
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
        outcome.effects.forEach { effect ->
            when (effect) {
                is Effect.TaskClosed -> {
                    val assignee = assignees.getValue(effect.taskId)
                    events.publishEvent(
                        WorkflowTaskClosed(
                            tenant,
                            instance.id,
                            instance.subjectType,
                            instance.subjectId,
                            effect.taskId,
                            assignee,
                            TaskClosure.valueOf(effect.state.name),
                        ),
                    )
                }

                is Effect.TaskCreated -> {
                    val task = effect.task
                    events.publishEvent(
                        WorkflowTaskCreated(
                            tenant,
                            instance.id,
                            instance.subjectType,
                            instance.subjectId,
                            task.id,
                            task.assignee,
                            task.blocked,
                        ),
                    )
                }

                else -> {}
            }
        }
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
    }

    private companion object {
        const val TASK = "workflow_task"
        const val INSTANCE = "workflow_instance"
        val AUDIT_ACTIONS =
            mapOf(
                ActionKind.SUBMIT to "workflow.instance.started",
                ActionKind.APPROVE to "workflow.task.approved",
                ActionKind.RETURN to "workflow.task.returned",
                ActionKind.REJECT to "workflow.task.rejected",
                ActionKind.WITHDRAW to "workflow.instance.withdrawn",
                ActionKind.RESUBMIT to "workflow.instance.resubmitted",
                ActionKind.REASSIGN to "workflow.task.reassigned",
            )
    }
}
