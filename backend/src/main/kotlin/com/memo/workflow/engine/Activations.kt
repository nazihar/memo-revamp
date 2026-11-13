package com.memo.workflow.engine

import com.memo.workflow.BlockedReason
import com.memo.workflow.definition.StepDef
import com.memo.workflow.definition.StepType
import java.util.UUID

/** How [Rounds] activates a definition step: the step row, its tasks and its recorded resolution. Pure, like [Transition]. */
internal class Activations(
    private val context: TransitionContext,
) {
    /** A task for [assignee], or for their substitute acting for them while a substitution is in force (design E04 §4.5). */
    fun newTask(assignee: UUID): Task {
        val substitute = context.substituteOf(assignee)
        return if (substitute == null) {
            Task(context.newId(), assignee, TaskState.OPEN, context.eligibility(assignee))
        } else {
            Task(context.newId(), substitute, TaskState.OPEN, context.eligibility(substitute), onBehalfOf = assignee)
        }
    }

    /**
     * A step for the people [found] (design E04 §4.1): one holder; several, of whom the step needs its parallel mode's
     * count (a parallel step) or the first to decide (any other step); or too few, held by a task without assignee until
     * an administrator assigns it (never skipped). The resolution is recorded on the step.
     */
    fun shaped(
        round: Round,
        node: StepDef,
        found: Found,
    ): Step {
        val kind =
            when (node.type) {
                StepType.REVIEW -> StepKind.REVIEW
                StepType.PARALLEL -> StepKind.PARALLEL
                else -> StepKind.APPROVE
            }
        val people = found.people
        val needed = if (kind == StepKind.PARALLEL) Parallel.needed(node, people.size) else 1
        val step =
            when {
                people.isEmpty() || people.size < needed -> {
                    activated(round, node, kind, null, null)
                        .copy(tasks = listOf(Task(context.newId(), null, TaskState.OPEN, BlockedReason.UNRESOLVED)))
                }

                people.size == 1 && kind != StepKind.PARALLEL -> {
                    activated(round, node, kind, people.single(), null)
                }

                else -> {
                    activated(round, node, kind, people.first(), null)
                        .copy(tasks = people.map(::newTask), needed = needed.takeIf { kind != StepKind.PARALLEL })
                }
            }
        return step.copy(resolution = found.record)
    }

    /**
     * An approval group of a DoA rule (design E04 §4.3): a task for each person [found]; the step needs [needed] of their
     * approvals, and holds a task without assignee for each approval it cannot find, for an administrator to assign.
     */
    fun group(
        round: Round,
        node: StepDef,
        found: Found,
        needed: Int,
        group: Int?,
    ): Step {
        val people = found.people
        val missing =
            List((needed - people.size).coerceAtLeast(0)) { Task(context.newId(), null, TaskState.OPEN, BlockedReason.UNRESOLVED) }
        return activated(round, node, StepKind.PARALLEL, null, null)
            .copy(assignee = people.firstOrNull(), tasks = people.map(::newTask) + missing, needed = needed, doaGroup = group)
            .copy(resolution = found.record)
    }

    /**
     * A route entry naming a position or a group (design E04 §4.7): a position goes to its holder, as a definition's
     * step would; a group needs [needed] approvals from its members.
     */
    fun entry(
        round: Round,
        node: StepDef,
        found: Found,
        needed: Int?,
        index: Int,
    ): Step = (if (needed == null) shaped(round, node, found) else group(round, node, found, needed, null)).copy(routeIndex = index)

    fun activated(
        round: Round,
        node: StepDef,
        kind: StepKind,
        assignee: UUID?,
        routeIndex: Int?,
    ): Step =
        Step(
            id = context.newId(),
            ordinal = (round.steps.maxOfOrNull { it.ordinal } ?: 0) + 1,
            assignee = assignee,
            state = StepState.ACTIVE,
            tasks = listOfNotNull(assignee?.let(::newTask)),
            nodeId = node.id,
            kind = kind,
            routeIndex = routeIndex,
            name = node.name,
        )
}
