package com.memo.workflow.internal

import com.memo.common.context.Actor
import com.memo.common.id.IdGenerator
import com.memo.workflow.ActionKind
import com.memo.workflow.InstanceStatus
import com.memo.workflow.MyTask
import com.memo.workflow.RouteEntry
import com.memo.workflow.StepRef
import com.memo.workflow.TaskRole
import com.memo.workflow.WorkflowRefusedException
import com.memo.workflow.WorkflowSubject
import com.memo.workflow.WorkflowView
import com.memo.workflow.definition.Definition
import com.memo.workflow.definition.ResubmitPolicy
import com.memo.workflow.engine.Ask
import com.memo.workflow.engine.Carried
import com.memo.workflow.engine.Command
import com.memo.workflow.engine.Decision
import com.memo.workflow.engine.Delegate
import com.memo.workflow.engine.Forward
import com.memo.workflow.engine.InstanceState
import com.memo.workflow.engine.Migrate
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
import tools.jackson.databind.JsonNode
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
    private val resolvers: OrgResolvers,
    private val substitutes: SubstitutesInForce,
    private val doa: DoaMatcher,
    private val orgLimits: ForwardLimits,
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
        entries: List<RouteEntry>? = null,
    ): WorkflowView {
        val subject = subject(subjectType)
        subject.lock(subjectId)
        if (instances.instanceOfSubject(subjectType, subjectId) != null) throw WorkflowRefusedException(ALREADY_STARTED)
        // New instances pin the newest published version (ADR-0038).
        val version = definitions.publishedVersion(definitionKey)
        val definition = compiled.get(version)
        // And the DoA version in force today, when the definition asks the DoA (design E04 §4.3).
        val doaVersion = doa.pin(definition)
        val outcome =
            accepted(
                transition(
                    subject,
                    subjectId,
                    actor.userId,
                    route + entries.orEmpty().flatMap { it.people },
                    definition,
                    doaVersion = doaVersion,
                ).start(actor.userId, route, remark, entries),
            )
        val instanceId = ids.next()
        instances.insert(instanceId, subjectType, subjectId, version, outcome.state, now(), doaVersion)
        val loaded = LoadedInstance(instanceId, subjectType, subjectId, 0, outcome.state, definition, version, doaVersion)
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
                .mapNotNull { it.assignee } + newPeople(cmd)
        val carried = carried(loaded, cmd, subject)
        val outcome =
            accepted(
                transition(subject, loaded.subjectId, loaded.state.initiator, people, loaded.definition, carried, loaded.doaVersionId)
                    .apply(loaded.state, cmd),
            )
        return record(loaded, loaded.state, subject, outcome, actor)
    }

    /**
     * Moves the instance from version [from] to the newer [to] of its definition (design E03 §4.7), each an id and its
     * number. Refused when it is no longer on [from] (migrated meanwhile) or a step it is at is missing or changed in [to].
     */
    fun migrate(
        instanceId: UUID,
        actor: Actor.User,
        from: Pair<UUID, Int>,
        to: Pair<UUID, Int>,
    ): WorkflowView {
        val ref = instances.subjectOfInstance(instanceId) ?: throw WorkflowRefusedException(INSTANCE_NOT_FOUND)
        val subject = subject(ref.type)
        subject.lock(ref.id)
        val found = instances.load(instanceId, lock = true)
        if (found?.definitionVersionId !=
            from.first
        ) {
            throw WorkflowRefusedException(if (found == null) INSTANCE_NOT_FOUND else VERSION_CHANGED)
        }
        val loaded = checkNotNull(found)
        val command = Migrate(actor.userId, compiled.get(to.first), from.second, to.second)
        val outcome =
            accepted(
                transition(
                    subject,
                    loaded.subjectId,
                    loaded.state.initiator,
                    emptyList(),
                    loaded.definition,
                    doaVersion = loaded.doaVersionId,
                ).apply(loaded.state, command),
            )
        val view = record(loaded, loaded.state, subject, outcome, actor)
        instances.repin(instanceId, to.first)
        return view
    }

    /**
     * A staff change reaches a running instance (design E04 §4.6): [Block] for a deactivated holder, [Reresolve] for a
     * step whose position changed holder; [actor] changed the organisation.
     */
    fun staffChange(
        instanceId: UUID,
        actor: Actor.User,
        command: (UUID) -> Command,
    ): WorkflowView = onInstance(instanceId, actor, command)

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

    /** The engine for one action: eligibility of everyone it may hand a task to, and the subject's attributes on demand. */
    private fun transition(
        subject: WorkflowSubject,
        subjectId: UUID,
        initiator: UUID,
        people: Collection<UUID>,
        definition: Definition,
        carried: Set<Carried> = emptySet(),
        doaVersion: UUID? = null,
    ): Transition {
        val named = definition.steps.flatMap { step -> listOfNotNull(step.assignee?.userId) + step.assignees.mapNotNull { it.userId } }
        val known = (people + named).toSet()
        val ineligible = subject.ineligible(subjectId, known).toMutableMap()
        // People a resolver finds are not known in advance: asked about once each, when they get a task.
        val asked = known.toMutableSet()
        val eligibility = { person: UUID ->
            if (asked.add(person)) ineligible.putAll(subject.ineligible(subjectId, listOf(person)))
            ineligible[person]
        }
        val interested = lazy { subject.interested(subjectId) }
        val authority = lazy { doa.match(doaVersion, subject, subjectId, initiator) }
        val context =
            TransitionContext(
                newId = ids::next,
                eligibility = eligibility,
                maxApprovers = settings.maxApprovers(),
                attributes = { subject.attributeValues(subjectId) },
                carried = carried,
                resolver = resolvers.resolver(),
                oncePerRound = settings.oncePerRound(),
                interested = interested::value,
                // SOD-3: a substitute never acts on an item they initiated or have an interest in (design E04 §4.4).
                substituteOf = { person -> substitutes.of(person)?.takeUnless { it == initiator || it in interested.value } },
                doa = authority::value,
                within = orgLimits::within,
            )
        return Transition(context, definition)
    }

    /**
     * For `skip-unchanged` (design E03 §4.6): who approved which step in the round before the one being walked, on the
     * subject as it is now (same snapshot), so the walk carries their approval over instead of asking again.
     */
    private fun carried(
        loaded: LoadedInstance,
        command: Command,
        subject: WorkflowSubject,
    ): Set<Carried> {
        val previous = if (command is Resubmit) loaded.state.round.number else loaded.state.round.number - 1
        if (loaded.definition.resubmitPolicy != ResubmitPolicy.SKIP_UNCHANGED || previous < 1) return emptySet()
        val now = json.valueToTree<JsonNode>(subject.snapshot(loaded.subjectId))
        return instances
            .approvals(loaded.id, previous)
            .filter { json.readTree(it.snapshot) == now }
            .map { Carried(it.nodeId, it.person) }
            .toSet()
    }

    private fun subject(type: String): WorkflowSubject =
        subjects.singleOrNull { it.type == type } ?: throw IllegalArgumentException("no workflow subject of type '$type'")

    private fun now(): OffsetDateTime = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC)

    companion object {
        const val ALREADY_STARTED = "ALREADY_STARTED"
        const val TASK_NOT_FOUND = "TASK_NOT_FOUND"
        const val INSTANCE_NOT_FOUND = "INSTANCE_NOT_FOUND"
        const val VERSION_CHANGED = "VERSION_CHANGED"
    }
}

private fun newPeople(command: Command): List<UUID> =
    when (command) {
        is Resubmit -> command.routeEntries.flatMap { it.people }
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
    val open = state.openDecisions.firstOrNull()
    val untouched =
        state.round.steps
            .flatMap { it.tasks }
            .all { it.state == TaskState.OPEN && it.kind == TaskKind.DECIDE && !it.waiting }
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
        recallable = state.status == InstanceStatus.RUNNING && untouched,
    )
}
