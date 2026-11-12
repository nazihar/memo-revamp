package com.memo.workflow.engine

import com.memo.workflow.definition.Assignee
import com.memo.workflow.definition.AssigneeKind
import java.util.UUID

/** Who an assignee is now (design E04 §4.1), and what that was decided from (recorded on the step). */
data class Resolution(
    val people: List<UUID>,
    val inputs: Map<String, Any?> = emptyMap(),
)

/**
 * Resolves a definition's assignee for an instance whose initiator is [initiator]. The engine stays pure: the runner
 * answers with the organisation as it is now (ADR-0040).
 */
fun interface Resolver {
    fun resolve(
        assignee: Assignee,
        initiator: UUID,
    ): Resolution

    companion object {
        /** Named people and the initiator only; the organisation's resolvers come from the runner. */
        val NAMED =
            Resolver { assignee, initiator ->
                when (assignee.kind) {
                    AssigneeKind.PERSON -> Resolution(listOfNotNull(assignee.userId), mapOf("kind" to assignee.kind.json))
                    AssigneeKind.INITIATOR -> Resolution(listOf(initiator), mapOf("kind" to assignee.kind.json))
                    else -> Resolution(emptyList(), mapOf("kind" to assignee.kind.json))
                }
            }
    }
}
