package com.memo.org.internal

import com.memo.audit.AuditTrail
import com.memo.common.authz.Permission
import com.memo.common.authz.Policy
import com.memo.common.authz.Resource
import com.memo.identity.UserSummaries
import com.memo.identity.UserSummary
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.util.UUID

/** Read models of the org screens: each one a handful of set-based queries, no N+1. Inside a tenant transaction. */
@Component
class OrgViews(
    private val units: OrgUnits,
    private val positions: Positions,
    private val positionStore: PositionStore,
    private val assignments: AssignmentStore,
    private val lines: ReportingLineStore,
    private val users: UserSummaries,
    private val trail: AuditTrail,
    private val policy: Policy,
) {
    /** Positions of a unit with their holders on [date]. */
    fun positionsOf(
        unitId: UUID,
        date: LocalDate,
    ): List<PositionWithHolderResponse> {
        units.unit(unitId)
        val list = positionStore.ofUnit(unitId).filter { it.openOn(date) }
        val holders = assignments.holdersOn(list.map { it.id }, date)
        val people = users.of(holders.values.map { it.userId })
        return list.map { position ->
            val holder = holders[position.id]
            PositionWithHolderResponse(position.toResponse(), holder?.let { people[it.userId]?.toPerson() }, holder?.period?.from)
        }
    }

    fun positionDetail(id: UUID): PositionDetailResponse {
        val position = positions.get(id)
        val history = assignments.ofPosition(id)
        val people = users.of(history.map { it.userId })
        val reporting = lines.ofPosition(id)
        val targets = positionStore.byIds(reporting.map { it.reportsToPositionId })
        return PositionDetailResponse(
            position.toResponse(),
            units.unit(position.unitId).toResponse(),
            history.mapNotNull { a -> people[a.userId]?.let { a.toResponse(it) } },
            reporting.mapNotNull { line -> targets[line.reportsToPositionId]?.let { line.toResponse(it) } },
            policy.allows(Permission.ORG_MANAGE, Resource.Unit(position.unitId)),
        )
    }

    /** A person's positions over time with titles and unit names, newest first. */
    fun assignmentsOf(userId: UUID): List<UserPositionResponse> {
        users.of(userId) ?: throw OrgNotFoundException("user")
        val history = assignments.ofUser(userId)
        val held = positionStore.byIds(history.map { it.positionId })
        val unitNames = units.namesOf(held.values.map { it.unitId }.toSet())
        return history.mapNotNull { a ->
            held[a.positionId]?.let { p ->
                UserPositionResponse(a.id, p.toResponse(), unitNames[p.unitId].orEmpty(), a.period.from, a.period.to, a.status)
            }
        }
    }

    fun holderOn(
        positionId: UUID,
        date: LocalDate,
    ): AssignmentResponse? {
        positions.get(positionId)
        val holder = Assignments.holderOn(assignments.ofPosition(positionId), date) ?: return null
        return users.of(holder.userId)?.let { holder.toResponse(it) }
    }

    fun assignment(id: UUID): AssignmentResponse {
        val assignment = assignments.find(id) ?: throw OrgNotFoundException("assignment")
        return assignment.toResponse(users.of(assignment.userId) ?: throw OrgNotFoundException("user"))
    }

    fun reportingLine(id: UUID): ReportingLineResponse {
        val line = lines.find(id) ?: throw OrgNotFoundException("reporting line")
        return line.toResponse(positions.get(line.reportsToPositionId))
    }

    /**
     * The change history of a unit or position from the audit trail (design E04 §6): readable with `audit.read`, or by
     * whoever may manage the object.
     */
    fun history(
        type: String,
        id: UUID,
        limit: Int,
    ): List<HistoryEntryResponse> {
        val unit =
            when (type) {
                "unit" -> units.unit(id).id
                "position" -> positions.get(id).unitId
                else -> throw OrgNotFoundException("history type")
            }
        if (!policy.allows(Permission.AUDIT_READ)) policy.require(Permission.ORG_MANAGE, Resource.Unit(unit))
        val entries = trail.history("org.$type", id.toString(), limit)
        val actors = users.of(entries.mapNotNull { e -> e.actorId?.let { runCatching { UUID.fromString(it) }.getOrNull() } })
        return entries.map { e ->
            val actor = e.actorId?.let { runCatching { UUID.fromString(it) }.getOrNull() }?.let { actors[it]?.displayName }
            HistoryEntryResponse(e.occurredAt, e.action, actor, e.before, e.after)
        }
    }

    private fun UserSummary.toPerson() = PersonResponse(id, displayName, email)

    private fun Assignment.toResponse(person: UserSummary) =
        AssignmentResponse(id, positionId, person.toPerson(), period.from, period.to, status, version)

    private fun ReportingLine.toResponse(target: OrgPosition) =
        ReportingLineResponse(id, positionId, target.toResponse(), period.from, period.to, status, version)
}
