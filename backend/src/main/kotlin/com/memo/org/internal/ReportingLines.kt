package com.memo.org.internal

import com.memo.audit.AuditLog
import com.memo.common.authz.Permission
import com.memo.common.authz.Policy
import com.memo.common.authz.Resource
import com.memo.common.concurrency.ConcurrentModificationException
import com.memo.common.tenant.TenantContext
import com.memo.common.web.refuseIf
import com.memo.org.OrgChanged
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Solid reporting lines between positions (ADR-0026): one active line per position at any date, never a cycle. The
 * cycle check is conservative: it considers every active line whose period overlaps the new one. Every method runs
 * inside a tenant transaction.
 */
@Service
class ReportingLines(
    private val store: ReportingLineStore,
    private val positions: PositionStore,
    private val policy: Policy,
    private val audit: AuditLog,
    private val events: ApplicationEventPublisher,
) {
    fun get(id: UUID): ReportingLine = store.find(id) ?: throw OrgNotFoundException("reporting line")

    fun create(
        positionId: UUID,
        reportsTo: UUID,
        period: Period,
    ): ReportingLine {
        store.lockAll()
        val position = positions.find(positionId) ?: throw OrgNotFoundException("position")
        val target = positions.find(reportsTo) ?: throw OrgNotFoundException("position")
        policy.require(Permission.ORG_MANAGE, Resource.Unit(position.unitId))
        checkFits(position, target, period, except = null)
        val created = store.insert(positionId, reportsTo, period)
        audit.record("org.reporting_line.created", OBJECT_TYPE, created.id.toString(), after = created.audited())
        changed(created.id)
        return created
    }

    fun changeDates(
        id: UUID,
        expectedVersion: Long,
        period: Period,
    ): ReportingLine {
        store.lockAll()
        val before = get(id)
        refuseIf(before.status != RecordStatus.ACTIVE, "CANCELLED") { "A cancelled reporting line cannot change." }
        val position = positions.find(before.positionId) ?: throw OrgNotFoundException("position")
        val target = positions.find(before.reportsToPositionId) ?: throw OrgNotFoundException("position")
        policy.require(Permission.ORG_MANAGE, Resource.Unit(position.unitId))
        checkFits(position, target, period, except = id)
        return save(before, expectedVersion, period, RecordStatus.ACTIVE, "updated")
    }

    fun cancel(
        id: UUID,
        expectedVersion: Long,
    ): ReportingLine {
        val before = get(id)
        refuseIf(before.status != RecordStatus.ACTIVE, "CANCELLED") { "The reporting line is already cancelled." }
        val position = positions.find(before.positionId) ?: throw OrgNotFoundException("position")
        policy.require(Permission.ORG_MANAGE, Resource.Unit(position.unitId))
        return save(before, expectedVersion, before.period, RecordStatus.CANCELLED, "cancelled")
    }

    private fun checkFits(
        position: OrgPosition,
        target: OrgPosition,
        period: Period,
        except: UUID?,
    ) {
        refuseIf(position.id == target.id, "CYCLE") { "A position cannot report to itself." }
        refuseIf(!period.within(Period(position.validFrom, position.validTo)), "DATES") { "The dates must lie within the position's." }
        val overlapping = store.active().filter { it.id != except && it.period.overlaps(period) }
        refuseIf(overlapping.any { it.positionId == position.id }, "OVERLAP") { "The position already reports to someone in that period." }
        val upward = overlapping.groupBy({ it.positionId }, { it.reportsToPositionId })
        refuseIf(reaches(upward, target.id, position.id), "CYCLE") { "That would make the position report to itself through others." }
    }

    /** Whether following reporting lines up from [from] reaches [goal]. */
    private fun reaches(
        upward: Map<UUID, List<UUID>>,
        from: UUID,
        goal: UUID,
    ): Boolean {
        val seen = mutableSetOf<UUID>()
        val queue = ArrayDeque(listOf(from))
        while (queue.isNotEmpty()) {
            val next = queue.removeFirst()
            if (next == goal) return true
            if (seen.add(next)) queue.addAll(upward[next].orEmpty())
        }
        return false
    }

    private fun save(
        before: ReportingLine,
        expectedVersion: Long,
        period: Period,
        status: RecordStatus,
        action: String,
    ): ReportingLine {
        if (!store.update(before.id, expectedVersion, period, status)) {
            throw ConcurrentModificationException("reporting line ${before.id}", expectedVersion)
        }
        val after = get(before.id)
        audit.record("org.reporting_line.$action", OBJECT_TYPE, before.id.toString(), before.audited(), after.audited())
        changed(before.id)
        return after
    }

    private fun changed(id: UUID) = events.publishEvent(OrgChanged(TenantContext.current().value, "reporting_line", id))

    private fun ReportingLine.audited() =
        mapOf(
            "positionId" to positionId.toString(),
            "reportsToPositionId" to reportsToPositionId.toString(),
            "validFrom" to period.from.toString(),
            "validTo" to period.to?.toString(),
            "status" to status.name,
        )

    companion object {
        const val OBJECT_TYPE = "org.reporting_line"
    }
}
