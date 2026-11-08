package com.memo.tenancy

import com.memo.audit.AuditLog
import com.memo.common.cache.CacheInvalidation
import com.memo.common.concurrency.ConcurrentModificationException
import com.memo.common.tenant.TenantDsl
import com.memo.common.tenant.TenantId
import com.memo.common.tenant.TenantTransactions
import com.memo.jooq.tables.references.TENANCY_TENANT
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset

/** Why a slug cannot be used; the console shows it next to the field. */
enum class SlugProblem { FORMAT, RESERVED, TAKEN }

class SlugRejectedException(
    val problem: SlugProblem,
) : IllegalArgumentException("slug rejected: $problem")

/** The tenant id names no tenant. */
class UnknownTenantException : RuntimeException("unknown tenant")

/**
 * Creating, renaming, suspending and resuming tenants (ADR-0031). Every change is made in the tenant's own scope (the
 * registry's `tenant_self` policy), audited in the tenant's chain in the same transaction, and evicts host resolution on
 * every node. The platform chain is the caller's (the platform module records it in platform scope).
 */
@Service
class TenantLifecycle(
    private val registry: TenantRegistry,
    private val transactions: TenantTransactions,
    private val tenantDsl: TenantDsl,
    private val audit: AuditLog,
    private val invalidation: CacheInvalidation,
    private val events: ApplicationEventPublisher,
    private val clock: Clock,
) {
    /** A new ACTIVE tenant; the slug must fit the host-name pattern, not be reserved and not be taken. */
    fun create(
        slug: String,
        name: String,
    ): Tenant {
        val problem =
            when {
                !SLUG.matches(slug) -> SlugProblem.FORMAT
                slug in RESERVED -> SlugProblem.RESERVED
                registry.findBySlug(slug) != null -> SlugProblem.TAKEN
                else -> null
            }
        if (problem != null) throw SlugRejectedException(problem)
        requireName(name)
        return registry.create(slug, name.trim())
    }

    fun rename(
        id: TenantId,
        name: String,
        expectedVersion: Long,
    ): Tenant {
        requireName(name)
        return change(id, expectedVersion, "tenant.renamed", "name", name.trim()) { it.name }
    }

    /** Blocks the tenant's host and ends its sessions (identity listens to [TenantSuspended]); its jobs wait. */
    fun suspend(
        id: TenantId,
        expectedVersion: Long,
    ): Tenant = change(id, expectedVersion, "tenant.suspended", "status", TenantStatus.SUSPENDED.name) { it.status.name }

    fun resume(
        id: TenantId,
        expectedVersion: Long,
    ): Tenant = change(id, expectedVersion, "tenant.resumed", "status", TenantStatus.ACTIVE.name) { it.status.name }

    private fun change(
        id: TenantId,
        expectedVersion: Long,
        action: String,
        field: String,
        value: String,
        current: (Tenant) -> String,
    ): Tenant =
        transactions.inTenant(id) {
            val before = registry.current() ?: throw UnknownTenantException()
            if (before.version != expectedVersion) throw ConcurrentModificationException("tenant", expectedVersion)
            val now = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC)
            val update =
                tenantDsl
                    .current()
                    .update(TENANCY_TENANT)
                    .set(TENANCY_TENANT.VERSION, TENANCY_TENANT.VERSION.plus(1))
            val set =
                if (field == "status") {
                    update.set(TENANCY_TENANT.STATUS, value).set(TENANCY_TENANT.STATUS_CHANGED_AT, now)
                } else {
                    update.set(TENANCY_TENANT.NAME, value)
                }
            val updated =
                set
                    .where(TENANCY_TENANT.ID.eq(id.value))
                    .and(TENANCY_TENANT.VERSION.eq(expectedVersion))
                    .execute()
            if (updated != 1) throw ConcurrentModificationException("tenant", expectedVersion)
            audit.record(action, "tenant", id.toString(), mapOf(field to current(before)), mapOf(field to value))
            invalidation.invalidate(TenantResolver.CACHE, id)
            if (action == "tenant.suspended") events.publishEvent(TenantSuspended(id.value))
            requireNotNull(registry.current())
        }

    private fun requireName(name: String) = require(name.isNotBlank() && name.trim().length <= MAX_NAME) { "invalid tenant name" }

    companion object {
        private val SLUG = Regex("^[a-z0-9](?:[a-z0-9-]{1,61}[a-z0-9])$")

        /** Host labels the platform keeps (ADR-0031); also a check constraint on the registry. */
        val RESERVED = setOf(PlatformHost.RESERVED_SLUG, "www", "api", "mail", "static")
        private const val MAX_NAME = 200
    }
}
