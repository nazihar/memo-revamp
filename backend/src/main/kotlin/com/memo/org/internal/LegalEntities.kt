package com.memo.org.internal

import com.memo.audit.AuditLog
import com.memo.common.authz.Permission
import com.memo.common.authz.Policy
import com.memo.common.concurrency.ConcurrentModificationException
import com.memo.common.tenant.TenantContext
import com.memo.common.web.refuseIf
import com.memo.org.OrgChanged
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import java.time.LocalDate
import java.util.UUID

/** An object that does not exist in this tenant (404; another tenant's objects are invisible under RLS). */
class OrgNotFoundException(
    what: String,
) : RuntimeException("$what not found")

/** Legal entities (ADR-0026): tenant-wide `org.legal_entities.manage`, audited. Inside a tenant transaction. */
@Service
class LegalEntities(
    private val store: LegalEntityStore,
    private val units: UnitStore,
    private val calendar: OrgCalendar,
    private val policy: Policy,
    private val audit: AuditLog,
    private val events: ApplicationEventPublisher,
) {
    fun get(id: UUID): LegalEntity = store.find(id) ?: throw OrgNotFoundException("legal entity")

    fun create(
        name: String,
        shortName: String?,
        registrationNumber: String?,
        validFrom: LocalDate?,
    ): LegalEntity {
        policy.require(Permission.ORG_LEGAL_ENTITIES_MANAGE)
        val entity = store.insert(name, shortName, registrationNumber, validFrom ?: calendar.today())
        audit.record("org.legal_entity.created", OBJECT_TYPE, entity.id.toString(), after = entity.audited())
        changed(entity.id)
        return entity
    }

    fun update(
        id: UUID,
        expectedVersion: Long,
        name: String,
        shortName: String?,
        registrationNumber: String?,
    ): LegalEntity {
        policy.require(Permission.ORG_LEGAL_ENTITIES_MANAGE)
        val before = get(id)
        return save(
            before,
            expectedVersion,
            before.copy(name = name, shortName = shortName, registrationNumber = registrationNumber),
            "updated",
        )
    }

    /** Closes on [validTo]; the entity's units must be closed by then. */
    fun close(
        id: UUID,
        expectedVersion: Long,
        validTo: LocalDate,
    ): LegalEntity {
        policy.require(Permission.ORG_LEGAL_ENTITIES_MANAGE)
        val before = get(id)
        refuseIf(!validTo.isAfter(before.validFrom), "DATES") { "The closing date must be after the start date." }
        refuseIf(units.units().any { it.legalEntityId == id && it.validTo?.isAfter(validTo) != false }, "NOT_EMPTY") {
            "Close the entity's units first."
        }
        return save(before, expectedVersion, before.copy(validTo = validTo), "closed")
    }

    private fun save(
        before: LegalEntity,
        expectedVersion: Long,
        changed: LegalEntity,
        action: String,
    ): LegalEntity {
        if (!store.update(
                before.id,
                expectedVersion,
                changed,
            )
        ) {
            throw ConcurrentModificationException("legal entity ${before.id}", expectedVersion)
        }
        val after = get(before.id)
        audit.record("org.legal_entity.$action", OBJECT_TYPE, before.id.toString(), before.audited(), after.audited())
        changed(before.id)
        return after
    }

    private fun changed(id: UUID) = events.publishEvent(OrgChanged(TenantContext.current().value, "legal_entity", id))

    private fun LegalEntity.audited() =
        mapOf(
            "name" to name,
            "shortName" to shortName,
            "registrationNumber" to registrationNumber,
            "validFrom" to validFrom.toString(),
            "validTo" to validTo?.toString(),
        )

    companion object {
        const val OBJECT_TYPE = "org.legal_entity"
    }
}
