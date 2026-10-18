package com.memo.common.tenant

import org.slf4j.MDC

/**
 * The tenant the current unit of work acts for (ADR-0017). A [ScopedValue], so a binding can never outlive the block that
 * made it: nothing to clear, nothing to leak to the next request on a reused (virtual) thread.
 *
 * Bound by the request filter (E03), the job and event helpers and [TenantTransactions]. Every transaction started while a
 * tenant is bound gets `app.tenant_id` set by [TenantAwareTransactionManager].
 */
object TenantContext {
    private val TENANT: ScopedValue<TenantId> = ScopedValue.newInstance()

    /** Tenant id in structured logs while a tenant is in scope (ADR-0016). */
    const val MDC_KEY = "tenant.id"

    fun currentOrNull(): TenantId? = if (TENANT.isBound) TENANT.get() else null

    fun current(): TenantId = currentOrNull() ?: throw MissingTenantContextException()

    /**
     * Runs [block] with [tenant] in scope. Re-entering the same tenant is a no-op; switching to another tenant, or entering
     * a tenant from platform scope, is a bug and throws.
     */
    fun <T> call(
        tenant: TenantId,
        block: () -> T,
    ): T {
        val bound = currentOrNull()
        if (bound == tenant) return block()
        check(bound == null) { "Tenant scope $bound cannot be switched to $tenant" }
        check(!PlatformScope.isActive()) { "Tenant scope cannot be entered from platform scope" }
        return ScopedValue.where(TENANT, tenant).call(ScopedValue.CallableOp<T, RuntimeException> { withMdc(tenant, block) })
    }

    private fun <T> withMdc(
        tenant: TenantId,
        block: () -> T,
    ): T {
        val previous = MDC.get(MDC_KEY)
        MDC.put(MDC_KEY, tenant.toString())
        try {
            return block()
        } finally {
            if (previous == null) MDC.remove(MDC_KEY) else MDC.put(MDC_KEY, previous)
        }
    }
}

/**
 * Platform scope (ADR-0017): platform jobs and, from E07, operator actions. Transactions started in it set
 * `app.scope = 'platform'`, which only the tenant registry (SELECT) and the platform audit chain accept, so it can list
 * tenants but never read tenant data. It cannot be combined with a tenant scope.
 */
object PlatformScope {
    private val PLATFORM: ScopedValue<Boolean> = ScopedValue.newInstance()

    fun isActive(): Boolean = PLATFORM.isBound && PLATFORM.get()

    fun <T> call(block: () -> T): T {
        if (isActive()) return block()
        check(TenantContext.currentOrNull() == null) { "Platform scope cannot be entered from a tenant scope" }
        return ScopedValue.where(PLATFORM, true).call(ScopedValue.CallableOp<T, RuntimeException> { block() })
    }
}
