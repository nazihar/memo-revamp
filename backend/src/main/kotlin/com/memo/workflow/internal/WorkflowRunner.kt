package com.memo.workflow.internal

import com.memo.common.context.Actor
import com.memo.common.id.IdGenerator
import com.memo.workflow.WorkflowRefusedException
import com.memo.workflow.WorkflowSubject
import com.memo.workflow.WorkflowView
import com.memo.workflow.engine.Command
import com.memo.workflow.engine.Decision
import com.memo.workflow.engine.InstanceState
import com.memo.workflow.engine.Outcome
import com.memo.workflow.engine.Reassign
import com.memo.workflow.engine.Resubmit
import com.memo.workflow.engine.Transition
import com.memo.workflow.engine.TransitionContext
import org.jooq.JSONB
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/** Loads, decides and writes (ADR-0035): the transaction-side half of the engine, behind [com.memo.workflow.Workflows]. */
@Component
class WorkflowRunner(
    private val instances: InstanceStore,
    private val writer: InstanceWriter,
    private val definitions: DefinitionStore,
    private val settings: WorkflowSettings,
    private val subjects: List<WorkflowSubject>,
    private val effects: ActionEffects,
    private val ids: IdGenerator,
    private val json: JsonMapper,
    private val clock: Clock,
) {
    fun start(
        subjectType: String,
        subjectId: UUID,
        route: List<UUID>,
        remark: String?,
        actor: Actor.User,
    ): WorkflowView {
        val subject = subject(subjectType)
        subject.lock(subjectId)
        if (instances.instanceOfSubject(subjectType, subjectId) != null) throw WorkflowRefusedException(ALREADY_STARTED)
        val outcome = accepted(transition(subject, subjectId, route).start(actor.userId, route, remark))
        val instanceId = ids.next()
        val definition = definitions.publishedVersion(DefinitionStore.SEQUENTIAL_APPROVAL)
        instances.insert(instanceId, subjectType, subjectId, definition, outcome.state, now())
        return record(LoadedInstance(instanceId, subjectType, subjectId, 0, outcome.state), null, subject, outcome, actor)
    }

    fun onTask(
        taskId: UUID,
        actor: Actor.User,
        command: (UUID) -> Command,
    ): WorkflowView {
        val instanceId = instances.instanceOfTask(taskId) ?: throw WorkflowRefusedException(TASK_NOT_FOUND)
        return onInstance(instanceId, actor, command)
    }

    fun onInstance(
        instanceId: UUID,
        actor: Actor.User,
        command: (UUID) -> Command,
    ): WorkflowView {
        val ref = instances.subjectOfInstance(instanceId) ?: throw WorkflowRefusedException(INSTANCE_NOT_FOUND)
        val subject = subject(ref.type)
        subject.lock(ref.id)
        val loaded = instances.load(instanceId, lock = true) ?: throw WorkflowRefusedException(INSTANCE_NOT_FOUND)
        val cmd = command(actor.userId)
        val people =
            loaded.state.round.steps
                .map { it.assignee } + newPeople(cmd)
        val outcome = accepted(transition(subject, loaded.subjectId, people).apply(loaded.state, cmd))
        return record(loaded, loaded.state, subject, outcome, actor)
    }

    /** Where the subject's instance is now, without locking it; null if it was never submitted. */
    fun viewOf(
        subjectType: String,
        subjectId: UUID,
    ): WorkflowView? =
        instances
            .instanceOfSubject(subjectType, subjectId)
            ?.let { instances.load(it, lock = false) }
            ?.let { view(it, it.state, it.version) }

    private fun record(
        instance: LoadedInstance,
        before: InstanceState?,
        subject: WorkflowSubject,
        outcome: Outcome,
        actor: Actor.User,
    ): WorkflowView {
        subject.beforeAction(instance.subjectId, outcome.action.kind)
        val snapshot = subject.snapshot(instance.subjectId)
        val snapshotJson = JSONB.valueOf(json.writeValueAsString(snapshot))
        val (actionId, version) =
            writer.write(instance.id, before, instance.version, outcome, snapshotJson, actor.actingAs, now())
        effects.audit(instance, outcome, snapshot)
        effects.publish(instance, before, outcome, actionId)
        return view(instance, outcome.state, version)
    }

    private fun transition(
        subject: WorkflowSubject,
        subjectId: UUID,
        people: Collection<UUID>,
    ): Transition {
        val ineligible = subject.ineligible(subjectId, people.toSet())
        return Transition(TransitionContext(ids::next, { ineligible[it] }, settings.maxApprovers()))
    }

    private fun subject(type: String): WorkflowSubject =
        subjects.singleOrNull { it.type == type } ?: throw IllegalArgumentException("no workflow subject of type '$type'")

    private fun now(): OffsetDateTime = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC)

    companion object {
        const val ALREADY_STARTED = "ALREADY_STARTED"
        const val TASK_NOT_FOUND = "TASK_NOT_FOUND"
        const val INSTANCE_NOT_FOUND = "INSTANCE_NOT_FOUND"
    }
}

private fun newPeople(command: Command): List<UUID> =
    when (command) {
        is Resubmit -> command.route
        is Reassign -> listOf(command.newAssignee)
        else -> emptyList()
    }

private fun accepted(decision: Decision): Outcome =
    when (decision) {
        is Decision.Accepted -> decision.outcome
        is Decision.Refused -> throw WorkflowRefusedException(decision.reason.name)
    }

private fun view(
    instance: LoadedInstance,
    state: InstanceState,
    version: Long,
): WorkflowView {
    val open = state.openTasks.firstOrNull()
    return WorkflowView(
        instanceId = instance.id,
        subjectType = instance.subjectType,
        subjectId = instance.subjectId,
        status = state.status,
        round = state.round.number,
        currentAssignees = state.currentAssignees,
        openTaskId = open?.id,
        openTaskBlocked = open?.blocked,
        version = version,
    )
}
