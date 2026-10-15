package com.memo.tenancy

import com.memo.common.id.IdGenerator
import com.memo.common.tenant.TenantDsl
import com.memo.common.tenant.TenantId
import com.memo.common.tenant.TenantTransactions
import com.memo.jooq.tables.records.TenancyTenantRecord
import com.memo.jooq.tables.references.TENANCY_TENANT
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset

enum class TenantStatus { ACTIVE, SUSPENDED }

data class Tenant(
    val id: TenantId,
    val slug: String,
    val name: String,
    val status: TenantStatus,
    val version: Long,
)

/**
 * Tenant registry. A tenant row is only visible inside that tenant's own context (RLS `tenant_self`), and is created inside
 * the new tenant's context, so no code path can create or read rows for a tenant it is not scoped to (ADR-0006).
 * Cross-tenant listing is a platform operation for Phase 1a (separate audited path).
 */
@Service
class TenantRegistry(
    private val transactions: TenantTransactions,
    private val tenantDsl: TenantDsl,
    private val ids: IdGenerator,
    private val clock: Clock,
) {
    /** Creates a new tenant with a fresh UUIDv7. */
    fun create(
        slug: String,
        name: String,
    ): Tenant = ensure(TenantId(ids.next()), slug, name)

    /** Creates the tenant with a known id if it does not exist yet (idempotent; used by the demo tenant). */
    fun ensure(
        id: TenantId,
        slug: String,
        name: String,
    ): Tenant =
        transactions.inTenant(id) {
            val dsl = tenantDsl.current()
            dsl
                .insertInto(TENANCY_TENANT)
                .set(TENANCY_TENANT.ID, id.value)
                .set(TENANCY_TENANT.SLUG, slug)
                .set(TENANCY_TENANT.NAME, name)
                .set(TENANCY_TENANT.STATUS, TenantStatus.ACTIVE.name)
                .set(TENANCY_TENANT.CREATED_AT, OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC))
                .onConflict(TENANCY_TENANT.ID)
                .doNothing()
                .execute()
            requireNotNull(current()) { "tenant $id not visible after insert" }
        }

    /** The tenant of the current tenant transaction. */
    fun current(): Tenant? =
        tenantDsl
            .current()
            .selectFrom(TENANCY_TENANT)
            .where(TENANCY_TENANT.ID.eq(tenantDsl.tenant().value))
            .fetchOne()
            ?.toTenant()

    private fun TenancyTenantRecord.toTenant() =
        Tenant(
            id = TenantId(id),
            slug = slug,
            name = name,
            status = TenantStatus.valueOf(status!!),
            version = version!!,
        )
}
