package com.memo.workflow.engine

import com.memo.workflow.definition.StepDef
import java.util.UUID

/** The people a step goes to, and the record of how they were found (stored on the step, design E04 §4.1). */
internal data class Found(
    val people: List<UUID>,
    val record: Map<String, Any?>,
)

/**
 * Resolves a definition step's assignees for an instance (design E04 §4.1, ADR-0040): each assignee through the
 * [TransitionContext.resolver], the initiator left out (SOD-1: nobody decides on their own item), and the step's
 * `fallback` when that leaves nobody. Pure, like [Transition].
 */
internal class Candidates(
    private val context: TransitionContext,
) {
    fun of(
        node: StepDef,
        initiator: UUID,
    ): Found {
        val resolutions = (listOfNotNull(node.assignee) + node.assignees).map { context.resolver.resolve(it, initiator) }
        val found = resolutions.flatMap { it.people }.distinct()
        val people = found - initiator
        val record =
            mutableMapOf<String, Any?>(
                "resolved" to resolutions.map { it.inputs + ("people" to it.people.map(UUID::toString)) },
            )
        if (initiator in found) record["excluded"] = listOf(mapOf("person" to initiator.toString(), "rule" to SOD_1))
        val fallback = node.fallback
        val chosen =
            if (people.isEmpty() && fallback != null) {
                (context.resolver.resolve(fallback, initiator).people - initiator).distinct().also { record["fallback"] = true }
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

    private companion object {
        /** The initiator never approves their own item (design E04 §4.4). */
        const val SOD_1 = "SOD-1"
    }
}
