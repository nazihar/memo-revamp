package com.memo.org

import com.memo.org.internal.AssignmentStore
import com.memo.org.internal.OrgCalendar
import com.memo.org.internal.OrgPosition
import com.memo.org.internal.PositionStore
import com.memo.org.internal.RecordStatus
import com.memo.org.internal.ReportingLineStore
import com.memo.org.internal.UnitStore
import org.springframework.stereotype.Service
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** A position as other modules see it: where it is and whether it heads its unit. */
data class PositionRef(
    val id: UUID,
    val unitId: UUID,
    val title: String,
    val head: Boolean,
)

/** A unit as other modules see it: its short code (used in memo numbers) and its name. */
data class UnitRef(
    val id: UUID,
    val code: String,
    val name: String,
)

/**
 * Read-only questions about the organisation for other modules (design E04 §1), such as the workflow's assignee
 * resolvers: who holds a position, which position a person holds, a unit's head and ancestors, and the reporting line
 * upwards. Dates are calendar dates in the tenant's time zone ([today]); only open records count. Inside a tenant
 * transaction.
 */
@Service
class OrgDirectory(
    private val assignments: AssignmentStore,
    private val positions: PositionStore,
    private val units: UnitStore,
    private val lines: ReportingLineStore,
    private val calendar: OrgCalendar,
) {
    /** Today in the tenant's time zone. */
    fun today(): LocalDate = calendar.today()

    /** The tenant's time zone (the organisation's default; UTC when not set). */
    fun zone(): ZoneId = calendar.zone()

    /** The holder of each of [positionIds] on [date]; vacant positions are absent. */
    fun holders(
        positionIds: Collection<UUID>,
        date: LocalDate = today(),
    ): Map<UUID, UUID> = assignments.holdersOn(positionIds, date).mapValues { it.value.userId }

    /** The position each of [userIds] holds on [date]; people without one are absent. */
    fun positionsOf(
        userIds: Collection<UUID>,
        date: LocalDate = today(),
    ): Map<UUID, PositionRef> {
        val held = assignments.heldBy(userIds, date)
        val refs = positions.byIds(held.values.map { it.positionId }).filterValues { it.openOn(date) }.mapValues { it.value.ref() }
        return held.mapNotNull { (user, assignment) -> refs[assignment.positionId]?.let { user to it } }.toMap()
    }

    /** The positions of [ids] that exist, open or not. */
    fun positions(ids: Collection<UUID>): Map<UUID, PositionRef> = positions.byIds(ids).mapValues { it.value.ref() }

    /** The open head position of [unitId] on [date], if it has one. */
    fun headOf(
        unitId: UUID,
        date: LocalDate = today(),
    ): PositionRef? = positions.ofUnit(unitId).firstOrNull { it.head && it.openOn(date) }?.ref()

    /** The position an assignment is to (for listeners of [OrgChanged] about an assignment). */
    fun positionOfAssignment(assignmentId: UUID): UUID? = assignments.find(assignmentId)?.positionId

    /** Whether the tenant has a unit [unitId] (open or closed). */
    fun unitExists(unitId: UUID): Boolean = units.unit(unitId) != null

    /** The legal entity [unitId] belongs to; null for an unknown unit. */
    fun legalEntityOf(unitId: UUID): UUID? = units.unit(unitId)?.legalEntityId

    /** [unitId]'s ancestors, nearest first (empty for a root unit or an unknown one). */
    fun ancestors(unitId: UUID): List<UUID> =
        units
            .unit(unitId)
            ?.ancestors
            ?.reversed()
            .orEmpty()

    /**
     * The position [positionId] reports to, [levels] lines up, on [date]; null when the chain ends before (the top of
     * the organisation, or a position without a line).
     */
    fun reportsTo(
        positionId: UUID,
        levels: Int,
        date: LocalDate = today(),
    ): PositionRef? {
        require(levels in 1..MAX_LEVELS) { "levels is 1 to $MAX_LEVELS" }
        var current: UUID? = positionId
        repeat(levels) {
            current =
                current?.let { position ->
                    lines
                        .ofPosition(position)
                        .firstOrNull { it.status == RecordStatus.ACTIVE && it.period.contains(date) }
                        ?.reportsToPositionId
                }
        }
        return current?.let { positions.byIds(listOf(it))[it]?.ref() }
    }

    companion object {
        /** How far up a resolver may walk (design E04 §2). */
        const val MAX_LEVELS = 10
    }
}

private fun OrgPosition.ref() = PositionRef(id, unitId, title, head)

/** Units by id for other modules (Phase 3 E01): their codes for memo numbers and their names for display. */
@Service
class OrgUnitDirectory(
    private val units: UnitStore,
) {
    /** The units of [ids] that exist, open or not. */
    fun units(ids: Collection<UUID>): Map<UUID, UnitRef> =
        ids.toSet().mapNotNull { id -> units.unit(id)?.let { id to UnitRef(it.id, it.code, it.name) } }.toMap()
}
