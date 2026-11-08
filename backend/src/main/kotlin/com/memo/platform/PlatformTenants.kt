package com.memo.platform

import com.memo.audit.AuditLog
import com.memo.common.tenant.PlatformTransactions
import com.memo.common.tenant.TenantId
import com.memo.identity.operator.OperatorStepUp
import com.memo.tenancy.Tenant
import com.memo.tenancy.TenantLifecycle
import com.memo.tenancy.TenantRegistry
import com.memo.tenancy.TenantSummary
import com.memo.tenancy.UnknownTenantException
import org.springframework.stereotype.Service

/** What an operator asks for when creating a tenant. */
data class NewTenant(
    val slug: String,
    val name: String,
    val locale: String,
    val timeZone: String,
    val adminEmail: String,
    val adminName: String,
)

/**
 * The operator console's tenant actions (ADR-0031). Reads are platform scope (registry only); changes go through the
 * tenancy module's lifecycle and [TenantProvisioning], and every action is recorded in the platform chain.
 */
@Service
class PlatformTenants(
    private val registry: TenantRegistry,
    private val lifecycle: TenantLifecycle,
    private val provisioning: TenantProvisioning,
    private val platform: PlatformTransactions,
    private val audit: AuditLog,
    private val stepUp: OperatorStepUp,
) {
    fun page(
        afterSlug: String?,
        query: String?,
        limit: Int,
    ): List<TenantSummary> = registry.page(afterSlug, query, limit)

    fun get(id: TenantId): TenantSummary? = registry.summary(id)

    fun hasAdmin(id: TenantId): Boolean = provisioning.hasAdmin(id)

    /** Creates the tenant, sets its defaults and invites its first administrator; three steps, each audited. */
    fun create(request: NewTenant): TenantSummary {
        stepUp.require()
        val tenant = lifecycle.create(request.slug, request.name)
        record("tenant.created", tenant.id, mapOf("slug" to tenant.slug, "name" to tenant.name))
        provisioning.defaults(tenant.id, request.locale, request.timeZone)
        provisioning.inviteFirstAdmin(tenant.id, request.adminEmail, request.adminName)
        record("tenant.first_admin.invited", tenant.id, emptyMap())
        return requireNotNull(registry.summary(tenant.id))
    }

    fun rename(
        id: TenantId,
        name: String,
        expectedVersion: Long,
    ): TenantSummary = after(lifecycle.rename(id, name, expectedVersion), "tenant.renamed", mapOf("name" to name))

    fun suspend(
        id: TenantId,
        expectedVersion: Long,
    ): TenantSummary {
        stepUp.require()
        return after(lifecycle.suspend(id, expectedVersion), "tenant.suspended", mapOf("status" to "SUSPENDED"))
    }

    fun resume(
        id: TenantId,
        expectedVersion: Long,
    ): TenantSummary {
        stepUp.require()
        return after(lifecycle.resume(id, expectedVersion), "tenant.resumed", mapOf("status" to "ACTIVE"))
    }

    fun inviteFirstAdmin(
        id: TenantId,
        email: String,
        displayName: String,
    ) {
        stepUp.require()
        if (registry.summary(id) == null) throw UnknownTenantException()
        provisioning.inviteFirstAdmin(id, email, displayName)
        record("tenant.first_admin.invited", id, emptyMap())
    }

    private fun after(
        tenant: Tenant,
        action: String,
        data: Map<String, Any?>,
    ): TenantSummary {
        record(action, tenant.id, data)
        return requireNotNull(registry.summary(tenant.id))
    }

    /** The platform chain names the tenant only; personal data (the admin's address) stays in the tenant's own chain. */
    private fun record(
        action: String,
        tenant: TenantId,
        data: Map<String, Any?>,
    ) = platform.write { audit.record(action, "tenant", tenant.toString(), after = data.ifEmpty { null }) }
}
