package com.memo.workflow.engine

import com.memo.workflow.ActionKind
import com.memo.workflow.definition.AssigneeKind
import com.memo.workflow.definition.CompiledCondition
import com.memo.workflow.definition.ConditionOutcome
import com.memo.workflow.definition.Conditions
import com.memo.workflow.definition.Definition
import com.memo.workflow.definition.StepDef
import com.memo.workflow.definition.StepType
import java.time.Instant
import java.util.UUID

/** Where a walk through the definition stopped: the round as it is now, what to write, and the automatic actions taken. */
internal data class Walk(
    val round: Round,
    val effects: List<Effect>,
    val followUps: List<ActionRecord>,
    /** The walk reached the end: the round is approved. */
    val finished: Boolean,
)

/**
 * Walks a round through its definition (design E03 §4.2): from a step that just completed (or the start) through
 * branches, notifications and escalations to the next step that needs a person, or to the end. Pure, like [Transition].
 */
internal class Rounds(
    private val context: TransitionContext,
    private val definition: Definition,
) {
    private val compiled = mutableMapOf<String, CompiledCondition>()
    private val values: Map<String, Any?> by lazy { context.attributes() }

    /** A new round, entered at the definition's start. */
    fun open(
        number: Int,
        route: List<UUID>,
        people: People,
    ): Walk = enter(Round(number, RoundOutcome.OPEN, emptyList(), route), definition.start, people, emptyList(), emptyList())

    /** After [from] completed: the next approver of the route, or the definition step after [from]'s. */
    fun advance(
        round: Round,
        from: Step,
        people: People,
    ): Walk {
        val entry = from.routeIndex?.plus(1)?.takeIf { it < round.route.size }
        return if (entry != null) {
            val (next, step) = routeEntry(round, from.nodeId, entry)
            Walk(next, listOf(Effect.TaskCreated(step.id, step.tasks.last())), emptyList(), finished = false)
        } else {
            enter(round, checkNotNull(definition.step(from.nodeId).next), people, emptyList(), emptyList())
        }
    }

    fun newTask(assignee: UUID): Task = Task(context.newId(), assignee, TaskState.OPEN, context.eligibility(assignee))

    private tailrec fun enter(
        round: Round,
        at: String,
        people: People,
        effects: List<Effect>,
        followUps: List<ActionRecord>,
    ): Walk {
        if (at == Definition.END) return Walk(round, effects, followUps, finished = true)
        val node = definition.step(at)
        return when (node.type) {
            StepType.ROUTE -> {
                if (round.route.isEmpty()) {
                    enter(round, checkNotNull(node.next), people, effects, followUps)
                } else {
                    val (next, step) = routeEntry(round, node.id, 0)
                    Walk(next, effects + Effect.TaskCreated(step.id, step.tasks.last()), followUps, finished = false)
                }
            }

            StepType.REVIEW, StepType.APPROVE -> {
                val kind = if (node.type == StepType.REVIEW) StepKind.REVIEW else StepKind.APPROVE
                val step = activated(round, node, kind, checkNotNull(checkNotNull(node.assignee).userId), null)
                Walk(round.copy(steps = round.steps + step), effects + Effect.TaskCreated(step.id, step.tasks.last()), followUps, false)
            }

            StepType.PARALLEL -> {
                val holders = node.assignees.map { checkNotNull(it.userId) }
                val step =
                    activated(round, node, StepKind.PARALLEL, holders.first(), null).copy(tasks = holders.map(::newTask))
                Walk(round.copy(steps = round.steps + step), effects + step.tasks.map { Effect.TaskCreated(step.id, it) }, followUps, false)
            }

            StepType.BRANCH -> {
                val (next, record) = branch(node, round.number, people.actor)
                enter(round, next, people, effects, followUps + record)
            }

            StepType.NOTIFY -> {
                val recipients =
                    node.recipients.map {
                        if (it.kind ==
                            AssigneeKind.INITIATOR
                        ) {
                            people.initiator
                        } else {
                            checkNotNull(it.userId)
                        }
                    }
                val message = checkNotNull(node.message)
                val record = automatic(ActionKind.NOTIFY, round.number, people.actor, mapOf("step" to node.id, "message" to message))
                enter(
                    round,
                    checkNotNull(node.next),
                    people,
                    effects + Effect.Notify(node.id, recipients.distinct(), message),
                    followUps + record,
                )
            }

            StepType.ESCALATE_TO_FORUM -> {
                val forum = checkNotNull(node.forum)
                val record = automatic(ActionKind.ESCALATE_TO_FORUM, round.number, people.actor, mapOf("step" to node.id, "forum" to forum))
                enter(round, checkNotNull(node.next), people, effects + Effect.EscalateToForum(node.id, forum), followUps + record)
            }

            else -> {
                throw IllegalStateException("step type ${node.type.json} cannot run")
            }
        }
    }

    /** The route entry [index] activated: its pending step if the round has one, else a new step. */
    private fun routeEntry(
        round: Round,
        nodeId: String,
        index: Int,
    ): Pair<Round, Step> {
        val pending = round.steps.lastOrNull { it.nodeId == nodeId && it.routeIndex == index && it.state == StepState.PENDING }
        if (pending != null) {
            val active = pending.copy(state = StepState.ACTIVE, tasks = pending.tasks + newTask(pending.assignee))
            return round.replaceStep(pending.id) { active } to active
        }
        val node = definition.step(nodeId)
        val step = activated(round, node, StepKind.APPROVE, round.route[index], index)
        // The rest of the route waits as pending steps, so the round shows who comes next (as in E01).
        val waiting =
            (index + 1 until round.route.size).mapIndexed { offset, i ->
                Step(
                    context.newId(),
                    step.ordinal + offset + 1,
                    round.route[i],
                    StepState.PENDING,
                    emptyList(),
                    nodeId,
                    StepKind.APPROVE,
                    i,
                    node.name,
                )
            }
        return round.copy(steps = round.steps + step + waiting) to step
    }

    private fun activated(
        round: Round,
        node: StepDef,
        kind: StepKind,
        assignee: UUID,
        routeIndex: Int?,
    ): Step =
        Step(
            id = context.newId(),
            ordinal = (round.steps.maxOfOrNull { it.ordinal } ?: 0) + 1,
            assignee = assignee,
            state = StepState.ACTIVE,
            tasks = listOf(newTask(assignee)),
            nodeId = node.id,
            kind = kind,
            routeIndex = routeIndex,
            name = node.name,
        )

    /** The first branch whose condition is true, else `otherwise`; the record keeps the inputs and what failed. */
    private fun branch(
        node: StepDef,
        round: Int,
        actor: UUID,
    ): Pair<String, ActionRecord> {
        val inputs = linkedMapOf<String, Any?>()
        val failed = mutableListOf<Int>()
        val taken =
            node.branches.indices.firstOrNull { i ->
                val condition = condition(node.branches[i].condition)
                condition.reads.forEach { inputs[it] = shown(values[it]) }
                when (val outcome = condition.evaluate(values)) {
                    is ConditionOutcome.Value -> outcome.result
                    is ConditionOutcome.Failed -> false.also { failed += i }
                }
            }
        val next = taken?.let { node.branches[it].next } ?: checkNotNull(node.otherwise)
        val details = mapOf("step" to node.id, "taken" to (taken ?: "otherwise"), "inputs" to inputs, "failed" to failed)
        return next to automatic(ActionKind.ROUTE, round, actor, details)
    }

    private fun condition(text: String): CompiledCondition =
        compiled.getOrPut(text) {
            val (condition, error) = Conditions.compile(text, definition.attributes)
            checkNotNull(condition) { "a published condition does not compile: ${error?.message}" }
        }

    private fun automatic(
        kind: ActionKind,
        round: Int,
        actor: UUID,
        details: Map<String, Any?>,
    ): ActionRecord = ActionRecord(kind, round, null, actor, null, details)

    private companion object {
        const val MAX_SHOWN = 200

        /** Attribute values as the history shows them: JSON-friendly and short. */
        fun shown(value: Any?): Any? =
            when (value) {
                null, is Boolean, is Number -> value
                is Instant -> value.toString()
                else -> value.toString().take(MAX_SHOWN)
            }
    }
}

/** Who a walk acts for: the instance's initiator (notify recipients) and the person whose action led here. */
internal data class People(
    val initiator: UUID,
    val actor: UUID,
)
