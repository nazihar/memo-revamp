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
 * The units tree (ADR-0026, design E04 §2). Every change is checked against the policy on the units it touches (a root
 * unit, which has no parent, needs the permission tenant-wide), audited, evicts the tree cache and publishes
 * [OrgChanged]. Tree changes are serialised per tenant. Every method runs inside a tenant transaction.
 */
@Service
class OrgUnits(
    private val store: UnitStore,
    private val legalEntities: LegalEntities,
    private val tree: OrgTreeCache,
    private val calendar: OrgCalendar,
    private val policy: Policy,
    private val audit: AuditLog,
    private val events: ApplicationEventPublisher,
) {
    fun unit(id: UUID): OrgUnit = store.unit(id) ?: throw OrgNotFoundException("unit")

    fun createUnit(
        legalEntityId: UUID,
        parentId: UUID?,
        code: String,
        name: String,
        validFrom: LocalDate?,
    ): OrgUnit {
        store.lockTree()
        val parent = parentId?.let { unit(it) }
        policy.require(Permission.ORG_MANAGE, scopeOf(parent?.id))
        val entity = legalEntities.get(legalEntityId)
        val from = validFrom ?: calendar.today()
        refuseIf(entity.validTo != null && !entity.validTo.isAfter(from), "CLOSED") { "The legal entity is closed." }
        refuseIf(
            parent != null && parent.legalEntityId != legalEntityId,
            "OTHER_ENTITY",
        ) { "The parent unit belongs to another legal entity." }
        refuseIf(parent != null && !parent.openOn(from), "CLOSED") { "The parent unit is not open on that date." }
        refuseIf(store.codeTaken(code), "DUPLICATE_CODE") { "Another open unit has this code." }
        val created = store.insertUnit(legalEntityId, parent, code, name, from)
        audit.record("org.unit.created", OBJECT_TYPE, created.id.toString(), after = created.audited())
        changed(created.id)
        return created
    }

    fun updateUnit(
        id: UUID,
        expectedVersion: Long,
        code: String,
        name: String,
    ): OrgUnit {
        val before = unit(id)
        policy.require(Permission.ORG_MANAGE, Resource.Unit(id))
        refuseIf(store.codeTaken(code, except = id), "DUPLICATE_CODE") { "Another open unit has this code." }
        if (!store.updateUnit(
                id,
                expectedVersion,
                code,
                name,
                before.validTo,
            )
        ) {
            throw ConcurrentModificationException("unit $id", expectedVersion)
        }
        val after = unit(id)
        audit.record("org.unit.updated", OBJECT_TYPE, id.toString(), before.audited(), after.audited())
        changed(id)
        return after
    }

    /**
     * Moves the unit (with its subtree) under [parentId], within its legal entity. Needs the permission on the current
     * parent and on the new one, so a scoped administrator can neither move their own scope root nor move anything out
     * of scope.
     */
    fun moveUnit(
        id: UUID,
        expectedVersion: Long,
        parentId: UUID,
    ): OrgUnit {
        store.lockTree()
        val before = unit(id)
        val parent = unit(parentId)
        policy.require(Permission.ORG_MANAGE, scopeOf(before.parentId))
        policy.require(Permission.ORG_MANAGE, Resource.Unit(parent.id))
        refuseIf(parent.id == id || id in parent.ancestors, "CYCLE") { "A unit cannot move under itself." }
        refuseIf(parent.legalEntityId != before.legalEntityId, "OTHER_ENTITY") { "The new parent belongs to another legal entity." }
        refuseIf(!parent.openOn(calendar.today()), "CLOSED") { "The new parent unit is closed." }
        if (!store.move(before, expectedVersion, parent)) throw ConcurrentModificationException("unit $id", expectedVersion)
        audit.record(
            "org.unit.moved",
            OBJECT_TYPE,
            id.toString(),
            mapOf("parentId" to before.parentId?.toString()),
            mapOf("parentId" to parentId.toString()),
        )
        changed(id)
        return unit(id)
    }

    /** Closes on [validTo] (the last day is the day before); needs no open sub-units or positions by then. */
    fun closeUnit(
        id: UUID,
        expectedVersion: Long,
        validTo: LocalDate,
    ): OrgUnit {
        store.lockTree()
        val before = unit(id)
        policy.require(Permission.ORG_MANAGE, scopeOf(before.parentId))
        refuseIf(!validTo.isAfter(before.validFrom), "DATES") { "The closing date must be after the start date." }
        refuseIf(store.openContentsAfter(id, validTo) > 0, "NOT_EMPTY") { "Close or move the unit's sub-units and positions first." }
        if (!store.updateUnit(id, expectedVersion, before.code, before.name, validTo)) {
            throw ConcurrentModificationException("unit $id", expectedVersion)
        }
        audit.record("org.unit.closed", OBJECT_TYPE, id.toString(), mapOf("validTo" to null), mapOf("validTo" to validTo.toString()))
        changed(id)
        return unit(id)
    }

    private fun scopeOf(parent: UUID?): Resource = parent?.let { Resource.Unit(it) } ?: Resource.Tenant

    private fun changed(unit: UUID) {
        tree.evict()
        events.publishEvent(OrgChanged(TenantContext.current().value, "unit", unit))
    }

    private fun OrgUnit.audited() =
        mapOf(
            "legalEntityId" to legalEntityId.toString(),
            "parentId" to parentId?.toString(),
            "code" to code,
            "name" to name,
            "validFrom" to validFrom.toString(),
            "validTo" to validTo?.toString(),
        )

    companion object {
        const val OBJECT_TYPE = "org.unit"
    }
}
