package com.memo.workflow.engine

import com.memo.workflow.ActionKind
import com.memo.workflow.GroupMode
import com.memo.workflow.RouteEntry
import com.memo.workflow.definition.Assignee
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
 * branches, notifications and escalations to the next step that needs a person, or to the end. Steps that need people
 * go to whoever their assignees resolve to now (design E04 §4.1). Pure, like [Transition].
 */
internal class Rounds(
    private val context: TransitionContext,
    private val definition: Definition,
) {
    private val branches = Branches(context, definition)
    private val carries = Carries(context.carried)
    private val candidates = Candidates(context)
    private val steps = Activations(context)

    /** A new round, entered at the definition's start. */
    fun open(
        number: Int,
        route: List<RouteEntry>,
        people: People,
    ): Walk = enter(Round(number, RoundOutcome.OPEN, emptyList(), route), definition.start, people, emptyList(), emptyList())

    /** After [from] completed: the next approver of the route, or the definition step after [from]'s. */
    fun advance(
        round: Round,
        from: Step,
        people: People,
    ): Walk {
        // The next approval group of a DoA rule, when there is one (design E04 §4.3).
        val group = from.doaGroup?.plus(1)?.takeIf { it < context.doa().groups.size }
        if (group != null) {
            val (next, step) = doaGroup(round, definition.step(from.nodeId), people.initiator, group)
            return Walk(next, created(step), emptyList(), finished = false)
        }
        val (entry, carried) = from.routeIndex?.let { carries.uncarried(round, from.nodeId, it + 1) } ?: (null to emptyList())
        val skipped = carries.skip(round, from.nodeId, carried)
        return if (entry != null) {
            val (next, step) = routeEntry(skipped, from.nodeId, entry, people.initiator)
            Walk(next, created(step), carried, finished = false)
        } else {
            enter(skipped, checkNotNull(definition.step(from.nodeId).next), people, emptyList(), carried)
        }
    }

    /** A new round that starts again at [from] (the `resume` policy): a route entry at its approver's place in the new route. */
    fun resume(
        number: Int,
        route: List<RouteEntry>,
        people: People,
        from: Step,
        previous: RouteEntry?,
    ): Walk {
        val round = Round(number, RoundOutcome.OPEN, emptyList(), route)
        val index = from.routeIndex
        return if (index != null && route.isNotEmpty()) {
            val (next, step) = routeEntry(round, from.nodeId, previous?.let(route::indexOf)?.coerceAtLeast(0) ?: 0, people.initiator)
            Walk(next, created(step), emptyList(), finished = false)
        } else {
            enter(round, from.nodeId, people, emptyList(), emptyList())
        }
    }

    fun newTask(assignee: UUID): Task = steps.newTask(assignee)

    /**
     * [target] runs again after a send-back (design E03 §4.4): a route entry restarts there with the rest of the route
     * waiting behind it, a step with one holder goes to the person who held it, and a step shared by several people (or
     * held by nobody) resolves its assignees again.
     */
    fun reactivate(
        round: Round,
        target: Step,
        initiator: UUID,
        recused: Set<UUID> = emptySet(),
    ): Walk {
        val node = definition.step(target.nodeId)
        val index = target.routeIndex
        val holder = target.assignee
        val (next, step) =
            when {
                node.type == StepType.DOA -> {
                    doaGroup(round, node, initiator, target.doaGroup ?: 0, recused)
                }

                // A route entry whose approver stood aside: the initiator chose them, so an administrator assigns it.
                index != null && recused.isNotEmpty() -> {
                    val found = Found(emptyList(), mapOf("excluded" to recused.map { mapOf("person" to it.toString(), "rule" to "SOD-4") }))
                    steps.shaped(round, node, found).copy(routeIndex = index).let { round.copy(steps = round.steps + it) to it }
                }

                index != null -> {
                    routeEntry(round, target.nodeId, index, initiator)
                }

                target.shared || holder == null || recused.isNotEmpty() -> {
                    steps.shaped(round, node, candidates.of(node, initiator, round, recused)).let {
                        round.copy(steps = round.steps + it) to
                            it
                    }
                }

                else -> {
                    steps.activated(round, node, target.kind, holder, null).let { round.copy(steps = round.steps + it) to it }
                }
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
            StepType.ROUTE -> {
                route(round, node, people, effects, followUps)
            }

            StepType.REVIEW, StepType.APPROVE, StepType.PARALLEL -> {
                person(round, node, people, effects, followUps)
            }

            StepType.DOA -> {
                doaGroup(round, node, people.initiator, 0).let { (next, step) ->
                    Walk(next, effects + created(step), followUps, false)
                }
            }

            StepType.BRANCH, StepType.NOTIFY, StepType.ESCALATE_TO_FORUM -> {
                pass(round, node, people, effects, followUps)
            }

            else -> {
                throw IllegalStateException("step type ${node.type.json} cannot run")
            }
        }
    }

    /** The initiator's route: its first approver whose approval does not carry over, or on past it when all do. */
    private fun route(
        round: Round,
        node: StepDef,
        people: People,
        effects: List<Effect>,
        followUps: List<ActionRecord>,
    ): Walk {
        val (first, carried) = carries.uncarried(round, node.id, 0)
        return if (first == null) {
            enter(round, checkNotNull(node.next), people, effects, followUps + carried)
        } else {
            val (next, step) = routeEntry(round, node.id, first, people.initiator)
            Walk(carries.skip(next, node.id, carried), effects + created(step), followUps + carried, false)
        }
    }

    /** A step that needs people: its task(s) open, unless its one holder's approval carries over (then the walk goes on). */
    private fun person(
        round: Round,
        node: StepDef,
        people: People,
        effects: List<Effect>,
        followUps: List<ActionRecord>,
    ): Walk {
        val found = candidates.of(node, people.initiator, round)
        val holder = found.people.singleOrNull()
        return if (node.type != StepType.PARALLEL && holder != null && carries.carries(node.id, holder)) {
            enter(round, checkNotNull(node.next), people, effects, followUps + carries.record(node.id, holder, round.number))
        } else {
            val step = steps.shaped(round, node, found)
            Walk(round.copy(steps = round.steps + step), effects + step.tasks.map { Effect.TaskCreated(step.id, it) }, followUps, false)
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
                val recipients = candidates.recipients(node, people.initiator)
                val message = checkNotNull(node.message)
                val record = automatic(ActionKind.NOTIFY, round.number, people.actor, mapOf("step" to node.id, "message" to message))
                val notify = Effect.Notify(node.id, recipients, message)
                enter(round, checkNotNull(node.next), people, effects + notify, followUps + record)
            }

            else -> {
                val forum = checkNotNull(node.forum)
                val record = automatic(ActionKind.ESCALATE_TO_FORUM, round.number, people.actor, mapOf("step" to node.id, "forum" to forum))
                enter(round, checkNotNull(node.next), people, effects + Effect.EscalateToForum(node.id, forum), followUps + record)
            }
        }

    /**
     * Approval group [group] of the DoA rule that matched (design E04 §4.3): its members resolved, less the
     * separation-of-duties exclusions; the step needs the group's approvals, and waits for an administrator for any it
     * cannot find (or for all of them when no rule matched).
     */
    private fun doaGroup(
        round: Round,
        node: StepDef,
        initiator: UUID,
        group: Int,
        recused: Set<UUID> = emptySet(),
    ): Pair<Round, Step> {
        val match = context.doa()
        val wanted = match.groups.getOrNull(group)
        val found =
            if (wanted == null) {
                Found(emptyList(), emptyMap())
            } else {
                candidates.of(node, initiator, round, recused, Members(wanted.members, group = group))
            }
        val record = match.record + mapOf("group" to group, "needed" to (wanted?.needed ?: 1)) + found.record
        val step = steps.group(round, node, found.copy(record = record), wanted?.needed ?: 1, group)
        return round.copy(steps = round.steps + step) to step
    }

    /**
     * The route entry [index] activated (its pending step if the round has one, else a new step): a person gets the task;
     * a position or a group resolves now, as a definition's step would (design E04 §4.7).
     */
    private fun routeEntry(
        round: Round,
        nodeId: String,
        index: Int,
        initiator: UUID,
    ): Pair<Round, Step> {
        val pending = round.steps.lastOrNull { it.nodeId == nodeId && it.routeIndex == index && it.state == StepState.PENDING }
        val node = definition.step(nodeId)
        val entry = round.route[index]
        val person = entry.userId
        val resolved =
            if (person != null) {
                pending?.copy(state = StepState.ACTIVE, tasks = pending.tasks + newTask(person))
                    ?: steps.activated(round, node, StepKind.APPROVE, person, index)
            } else {
                val members =
                    entry.positions.map { Assignee(AssigneeKind.POSITION, positionId = it) } +
                        entry.people.map { Assignee(AssigneeKind.PERSON, userId = it) }
                val found = candidates.of(node, initiator, round, members = Members(members, routeIndex = index))
                val needed = entry.group?.let { if (it.mode == GroupMode.ALL) it.members.size else 1 }
                val step = steps.entry(round, node, found, needed, index)
                pending?.let { step.copy(id = it.id, ordinal = it.ordinal) } ?: step
            }
        if (pending != null) return round.replaceStep(pending.id) { resolved } to resolved
        val step = resolved
        // The rest of the route waits as pending steps, so the round shows who comes next (as in E01).
        val waiting =
            (index + 1 until round.route.size).mapIndexed { offset, i ->
                Step(
                    context.newId(),
                    step.ordinal + offset + 1,
                    round.route[i].userId,
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
}

/** A task created for each of [step]'s tasks. */
private fun created(step: Step): List<Effect> = step.tasks.map { Effect.TaskCreated(step.id, it) }

/** Who a walk acts for: the instance's initiator (notify recipients) and the person whose action led here. */
internal data class People(
    val initiator: UUID,
    val actor: UUID,
)
