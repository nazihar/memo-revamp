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
import java.time.LocalDate
import java.util.UUID

/**
 * Positions, the seats of the org model (ADR-0026). Changes need `org.manage` on the position's unit and are audited.
 * Every method runs inside a tenant transaction.
 */
@Service
class Positions(
    private val store: PositionStore,
    private val units: OrgUnits,
    private val assignments: AssignmentStore,
    private val lines: ReportingLineStore,
    private val calendar: OrgCalendar,
    private val policy: Policy,
    private val audit: AuditLog,
    private val events: ApplicationEventPublisher,
) {
    fun get(id: UUID): OrgPosition = store.find(id) ?: throw OrgNotFoundException("position")

    fun create(
        unitId: UUID,
        code: String?,
        title: String,
        head: Boolean,
        validFrom: LocalDate?,
    ): OrgPosition {
        val unit = units.unit(unitId)
        policy.require(Permission.ORG_MANAGE, Resource.Unit(unitId))
        val from = validFrom ?: calendar.today()
        refuseIf(!unit.openOn(from), "CLOSED") { "The unit is not open on that date." }
        refuseIf(head && store.headTaken(unitId, null), "HEAD_TAKEN") { "The unit already has a head position." }
        refuseIf(code != null && store.codeTaken(code, null), "DUPLICATE_CODE") { "Another open position has this code." }
        val created = store.insert(unitId, code, title, head, from)
        audit.record("org.position.created", OBJECT_TYPE, created.id.toString(), after = created.audited())
        changed(created.id)
        return created
    }

    fun update(
        id: UUID,
        expectedVersion: Long,
        code: String?,
        title: String,
        head: Boolean,
    ): OrgPosition {
        val before = get(id)
        policy.require(Permission.ORG_MANAGE, Resource.Unit(before.unitId))
        refuseIf(head && store.headTaken(before.unitId, id), "HEAD_TAKEN") { "The unit already has a head position." }
        refuseIf(code != null && store.codeTaken(code, id), "DUPLICATE_CODE") { "Another open position has this code." }
        return save(before, expectedVersion, before.copy(code = code, title = title, head = head), "updated")
    }

    /** Closes on [validTo]; no assignment or reporting line of the position may run past it. */
    fun close(
        id: UUID,
        expectedVersion: Long,
        validTo: LocalDate,
    ): OrgPosition {
        val before = get(id)
        policy.require(Permission.ORG_MANAGE, Resource.Unit(before.unitId))
        refuseIf(!validTo.isAfter(before.validFrom), "DATES") { "The closing date must be after the start date." }
        refuseIf(assignments.activeAfter(listOf(id), validTo) + lines.activeAfter(id, validTo) > 0, "NOT_EMPTY") {
            "End the position's assignments and reporting lines first."
        }
        return save(before, expectedVersion, before.copy(validTo = validTo), "closed")
    }

    private fun save(
        before: OrgPosition,
        expectedVersion: Long,
        changed: OrgPosition,
        action: String,
    ): OrgPosition {
        if (!store.update(expectedVersion, changed)) throw ConcurrentModificationException("position ${before.id}", expectedVersion)
        val after = get(before.id)
        audit.record("org.position.$action", OBJECT_TYPE, before.id.toString(), before.audited(), after.audited())
        changed(before.id)
        return after
    }

    private fun changed(id: UUID) = events.publishEvent(OrgChanged(TenantContext.current().value, "position", id))

    private fun OrgPosition.audited() =
        mapOf(
            "unitId" to unitId.toString(),
            "code" to code,
            "title" to title,
            "head" to head,
            "validFrom" to validFrom.toString(),
            "validTo" to validTo?.toString(),
        )

    companion object {
        const val OBJECT_TYPE = "org.position"
    }
}
