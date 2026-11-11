package com.memo.workflow.engine

import com.memo.workflow.ActionKind
import com.memo.workflow.definition.AssigneeKind
import com.memo.workflow.definition.Definition
import com.memo.workflow.definition.StepDef
import com.memo.workflow.definition.StepType
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
    private val branches = Branches(context, definition)
    private val carries = Carries(context.carried)

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
        val (entry, carried) = from.routeIndex?.let { carries.uncarried(round, from.nodeId, it + 1) } ?: (null to emptyList())
        val skipped = carries.skip(round, from.nodeId, carried)
        return if (entry != null) {
            val (next, step) = routeEntry(skipped, from.nodeId, entry)
            Walk(next, listOf(Effect.TaskCreated(step.id, step.tasks.last())), carried, finished = false)
        } else {
            enter(skipped, checkNotNull(definition.step(from.nodeId).next), people, emptyList(), carried)
        }
    }

    /** A new round that starts again at [from] (the `resume` policy): a route entry at its approver's place in the new route. */
    fun resume(
        number: Int,
        route: List<UUID>,
        people: People,
        from: Step,
    ): Walk {
        val round = Round(number, RoundOutcome.OPEN, emptyList(), route)
        val index = from.routeIndex
        return if (index != null && route.isNotEmpty()) {
            val (next, step) = routeEntry(round, from.nodeId, route.indexOf(from.assignee).coerceAtLeast(0))
            Walk(next, listOf(Effect.TaskCreated(step.id, step.tasks.last())), emptyList(), finished = false)
        } else {
            enter(round, from.nodeId, people, emptyList(), emptyList())
        }
    }

    fun newTask(assignee: UUID): Task = Task(context.newId(), assignee, TaskState.OPEN, context.eligibility(assignee))

    /**
     * [target] runs again after a send-back (design E03 §4.4): a route entry restarts there with the rest of the route
     * waiting behind it, a parallel step asks all its holders again, any other step goes to the person who held it.
     */
    fun reactivate(
        round: Round,
        target: Step,
    ): Walk {
        val node = definition.step(target.nodeId)
        val index = target.routeIndex
        val (next, step) =
            when {
                index != null -> routeEntry(round, target.nodeId, index)
                target.kind == StepKind.PARALLEL -> parallel(round, node).let { round.copy(steps = round.steps + it) to it }
                else -> activated(round, node, target.kind, target.assignee, null).let { round.copy(steps = round.steps + it) to it }
            }
        return Walk(next, step.tasks.map { Effect.TaskCreated(step.id, it) }, emptyList(), finished = false)
    }

    /** From [at] on: steps without a person act and move on; the first step that needs a person stops the walk. */
    private fun enter(
        round: Round,
        at: String,
        people: People,
        effects: List<Effect>,
        followUps: List<ActionRecord>,
    ): Walk {
        if (at == Definition.END) return Walk(round, effects, followUps, finished = true)
        val node = definition.step(at)
        return when (node.type) {
            StepType.ROUTE, StepType.REVIEW, StepType.APPROVE, StepType.PARALLEL -> person(round, node, people, effects, followUps)
            StepType.BRANCH, StepType.NOTIFY, StepType.ESCALATE_TO_FORUM -> pass(round, node, people, effects, followUps)
            else -> throw IllegalStateException("step type ${node.type.json} cannot run")
        }
    }

    /** A step that needs people: its task(s) open, unless every approval it asks for carries over (then the walk goes on). */
    private fun person(
        round: Round,
        node: StepDef,
        people: People,
        effects: List<Effect>,
        followUps: List<ActionRecord>,
    ): Walk {
        val holder = node.assignee?.userId
        return when {
            node.type == StepType.ROUTE -> {
                val (first, carried) = carries.uncarried(round, node.id, 0)
                if (first == null) {
                    enter(round, checkNotNull(node.next), people, effects, followUps + carried)
                } else {
                    val (next, step) = routeEntry(round, node.id, first)
                    Walk(
                        carries.skip(next, node.id, carried),
                        effects + Effect.TaskCreated(step.id, step.tasks.last()),
                        followUps + carried,
                        false,
                    )
                }
            }

            node.type == StepType.PARALLEL -> {
                val step = parallel(round, node)
                Walk(round.copy(steps = round.steps + step), effects + step.tasks.map { Effect.TaskCreated(step.id, it) }, followUps, false)
            }

            holder != null && carries.carries(node.id, holder) -> {
                enter(round, checkNotNull(node.next), people, effects, followUps + carries.record(node.id, holder, round.number))
            }

            else -> {
                val kind = if (node.type == StepType.REVIEW) StepKind.REVIEW else StepKind.APPROVE
                val step = activated(round, node, kind, checkNotNull(holder), null)
                Walk(round.copy(steps = round.steps + step), effects + Effect.TaskCreated(step.id, step.tasks.last()), followUps, false)
            }
        }
    }

    /** A step without a person: a branch chooses the way on, a notification or an escalation is recorded and published. */
    private fun pass(
        round: Round,
        node: StepDef,
        people: People,
        effects: List<Effect>,
        followUps: List<ActionRecord>,
    ): Walk =
        when (node.type) {
            StepType.BRANCH -> {
                val (next, record) = branches.choose(node, round.number, people.actor)
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
                val notify = Effect.Notify(node.id, recipients.distinct(), message)
                enter(round, checkNotNull(node.next), people, effects + notify, followUps + record)
            }

            else -> {
                val forum = checkNotNull(node.forum)
                val record = automatic(ActionKind.ESCALATE_TO_FORUM, round.number, people.actor, mapOf("step" to node.id, "forum" to forum))
                enter(round, checkNotNull(node.next), people, effects + Effect.EscalateToForum(node.id, forum), followUps + record)
            }
        }

    private fun parallel(
        round: Round,
        node: StepDef,
    ): Step {
        val holders = node.assignees.map { checkNotNull(it.userId) }
        return activated(round, node, StepKind.PARALLEL, holders.first(), null).copy(tasks = holders.map(::newTask))
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
}

/** Who a walk acts for: the instance's initiator (notify recipients) and the person whose action led here. */
internal data class People(
    val initiator: UUID,
    val actor: UUID,
)
