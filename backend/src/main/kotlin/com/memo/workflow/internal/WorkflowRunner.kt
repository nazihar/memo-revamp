package com.memo.workflow.internal

import com.memo.common.context.Actor
import com.memo.common.id.IdGenerator
import com.memo.workflow.ActionKind
import com.memo.workflow.MyTask
import com.memo.workflow.StepRef
import com.memo.workflow.TaskRole
import com.memo.workflow.WorkflowRefusedException
import com.memo.workflow.WorkflowSubject
import com.memo.workflow.WorkflowView
import com.memo.workflow.definition.Definition
import com.memo.workflow.engine.Ask
import com.memo.workflow.engine.Command
import com.memo.workflow.engine.Decision
import com.memo.workflow.engine.Delegate
import com.memo.workflow.engine.Forward
import com.memo.workflow.engine.InstanceState
import com.memo.workflow.engine.Outcome
import com.memo.workflow.engine.Reassign
import com.memo.workflow.engine.Refer
import com.memo.workflow.engine.Resubmit
import com.memo.workflow.engine.StepKind
import com.memo.workflow.engine.StepState
import com.memo.workflow.engine.TaskKind
import com.memo.workflow.engine.TaskState
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
    private val compiled: CompiledDefinitions,
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
        definitionKey: String = DefinitionStore.SEQUENTIAL_APPROVAL,
    ): WorkflowView {
        val subject = subject(subjectType)
        subject.lock(subjectId)
        if (instances.instanceOfSubject(subjectType, subjectId) != null) throw WorkflowRefusedException(ALREADY_STARTED)
        // New instances pin the newest published version (ADR-0038).
        val version = definitions.publishedVersion(definitionKey)
        val definition = compiled.get(version)
        val outcome = accepted(transition(subject, subjectId, route, definition).start(actor.userId, route, remark))
        val instanceId = ids.next()
        instances.insert(instanceId, subjectType, subjectId, version, outcome.state, now())
        val loaded = LoadedInstance(instanceId, subjectType, subjectId, 0, outcome.state, definition)
        return record(loaded, null, subject, outcome, actor)
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
        val outcome = accepted(transition(subject, loaded.subjectId, people, loaded.definition).apply(loaded.state, cmd))
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

    /** The open task [user] holds on the subject, decisions first (design E03 §12). */
    fun myTask(
        subjectType: String,
        subjectId: UUID,
        user: UUID,
    ): MyTask? {
        val loaded = instances.instanceOfSubject(subjectType, subjectId)?.let { instances.load(it, lock = false) }
        val task =
            loaded
                ?.state
                ?.openTasks
                ?.filter { it.assignee == user }
                ?.minByOrNull { it.kind.ordinal }
        return task?.let { describe(loaded, it.id) }
    }

    fun task(taskId: UUID): MyTask? {
        val loaded = instances.instanceOfTask(taskId)?.let { instances.load(it, lock = false) }
        return loaded?.let { describe(it, taskId) }
    }

    private fun describe(
        loaded: LoadedInstance,
        taskId: UUID,
    ): MyTask? {
        val (step, task) = loaded.state.findTask(taskId)?.takeIf { it.second.state == TaskState.OPEN } ?: return null
        val node = loaded.definition.stepsById[step.nodeId]
        val positive = if (step.kind == StepKind.REVIEW) ActionKind.RECOMMEND else ActionKind.APPROVE
        val decides = task.kind == TaskKind.DECIDE
        val targets =
            loaded.state.round.steps
                .filter { it.state == StepState.DONE && it.ordinal < step.ordinal }
                .map { StepRef(it.id, it.name, it.assignee) }
        return MyTask(
            id = task.id,
            role = TaskRole.valueOf(task.kind.name),
            blocked = task.blocked,
            waiting = task.waiting,
            delegated = task.onBehalfOf != null,
            stepName = step.name,
            positive = positive,
            actions = if (decides) node?.offeredActions.orEmpty().map { ActionKind.valueOf(it.name) } else emptyList(),
            sendBackTargets = if (decides) targets else emptyList(),
        )
    }

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

    /** The engine for one action: eligibility of everyone it may hand a task to, and the subject's attributes on demand. */
    private fun transition(
        subject: WorkflowSubject,
        subjectId: UUID,
        people: Collection<UUID>,
        definition: Definition,
    ): Transition {
        val named = definition.steps.flatMap { step -> listOfNotNull(step.assignee?.userId) + step.assignees.mapNotNull { it.userId } }
        val ineligible = subject.ineligible(subjectId, (people + named).toSet())
        val context = TransitionContext(ids::next, { ineligible[it] }, settings.maxApprovers()) { subject.attributeValues(subjectId) }
        return Transition(context, definition)
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
        is Forward -> listOf(command.to)
        is Delegate -> listOf(command.to)
        is Ask -> listOf(command.to)
        is Refer -> command.to
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
