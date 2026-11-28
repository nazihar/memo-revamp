package com.memo.workflow.internal

import com.memo.common.authz.RoleHolders
import com.memo.org.OrgDirectory
import com.memo.org.PositionRef
import com.memo.workflow.SlotHolder
import com.memo.workflow.definition.Assignee
import com.memo.workflow.definition.AssigneeKind
import com.memo.workflow.engine.Resolution
import com.memo.workflow.engine.Resolver
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.util.UUID

/**
 * The engine's [Resolver] over the organisation as it is today (design E04 §4.1, ADR-0040): positions, the initiator's
 * supervisor and unit heads through [OrgDirectory], roles through [RoleHolders]. Each answer carries what it was decided
 * from, and why nobody was found when that happens. Inside the action's tenant transaction.
 */
@Component
class OrgResolvers(
    private val directory: OrgDirectory,
    private val roles: RoleHolders,
    private val queues: Queues,
) {
    /**
     * A resolver for one action; the initiator's position is looked up once. [slots] answers `subject` assignees for the
     * instance's item (ADR-0068); without it they resolve to nobody. The organisation is read as on [on], today in the
     * tenant's zone by default (the daily holder run passes the day it runs for).
     */
    fun resolver(
        on: LocalDate? = null,
        slots: ((String) -> List<SlotHolder>)? = null,
    ): Resolver {
        val positions = mutableMapOf<UUID, PositionRef?>()
        val day by lazy { on ?: directory.today() }
        return Resolver { assignee, initiator ->
            val own by lazy { positions.getOrPut(initiator) { directory.positionsOf(listOf(initiator), day)[initiator] } }
            val resolution =
                when (assignee.kind) {
                    AssigneeKind.PERSON -> Resolution(listOfNotNull(assignee.userId), inputs(assignee))
                    AssigneeKind.INITIATOR -> Resolution(listOf(initiator), inputs(assignee))
                    AssigneeKind.POSITION -> holderOf(checkNotNull(assignee.positionId), inputs(assignee), day)
                    AssigneeKind.SUPERVISOR -> supervisor(assignee, own, day)
                    AssigneeKind.UNIT_HEAD -> unitHead(assignee, own, day)
                    AssigneeKind.ROLE -> role(assignee, own)
                    AssigneeKind.QUEUE -> queue(checkNotNull(assignee.queue), inputs(assignee))
                    AssigneeKind.SUBJECT -> slot(checkNotNull(assignee.slot), slots, inputs(assignee), day)
                }
            if (resolution.people.size > MAX_CANDIDATES) {
                Resolution(emptyList(), resolution.inputs + ("reason" to TOO_MANY))
            } else {
                resolution
            }
        }
    }

    private fun supervisor(
        assignee: Assignee,
        own: PositionRef?,
        day: LocalDate,
    ): Resolution {
        val inputs = inputs(assignee) + ("initiatorPosition" to own?.id?.toString())
        val above = own?.let { directory.reportsTo(it.id, checkNotNull(assignee.levels), day) }
        return when {
            own == null -> Resolution(emptyList(), inputs + ("reason" to NO_POSITION))
            above == null -> Resolution(emptyList(), inputs + ("reason" to NO_LINE))
            else -> holderOf(above.id, inputs, day)
        }
    }

    private fun unitHead(
        assignee: Assignee,
        own: PositionRef?,
        day: LocalDate,
    ): Resolution {
        val base = assignee.unit ?: own?.unitId
        val up = assignee.up ?: 0
        val unit = base?.let { if (up == 0) it else directory.ancestors(it).getOrNull(up - 1) }
        val inputs = inputs(assignee) + ("unitId" to unit?.toString())
        val head = unit?.let { directory.headOf(it, day) }
        return when {
            base == null -> Resolution(emptyList(), inputs + ("reason" to NO_POSITION))
            unit == null -> Resolution(emptyList(), inputs + ("reason" to NO_UNIT))
            head == null -> Resolution(emptyList(), inputs + ("reason" to NO_HEAD))
            else -> holderOf(head.id, inputs, day)
        }
    }

    private fun role(
        assignee: Assignee,
        own: PositionRef?,
    ): Resolution {
        val unit = assignee.unit ?: own?.unitId
        val people = roles.holders(checkNotNull(assignee.roleId), unit).sorted()
        val inputs = inputs(assignee) + ("unitId" to unit?.toString())
        return Resolution(people, if (people.isEmpty()) inputs + ("reason" to NOBODY) else inputs)
    }

    private fun queue(
        key: String,
        inputs: Map<String, Any?>,
    ): Resolution {
        val people = queues.people(key)
        return Resolution(people, if (people.isEmpty()) inputs + ("reason" to NOBODY) else inputs)
    }

    /**
     * The people the item names in [slot] (ADR-0068), in the item's order: persons as they are, positions as their
     * holders now. The positions are recorded (`positionIds`) so a holder change re-resolves the step, as for a
     * `position` assignee; a slot of vacant positions only is VACANT, an empty one NOBODY.
     */
    private fun slot(
        slot: String,
        slots: ((String) -> List<SlotHolder>)?,
        inputs: Map<String, Any?>,
        day: LocalDate,
    ): Resolution {
        val named = slots?.invoke(slot).orEmpty()
        val positions = named.filterIsInstance<SlotHolder.Position>().map { it.positionId }.distinct()
        val held = if (positions.isEmpty()) emptyMap() else directory.holders(positions, day)
        val people = peopleOf(named, held)
        val recorded =
            inputs +
                listOfNotNull(
                    positions.takeIf { it.isNotEmpty() }?.let { "positionIds" to it.map(UUID::toString) },
                    positions.filter { it !in held }.takeIf { it.isNotEmpty() }?.let { "vacant" to it.map(UUID::toString) },
                    when {
                        people.isNotEmpty() -> null
                        positions.isNotEmpty() && named.all { it is SlotHolder.Position } -> "reason" to VACANT
                        else -> "reason" to NOBODY
                    },
                )
        return Resolution(people, recorded)
    }

    private fun holderOf(
        position: UUID,
        inputs: Map<String, Any?>,
        day: LocalDate,
    ): Resolution {
        val holder = directory.holders(listOf(position), day)[position]
        val withPosition = inputs + ("positionId" to position.toString())
        return if (holder ==
            null
        ) {
            Resolution(emptyList(), withPosition + ("reason" to VACANT))
        } else {
            Resolution(listOf(holder), withPosition)
        }
    }

    private fun inputs(assignee: Assignee): Map<String, Any?> =
        listOfNotNull(
            "kind" to assignee.kind.json,
            assignee.positionId?.let { "positionId" to it.toString() },
            assignee.levels?.let { "levels" to it },
            assignee.unit?.let { "unit" to it.toString() },
            assignee.up?.let { "up" to it },
            assignee.roleId?.let { "roleId" to it.toString() },
            assignee.queue?.let { "queue" to it },
            assignee.slot?.let { "slot" to it },
        ).toMap()

    companion object {
        /** At most this many people share one step (threat model E04 D1). */
        const val MAX_CANDIDATES = 20
        const val VACANT = "VACANT"
        const val NO_POSITION = "NO_POSITION"
        const val NO_LINE = "NO_LINE"
        const val NO_UNIT = "NO_UNIT"
        const val NO_HEAD = "NO_HEAD"
        const val NOBODY = "NOBODY"
        const val TOO_MANY = "TOO_MANY_CANDIDATES"

        /** The people a slot naming [named] resolves to when its positions are held as in [held], in the item's order. */
        fun peopleOf(
            named: List<SlotHolder>,
            held: Map<UUID, UUID>,
        ): List<UUID> =
            named
                .mapNotNull { holder ->
                    (holder as? SlotHolder.Person)?.userId ?: (holder as? SlotHolder.Position)?.let { held[it.positionId] }
                }.distinct()
    }
}
