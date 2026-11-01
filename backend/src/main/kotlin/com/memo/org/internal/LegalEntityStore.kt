package com.memo.org.internal

import com.memo.common.id.IdGenerator
import com.memo.common.tenant.TenantDsl
import com.memo.jooq.tables.records.OrgLegalEntityRecord
import com.memo.jooq.tables.references.ORG_LEGAL_ENTITY
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import java.time.Clock
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

data class LegalEntity(
    val id: UUID,
    val name: String,
    val shortName: String?,
    val registrationNumber: String?,
    val validFrom: LocalDate,
    val validTo: LocalDate?,
    val version: Long,
)

/** Legal entities (ADR-0026). Every method runs inside a tenant transaction. */
@Repository
class LegalEntityStore(
    private val tenantDsl: TenantDsl,
    private val ids: IdGenerator,
    private val clock: Clock,
) {
    fun all(): List<LegalEntity> =
        tenantDsl
            .current()
            .selectFrom(ORG_LEGAL_ENTITY)
            .orderBy(DSL.lower(ORG_LEGAL_ENTITY.NAME))
            .fetch { it.toLegalEntity() }

    fun find(id: UUID): LegalEntity? =
        tenantDsl
            .current()
            .selectFrom(ORG_LEGAL_ENTITY)
            .where(ORG_LEGAL_ENTITY.ID.eq(id))
            .fetchOne()
            ?.toLegalEntity()

    fun insert(
        name: String,
        shortName: String?,
        registrationNumber: String?,
        validFrom: LocalDate,
    ): LegalEntity {
        val now = now()
        return tenantDsl
            .current()
            .insertInto(ORG_LEGAL_ENTITY)
            .set(ORG_LEGAL_ENTITY.ID, ids.next())
            .set(ORG_LEGAL_ENTITY.TENANT_ID, tenantDsl.tenant().value)
            .set(ORG_LEGAL_ENTITY.NAME, name)
            .set(ORG_LEGAL_ENTITY.SHORT_NAME, shortName)
            .set(ORG_LEGAL_ENTITY.REGISTRATION_NUMBER, registrationNumber)
            .set(ORG_LEGAL_ENTITY.VALID_FROM, validFrom)
            .set(ORG_LEGAL_ENTITY.CREATED_AT, now)
            .set(ORG_LEGAL_ENTITY.UPDATED_AT, now)
            .returning()
            .fetchSingle()
            .toLegalEntity()
    }

    /** Guarded update on [expectedVersion]; false when the row moved on (or does not exist). */
    fun update(
        id: UUID,
        expectedVersion: Long,
        changed: LegalEntity,
    ): Boolean =
        tenantDsl
            .current()
            .update(ORG_LEGAL_ENTITY)
            .set(ORG_LEGAL_ENTITY.NAME, changed.name)
            .set(ORG_LEGAL_ENTITY.SHORT_NAME, changed.shortName)
            .set(ORG_LEGAL_ENTITY.REGISTRATION_NUMBER, changed.registrationNumber)
            .set(ORG_LEGAL_ENTITY.VALID_TO, changed.validTo)
            .set(ORG_LEGAL_ENTITY.VERSION, ORG_LEGAL_ENTITY.VERSION.plus(1))
            .set(ORG_LEGAL_ENTITY.UPDATED_AT, now())
            .where(ORG_LEGAL_ENTITY.ID.eq(id), ORG_LEGAL_ENTITY.VERSION.eq(expectedVersion))
            .execute() == 1

    private fun now() = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC)

    private fun OrgLegalEntityRecord.toLegalEntity() = LegalEntity(id, name, shortName, registrationNumber, validFrom, validTo, version!!)
}
