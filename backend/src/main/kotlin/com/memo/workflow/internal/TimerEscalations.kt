package com.memo.workflow.internal

import com.memo.common.context.Actor
import com.memo.org.OrgDirectory
import com.memo.workflow.WorkflowRefusedException
import com.memo.workflow.WorkflowSubject
import com.memo.workflow.definition.EscalateAction
import com.memo.workflow.engine.Escalate
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * What an escalation timer does (ADR-0041, design E05 §4.3): it resolves the step's escalation target and runs
 * [Escalate] as the workflow ([WORKFLOW_ACTOR], never a user). `notify` tells the named assignee's people or the
 * holder's supervisor; `reassign` moves the task to the first person the named assignee resolves to; `nextLevel` to the
 * holder's supervisor by the reporting line. The initiator and the holder are never targets; nobody left means a
 * blocked task for an administrator. Inside a tenant transaction.
 */
@Component
class TimerEscalations(
    private val instances: InstanceStore,
    private val subjects: List<WorkflowSubject>,
    private val directory: OrgDirectory,
    private val resolvers: OrgResolvers,
    private val runner: ObjectProvider<WorkflowRunner>,
) {
    /** Takes the locks an action takes, subject then instance, so a firing escalation waits behind actions (no deadlock). */
    fun lock(instanceId: UUID) {
        val ref = instances.subjectOfInstance(instanceId) ?: return
        subjects.singleOrNull { it.type == ref.type }?.lock(ref.id)
        instances.load(instanceId, lock = true)
    }

    fun escalate(
        instanceId: UUID,
        taskId: UUID,
    ) {
        val loaded = instances.load(instanceId, lock = true)
        val found = loaded?.state?.findTask(taskId)
        val escalate =
            found?.let { (step, _) ->
                loaded.definition.stepsById[step.nodeId]
                    ?.sla
                    ?.escalate
            }
        if (loaded == null || found == null || escalate == null) return
        val task = found.second
        val initiator = loaded.state.initiator
        val holder = task.onBehalfOf ?: task.assignee
        val excluded = setOfNotNull(initiator, holder)
        val named = escalate.to?.let { resolvers.resolver().resolve(it, initiator).people }.orEmpty()
        val supervisor = holder?.let(::supervisorOf)
        val command = { actor: UUID ->
            when (escalate.action) {
                EscalateAction.NOTIFY -> {
                    val targets = named.ifEmpty { listOfNotNull(supervisor) }.filterNot { it in excluded }
                    Escalate(actor, taskId, escalate.action.json, move = false, to = null, targets = targets)
                }

                EscalateAction.REASSIGN -> {
                    Escalate(actor, taskId, escalate.action.json, move = true, to = named.firstOrNull { it !in excluded })
                }

                EscalateAction.NEXT_LEVEL -> {
                    Escalate(actor, taskId, escalate.action.json, move = true, to = supervisor?.takeUnless { it in excluded })
                }
            }
        }
        // A task that moved on in the meantime is the engine's to refuse; the timer has done its part either way.
        runCatching { runner.getObject().onInstance(instanceId, Actor.User(WORKFLOW_ACTOR), command) }
            .onFailure { if (it !is WorkflowRefusedException) throw it }
    }

    /** The holder of the position [person]'s position reports to, if any. */
    private fun supervisorOf(person: UUID): UUID? =
        directory
            .positionsOf(listOf(person))[person]
            ?.let { directory.reportsTo(it.id, 1) }
            ?.let { above -> directory.holders(listOf(above.id))[above.id] }

    companion object {
        /** The workflow itself as the actor of what timers do: never a user, shown as "Workflow". */
        val WORKFLOW_ACTOR: UUID = UUID(0, 0xF10FL)
    }
}
