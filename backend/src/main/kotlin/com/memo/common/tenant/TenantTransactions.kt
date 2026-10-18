package com.memo.common.tenant

import org.jooq.DSLContext
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID

/** Identifier of a tenant (the isolation boundary). */
@JvmInline
value class TenantId(
    val value: UUID,
) {
    override fun toString(): String = value.toString()
}

/** Thrown when tenant data is touched without a tenant-bound transaction (fail closed, AGENTS.md §3.2). */
class MissingTenantContextException(
    message: String = "No tenant context: tenant data may only be accessed inside a transaction started in a tenant scope",
) : IllegalStateException(message)

/**
 * Runs work for one tenant inside a database transaction (ADR-0006, ADR-0017): binds [TenantContext], then opens (or joins)
 * a transaction, which [TenantAwareTransactionManager] binds to that tenant.
 */
@Component
class TenantTransactions(
    transactionManager: PlatformTransactionManager,
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
    ): T =
        TenantContext.call(tenant) {
            val scope = TenantAwareTransactionManager.currentScope()
            val boundTenant: UUID? = scope?.tenant?.value
            when {
                scope == null -> template.inTransaction(block)

                boundTenant == tenant.value -> block()

                boundTenant != null -> throw IllegalStateException(
                    "Nested tenant transaction for a different tenant ($boundTenant → $tenant)",
                )

                else -> throw IllegalStateException("A tenant transaction cannot join a transaction started without a tenant")
            }
        }

    companion object {
        /** The tenant the current transaction is bound to, or null. */
        fun currentTenantOrNull(): TenantId? = TenantAwareTransactionManager.currentScope()?.tenant

        fun currentTenant(): TenantId = currentTenantOrNull() ?: throw MissingTenantContextException()
    }
}

/**
 * Platform-scope transactions (ADR-0017): can list tenants and write the platform audit chain, and nothing else.
 * The only code allowed to enter platform scope (source scan in `ArchitectureRulesTest`).
 */
@Component
class PlatformTransactions(
    transactionManager: PlatformTransactionManager,
) {
    private val readWrite = TransactionTemplate(transactionManager)
    private val readOnly = TransactionTemplate(transactionManager).apply { isReadOnly = true }

    fun <T> read(block: () -> T): T = run(readOnly, block)

    fun <T> write(block: () -> T): T = run(readWrite, block)

    private fun <T> run(
        template: TransactionTemplate,
        block: () -> T,
    ): T =
        PlatformScope.call {
            val scope = TenantAwareTransactionManager.currentScope()
            when {
                scope == null -> template.inTransaction(block)
                scope.platform -> block()
                else -> throw IllegalStateException("A platform transaction cannot join a tenant or tenant-less transaction")
            }
        }

    companion object {
        fun isPlatformTransaction(): Boolean = TenantAwareTransactionManager.currentScope()?.platform == true
    }
}

/** The only way repositories reach tenant-owned tables: fails closed unless the transaction is bound to the scoped tenant. */
@Component
class TenantDsl(
    private val dsl: DSLContext,
) {
    fun current(): DSLContext {
        tenant()
        return dsl
    }

    fun tenant(): TenantId {
        val bound = TenantTransactions.currentTenant()
        val scoped = TenantContext.currentOrNull()
        check(scoped == null || scoped == bound) { "Transaction is bound to $bound but the scope is $scoped" }
        return bound
    }
}

/** The template's result may be null only when [block] returns null; boxing keeps that distinction explicit for callers. */
private fun <T> TransactionTemplate.inTransaction(block: () -> T): T = requireNotNull(execute { Result(block()) }).value

private class Result<T>(
    val value: T,
)
