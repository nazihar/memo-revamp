package com.memo.tenancy

import com.memo.audit.AuditLog
import com.memo.common.id.IdGenerator
import com.memo.common.jobs.ActiveTenants
import com.memo.common.jobs.KnownTenants
import com.memo.common.jobs.TenantState
import com.memo.common.tenant.PlatformTransactions
import com.memo.common.tenant.TenantDsl
import com.memo.common.tenant.TenantId
import com.memo.common.tenant.TenantTransactions
import com.memo.jooq.tables.records.TenancyTenantRecord
import com.memo.jooq.tables.references.TENANCY_TENANT
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant
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

/** A registry row as the operator console shows it (ADR-0031): metadata only, never tenant data. */
data class TenantSummary(
    val id: TenantId,
    val slug: String,
    val name: String,
    val status: TenantStatus,
    val version: Long,
    val createdAt: Instant,
    val statusChangedAt: Instant?,
)

/**
 * Tenant registry. A tenant row is only visible inside that tenant's own context (RLS `tenant_self`), and is created inside
 * the new tenant's context, so no code path can create or read rows for a tenant it is not scoped to (ADR-0006).
 * Platform jobs list tenant ids through [listActiveIds] (platform scope, ADR-0017).
 */
@Service
class TenantRegistry(
    private val transactions: TenantTransactions,
    private val platformTransactions: PlatformTransactions,
    private val dsl: DSLContext,
    private val tenantDsl: TenantDsl,
    private val ids: IdGenerator,
    private val clock: Clock,
    private val events: ApplicationEventPublisher,
    private val audit: AuditLog,
) : KnownTenants,
    ActiveTenants {
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
            val inserted =
                tenantDsl
                    .current()
                    .insertInto(TENANCY_TENANT)
                    .set(TENANCY_TENANT.ID, id.value)
                    .set(TENANCY_TENANT.SLUG, slug)
                    .set(TENANCY_TENANT.NAME, name)
                    .set(TENANCY_TENANT.STATUS, TenantStatus.ACTIVE.name)
                    .set(TENANCY_TENANT.CREATED_AT, OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC))
                    .onConflict(TENANCY_TENANT.ID)
                    .doNothing()
                    .execute()
            val tenant = requireNotNull(current()) { "tenant $id not visible after insert" }
            if (inserted == 1) {
                audit.record(
                    "tenant.created",
                    "tenant",
                    id.toString(),
                    after =
                        mapOf(
                            "slug" to slug,
                            "name" to name,
                            "status" to tenant.status.name,
                        ),
                )
                events.publishEvent(TenantCreated(id.value))
            }
            tenant
        }

    /**
     * Ids of all active tenants, for platform jobs that fan out per tenant (ADR-0017). Runs in platform scope, which can
     * read the registry and nothing else.
     */
    fun listActiveIds(): List<TenantId> =
        platformTransactions.read {
            dsl
                .select(TENANCY_TENANT.ID)
                .from(TENANCY_TENANT)
                .where(TENANCY_TENANT.STATUS.eq(TenantStatus.ACTIVE.name))
                .orderBy(TENANCY_TENANT.ID)
                .fetch { TenantId(it.value1()!!) }
        }

    override fun ids(): List<TenantId> = listActiveIds()

    /** The tenant with [slug] and its status, for host-based resolution (ADR-0022). Platform scope. */
    fun findBySlug(slug: String): ResolvedTenant? =
        platformTransactions.read {
            dsl
                .select(TENANCY_TENANT.ID, TENANCY_TENANT.STATUS)
                .from(TENANCY_TENANT)
                .where(TENANCY_TENANT.SLUG.eq(slug))
                .fetchOne { ResolvedTenant(TenantId(it.value1()!!), TenantStatus.valueOf(it.value2()!!)) }
        }

    /** For the job helpers: unknown tenants' tasks are dropped, suspended tenants' tasks wait (ADR-0031). */
    override fun state(tenant: TenantId): TenantState =
        platformTransactions
            .read {
                dsl
                    .select(TENANCY_TENANT.STATUS)
                    .from(TENANCY_TENANT)
                    .where(TENANCY_TENANT.ID.eq(tenant.value))
                    .fetchOne { TenantStatus.valueOf(it.value1()!!) }
            }.let {
                when (it) {
                    null -> TenantState.UNKNOWN
                    TenantStatus.ACTIVE -> TenantState.ACTIVE
                    TenantStatus.SUSPENDED -> TenantState.SUSPENDED
                }
            }

    /** Registry rows by slug after [afterSlug], optionally matching [query] in name or slug (platform scope). */
    fun page(
        afterSlug: String?,
        query: String?,
        limit: Int,
    ): List<TenantSummary> =
        platformTransactions.read {
            val pattern =
                query?.trim()?.takeIf { it.isNotEmpty() }?.let {
                    "%" +
                        it
                            .lowercase()
                            .replace("\\", "\\\\")
                            .replace("%", "\\%")
                            .replace("_", "\\_") + "%"
                }
            dsl
                .selectFrom(TENANCY_TENANT)
                .where(afterSlug?.let { TENANCY_TENANT.SLUG.gt(it) } ?: DSL.noCondition())
                .and(
                    pattern?.let { DSL.lower(TENANCY_TENANT.NAME).like(it, '\\').or(TENANCY_TENANT.SLUG.like(it, '\\')) }
                        ?: DSL.noCondition(),
                ).orderBy(TENANCY_TENANT.SLUG)
                .limit(limit)
                .fetch { it.toSummary() }
        }

    /** One registry row (platform scope). */
    fun summary(id: TenantId): TenantSummary? =
        platformTransactions.read {
            dsl
                .selectFrom(TENANCY_TENANT)
                .where(TENANCY_TENANT.ID.eq(id.value))
                .fetchOne()
                ?.toSummary()
        }

    /** The tenant of the current tenant transaction. */
    fun current(): Tenant? =
        tenantDsl
            .current()
            .selectFrom(TENANCY_TENANT)
            .where(TENANCY_TENANT.ID.eq(tenantDsl.tenant().value))
            .fetchOne()
            ?.toTenant()

    private fun TenancyTenantRecord.toSummary() =
        TenantSummary(
            id = TenantId(id),
            slug = slug,
            name = name,
            status = TenantStatus.valueOf(status!!),
            version = version!!,
            createdAt = createdAt.toInstant(),
            statusChangedAt = statusChangedAt?.toInstant(),
        )

    private fun TenancyTenantRecord.toTenant() =
        Tenant(
            id = TenantId(id),
            slug = slug,
            name = name,
            status = TenantStatus.valueOf(status!!),
            version = version!!,
        )
}
