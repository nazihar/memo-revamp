package com.memorevamp.common.tenant

import org.jooq.DSLContext
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID

/** Identifier of a tenant (the isolation boundary). */
@JvmInline
value class TenantId(
    val value: UUID,
) {
    override fun toString(): String = value.toString()
}

/** Thrown when tenant data is touched outside a tenant transaction (fail closed, AGENTS.md §3.2). */
class MissingTenantContextException(
    message: String = "No tenant context: tenant data may only be accessed inside TenantTransactions.inTenant { }",
) : IllegalStateException(message)

/**
 * Runs work inside a database transaction bound to one tenant (ADR-0006).
 *
 * The tenant is set with `set_config('app.tenant_id', …, true)`, i.e. transaction-local, so it can never leak to the next
 * user of a pooled connection. This is the seed of Phase 1a's request/job tenant context.
 */
@Component
class TenantTransactions(
    transactionManager: PlatformTransactionManager,
    private val dsl: DSLContext,
) {
    private val readWrite = TransactionTemplate(transactionManager)
    private val readOnly = TransactionTemplate(transactionManager).apply { isReadOnly = true }

    fun <T> inTenant(
        tenant: TenantId,
        block: () -> T,
    ): T = run(tenant, readWrite, block)

    fun <T> readInTenant(
        tenant: TenantId,
        block: () -> T,
    ): T = run(tenant, readOnly, block)

    private fun <T> run(
        tenant: TenantId,
        template: TransactionTemplate,
        block: () -> T,
    ): T {
        val bound = currentTenantOrNull()
        if (bound != null) {
            check(bound == tenant) { "Nested tenant transaction for a different tenant ($bound → $tenant)" }
            return block()
        }
        return template.execute {
            dsl.execute("select set_config('app.tenant_id', ?, true)", tenant.value.toString())
            TransactionSynchronizationManager.bindResource(RESOURCE_KEY, tenant)
            TransactionSynchronizationManager.registerSynchronization(
                object : TransactionSynchronization {
                    override fun afterCompletion(status: Int) {
                        TransactionSynchronizationManager.unbindResourceIfPossible(RESOURCE_KEY)
                    }
                },
            )
            block()
        }
    }

    companion object {
        private val RESOURCE_KEY = Any()

        /** The tenant bound to the current transaction, or null. */
        fun currentTenantOrNull(): TenantId? =
            if (TransactionSynchronizationManager.isActualTransactionActive()) {
                TransactionSynchronizationManager.getResource(RESOURCE_KEY) as TenantId?
            } else {
                null
            }

        fun currentTenant(): TenantId = currentTenantOrNull() ?: throw MissingTenantContextException()
    }
}

/** The only way repositories reach tenant-owned tables: fails closed outside [TenantTransactions.inTenant]. */
@Component
class TenantDsl(
    private val dsl: DSLContext,
) {
    fun current(): DSLContext {
        TenantTransactions.currentTenant()
        return dsl
    }

    fun tenant(): TenantId = TenantTransactions.currentTenant()
}
