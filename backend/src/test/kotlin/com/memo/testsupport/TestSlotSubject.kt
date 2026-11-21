package com.memo.testsupport

import com.memo.workflow.AttributeType
import com.memo.workflow.BlockedReason
import com.memo.workflow.DefinitionProblem
import com.memo.workflow.SlotHolder
import com.memo.workflow.SubjectDefinition
import com.memo.workflow.WorkflowSubject
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * A test-only subject exercising the engine additions of ADR-0068 (Phase 4 E07 T1), as the action item will: it names
 * people per item in slots (`owner`, `verifier`, `chair`), sets due times per item and step, has its own rule for the
 * definitions bound to it (no REJECT, and the start step goes to the owner), and lets its initiator take part unless a
 * test switches that off. Tests set the values per item.
 */
@Component
class TestSlotSubject : WorkflowSubject {
    override val type = TYPE

    override val slots = setOf(OWNER, VERIFIER, CHAIR)

    override val attributes: Map<String, AttributeType> = mapOf("priority" to AttributeType.STRING)

    /** Who each slot of each item names. */
    val holders = ConcurrentHashMap<Pair<UUID, String>, List<SlotHolder>>()

    /** When each step of each item is due; absent: no due time. */
    val dues = ConcurrentHashMap<Pair<UUID, String>, Instant>()

    /** People with a declared interest per item (SOD-4). */
    val interests = ConcurrentHashMap<UUID, Set<UUID>>()

    @Volatile
    override var initiatorTakesPart: Boolean = true

    override fun slot(
        subjectId: UUID,
        slot: String,
    ): List<SlotHolder> = holders[subjectId to slot].orEmpty()

    override fun dueAt(
        subjectId: UUID,
        nodeId: String,
    ): Instant? = dues[subjectId to nodeId]

    override fun interested(subjectId: UUID): Set<UUID> = interests[subjectId].orEmpty()

    override fun checkDefinition(definition: SubjectDefinition): List<DefinitionProblem> =
        listOfNotNull(
            DefinitionProblem(definition.pointer(definition.start), START_NOT_OWNER, "the first step goes to the owner")
                .takeUnless { definition.step(definition.start)?.slot == OWNER },
        ) +
            definition.steps
                .filter { "REJECT" in it.actions }
                .map { DefinitionProblem("${definition.pointer(it.id)}/actions", NO_REJECT, "items of this kind are not rejected") }

    override fun ineligible(
        subjectId: UUID,
        users: Collection<UUID>,
    ): Map<UUID, BlockedReason> = emptyMap()

    override fun snapshot(subjectId: UUID): Map<String, Any?> = emptyMap()

    fun name(
        item: UUID,
        slot: String,
        vararg named: SlotHolder,
    ) {
        holders[item to slot] = named.toList()
    }

    companion object {
        const val TYPE = "test.slots"
        const val OWNER = "owner"
        const val VERIFIER = "verifier"
        const val CHAIR = "chair"
        const val START_NOT_OWNER = "TEST_START_NOT_OWNER"
        const val NO_REJECT = "TEST_NO_REJECT"
    }
}
