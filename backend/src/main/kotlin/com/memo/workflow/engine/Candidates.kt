package com.memo.workflow.engine

import com.memo.workflow.ActionKind
import com.memo.workflow.definition.Assignee
import com.memo.workflow.definition.StepDef
import java.util.UUID

/** The people a step goes to, and the record of how they were found (stored on the step, design E04 §4.1). */
internal data class Found(
    val people: List<UUID>,
    val record: Map<String, Any?>,
)

/**
 * Who to resolve instead of the step's own assignees: a DoA approval group ([group], design E04 §4.3) or a route entry
 * naming positions ([routeIndex], §4.7). Decisions on other groups or entries of the same step count for SOD-2.
 */
internal data class Members(
    val assignees: List<Assignee>,
    val group: Int? = null,
    val routeIndex: Int? = null,
)

/**
 * Resolves a definition step's assignees for an instance (design E04 §4.1, §4.4, ADR-0040): each assignee through the
 * [TransitionContext.resolver], then the separation-of-duties exclusions: the initiator (SOD-1), anyone who already
 * approved or recommended in this round (SOD-2, unless the tenant switched it off), and anyone with a declared interest
 * in the item or who just recused themselves (SOD-4). The step's `fallback` takes over when that leaves nobody. Every
 * exclusion is recorded. Pure, like [Transition].
 */
internal class Candidates(
    private val context: TransitionContext,
) {
    fun of(
        node: StepDef,
        initiator: UUID,
        round: Round,
        recused: Set<UUID> = emptySet(),
        members: Members? = null,
    ): Found {
        val assignees = members?.assignees ?: (listOfNotNull(node.assignee) + node.assignees)
        val resolutions = assignees.map { context.resolver.resolve(it, initiator) }
        val found = resolutions.flatMap { it.people }.distinct()
        val rules = exclusions(node, members, initiator, round, recused)
        val excluded = found.mapNotNull { person -> rules.firstOrNull { person in it.second }?.let { person to it.first } }
        val people = found - excluded.map { it.first }.toSet()
        val record =
            mutableMapOf<String, Any?>(
                "resolved" to resolutions.map { it.inputs + ("people" to it.people.map(UUID::toString)) },
            )
        if (excluded.isNotEmpty()) {
            record["excluded"] =
                excluded.map { (person, rule) -> mapOf("person" to person.toString(), "rule" to rule) }
        }
        // A DoA group has no fallback: who may approve is the DoA's to say.
        val fallback = node.fallback.takeIf { members == null }
        val chosen =
            if (people.isEmpty() && fallback != null) {
                val outside = rules.flatMap { it.second }.toSet()
                context.resolver
                    .resolve(fallback, initiator)
                    .people
                    .filterNot { it in outside }
                    .distinct()
                    .also { record["fallback"] = true }
            } else {
                people
            }
        record["people"] = chosen.map(UUID::toString)
        return Found(chosen, record)
    }

    /** The people a notify step tells; the initiator may be one of them. */
    fun recipients(
        node: StepDef,
        initiator: UUID,
    ): List<UUID> = node.recipients.flatMap { context.resolver.resolve(it, initiator).people }.distinct()

    /**
     * Who may not be a candidate, by rule, in the order the rules are checked (design E04 §4.4). SOD-2 counts decisions on
     * other steps only: a step that runs again after a send-back may go back to the person who decided it before.
     */
    private fun exclusions(
        node: StepDef,
        members: Members?,
        initiator: UUID,
        round: Round,
        recused: Set<UUID>,
    ): List<Pair<String, Set<UUID>>> {
        val decided =
            if (context.oncePerRound) {
                round.steps
                    .filter { it.nodeId != node.id || it.doaGroup != members?.group || it.routeIndex != members?.routeIndex }
                    .flatMap { it.tasks }
                    .filter { it.state == TaskState.COMPLETED && it.outcome in DECISIONS }
                    .mapNotNull { it.onBehalfOf ?: it.assignee }
                    .toSet()
            } else {
                emptySet()
            }
        return listOf(SOD_1 to setOf(initiator), SOD_2 to decided, SOD_4 to (context.interested() + recused))
    }

    private companion object {
        const val SOD_1 = "SOD-1"
        const val SOD_2 = "SOD-2"
        const val SOD_4 = "SOD-4"
        val DECISIONS = setOf(ActionKind.APPROVE, ActionKind.RECOMMEND)
    }
}
