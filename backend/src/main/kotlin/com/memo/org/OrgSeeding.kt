package com.memo.org

import com.memo.audit.AuditLog
import com.memo.common.tenant.TenantDsl
import com.memo.jooq.tables.references.ORG_ASSIGNMENT
import com.memo.jooq.tables.references.ORG_LEGAL_ENTITY
import com.memo.jooq.tables.references.ORG_POSITION
import com.memo.jooq.tables.references.ORG_REPORTING_LINE
import com.memo.jooq.tables.references.ORG_UNIT
import com.memo.org.internal.OrgTreeCache
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * Idempotent inserts with fixed ids for seeders (the demo tenant, synthetic volumes); never called from requests, so it
 * does not ask the policy. A row that exists is left as it is. New rows are audited like any other change. Inside a
 * tenant transaction.
 */
@Component
class OrgSeeding(
    private val tenantDsl: TenantDsl,
    private val tree: OrgTreeCache,
    private val audit: AuditLog,
    private val clock: Clock,
) {
    fun legalEntity(
        id: UUID,
        name: String,
        validFrom: LocalDate,
    ) {
        val inserted =
            tenantDsl
                .current()
                .insertInto(ORG_LEGAL_ENTITY)
                .set(ORG_LEGAL_ENTITY.ID, id)
                .set(ORG_LEGAL_ENTITY.TENANT_ID, tenantDsl.tenant().value)
                .set(ORG_LEGAL_ENTITY.NAME, name)
                .set(ORG_LEGAL_ENTITY.VALID_FROM, validFrom)
                .set(ORG_LEGAL_ENTITY.CREATED_AT, now())
                .set(ORG_LEGAL_ENTITY.UPDATED_AT, now())
                .onConflictDoNothing()
                .execute()
        if (inserted == 1) audit.record("org.legal_entity.created", "org.legal_entity", id.toString(), after = mapOf("name" to name))
    }

    /** Parents first: the unit's ancestors come from its parent's row. */
    fun unit(
        id: UUID,
        legalEntity: UUID,
        parent: UUID?,
        code: String,
        name: String,
        validFrom: LocalDate,
    ) {
        val ancestors =
            parent?.let { p ->
                val row = requireNotNull(tenantDsl.current().fetchOne(ORG_UNIT, ORG_UNIT.ID.eq(p))) { "parent $p not seeded" }
                row.ancestors?.filterNotNull().orEmpty() + p
            } ?: emptyList()
        val inserted =
            tenantDsl
                .current()
                .insertInto(ORG_UNIT)
                .set(ORG_UNIT.ID, id)
                .set(ORG_UNIT.TENANT_ID, tenantDsl.tenant().value)
                .set(ORG_UNIT.LEGAL_ENTITY_ID, legalEntity)
                .set(ORG_UNIT.PARENT_ID, parent)
                .set(ORG_UNIT.ANCESTORS, ancestors.toTypedArray<UUID?>())
                .set(ORG_UNIT.CODE, code)
                .set(ORG_UNIT.NAME, name)
                .set(ORG_UNIT.VALID_FROM, validFrom)
                .set(ORG_UNIT.CREATED_AT, now())
                .set(ORG_UNIT.UPDATED_AT, now())
                .onConflictDoNothing()
                .execute()
        if (inserted == 1) {
            audit.record("org.unit.created", "org.unit", id.toString(), after = mapOf("code" to code, "name" to name))
            tree.evict()
        }
    }

    fun position(
        id: UUID,
        unit: UUID,
        title: String,
        head: Boolean,
        validFrom: LocalDate,
    ) {
        val inserted =
            tenantDsl
                .current()
                .insertInto(ORG_POSITION)
                .set(ORG_POSITION.ID, id)
                .set(ORG_POSITION.TENANT_ID, tenantDsl.tenant().value)
                .set(ORG_POSITION.UNIT_ID, unit)
                .set(ORG_POSITION.TITLE, title)
                .set(ORG_POSITION.HEAD, head)
                .set(ORG_POSITION.VALID_FROM, validFrom)
                .set(ORG_POSITION.CREATED_AT, now())
                .set(ORG_POSITION.UPDATED_AT, now())
                .onConflictDoNothing()
                .execute()
        if (inserted == 1) audit.record("org.position.created", "org.position", id.toString(), after = mapOf("title" to title))
    }

    /** Assigns [user] to [position] from [validFrom] unless the position already has an assignment. */
    fun assignment(
        position: UUID,
        user: UUID,
        validFrom: LocalDate,
    ) {
        if (tenantDsl.current().fetchExists(ORG_ASSIGNMENT, ORG_ASSIGNMENT.POSITION_ID.eq(position))) return
        val id = UUID.nameUUIDFromBytes("assignment:$position".toByteArray())
        tenantDsl
            .current()
            .insertInto(ORG_ASSIGNMENT)
            .set(ORG_ASSIGNMENT.ID, id)
            .set(ORG_ASSIGNMENT.TENANT_ID, tenantDsl.tenant().value)
            .set(ORG_ASSIGNMENT.USER_ID, user)
            .set(ORG_ASSIGNMENT.POSITION_ID, position)
            .set(ORG_ASSIGNMENT.VALID_FROM, validFrom)
            .set(ORG_ASSIGNMENT.CREATED_AT, now())
            .set(ORG_ASSIGNMENT.UPDATED_AT, now())
            .execute()
        audit.record(
            "org.assignment.created",
            "org.assignment",
            id.toString(),
            after = mapOf("userId" to user.toString(), "positionId" to position.toString()),
        )
    }

    /** Makes [position] report to [reportsTo] from [validFrom] unless it already has a reporting line. */
    fun reportingLine(
        position: UUID,
        reportsTo: UUID,
        validFrom: LocalDate,
    ) {
        if (tenantDsl.current().fetchExists(ORG_REPORTING_LINE, ORG_REPORTING_LINE.POSITION_ID.eq(position))) return
        val id = UUID.nameUUIDFromBytes("reporting-line:$position".toByteArray())
        tenantDsl
            .current()
            .insertInto(ORG_REPORTING_LINE)
            .set(ORG_REPORTING_LINE.ID, id)
            .set(ORG_REPORTING_LINE.TENANT_ID, tenantDsl.tenant().value)
            .set(ORG_REPORTING_LINE.POSITION_ID, position)
            .set(ORG_REPORTING_LINE.REPORTS_TO_POSITION_ID, reportsTo)
            .set(ORG_REPORTING_LINE.VALID_FROM, validFrom)
            .set(ORG_REPORTING_LINE.CREATED_AT, now())
            .set(ORG_REPORTING_LINE.UPDATED_AT, now())
            .execute()
        audit.record(
            "org.reporting_line.created",
            "org.reporting_line",
            id.toString(),
            after = mapOf("positionId" to position.toString(), "reportsToPositionId" to reportsTo.toString()),
        )
    }

    private fun now() = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC)
}
