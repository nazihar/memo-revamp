package com.memo.actions.internal

import com.memo.minutes.Decisions
import org.springframework.stereotype.Component
import java.util.UUID

/** A decision as an action's source reads it (design E07 §1.2): where it is, its current entry and that entry's conditions. */
data class DecisionSourceRef(
    val decisionId: UUID,
    val meetingId: UUID,
    val itemId: UUID,
    val forumId: UUID,
    /** The current entry, which an action raised now points at. */
    val seq: Int,
    /** The entry's structured conditions (APPROVED_WITH_CONDITIONS), in their order; an action may come from one line. */
    val conditions: List<String>,
    /** The outcome is a resolution (ADR-0056): `action.fromResolution` for the definition's conditions. */
    val resolution: Boolean,
)

/**
 * The actions module's view of E06's decisions (design E07 §11): the published [Decisions] read through one small adapter,
 * so the rest of the module depends on what an action needs of a decision, not on the minutes module's shapes. Inside a
 * tenant transaction; no access decision is made here.
 */
@Component
class DecisionSource(
    private val decisions: Decisions,
) {
    /** The decision [decisionId] with its current entry; null when there is none. */
    fun find(decisionId: UUID): DecisionSourceRef? = decisions.find(decisionId)?.let(::ref)

    /** The current decision on [itemId]; null when the item has none. */
    fun ofItem(itemId: UUID): DecisionSourceRef? = decisions.ofItem(itemId)?.let(::ref)

    /** The conditions of entry [seq] of [decisionId] (an earlier entry stays readable after a correction). */
    fun conditions(
        decisionId: UUID,
        seq: Int,
    ): List<String> = decisions.entry(decisionId, seq)?.conditions.orEmpty()

    private fun ref(d: com.memo.minutes.DecisionRef) =
        DecisionSourceRef(d.id, d.meetingId, d.itemId, d.forumId, d.current.seq, d.current.conditions, d.current.resolution)
}
