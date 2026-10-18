package com.memo.common.tenant

import org.springframework.jdbc.support.JdbcTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.sql.Connection
import javax.sql.DataSource

/** What a transaction was started for: one tenant, platform scope, or neither (tenant-less: RLS shows nothing). */
data class TransactionScope(
    val tenant: TenantId?,
    val platform: Boolean,
)

/**
 * The single place that puts tenant context on the database connection (ADR-0017, ADR-0006). Every transaction, whoever
 * starts it (`@Transactional`, `TransactionTemplate`, jOOQ, Spring Modulith), passes through
 * [prepareTransactionalConnection], which sets `app.tenant_id` (or `app.scope`) transaction-locally from the bound scope and
 * records the scope on the transaction for [TenantDsl] to check.
 */
class TenantAwareTransactionManager(
    dataSource: DataSource,
) : JdbcTransactionManager(dataSource) {
    override fun prepareTransactionalConnection(
        con: Connection,
        definition: TransactionDefinition,
    ) {
        super.prepareTransactionalConnection(con, definition)
        val tenant = TenantContext.currentOrNull()
        val platform = PlatformScope.isActive()
        when {
            tenant != null -> setConfig(con, "app.tenant_id", tenant.toString())
            platform -> setConfig(con, "app.scope", "platform")
        }
        TransactionSynchronizationManager.bindResource(RESOURCE_KEY, TransactionScope(tenant, platform))
    }

    override fun doSuspend(transaction: Any): Any {
        val scope = TransactionSynchronizationManager.unbindResourceIfPossible(RESOURCE_KEY)
        return Suspended(super.doSuspend(transaction), scope as TransactionScope?)
    }

    override fun doResume(
        transaction: Any?,
        suspendedResources: Any,
    ) {
        val suspended = suspendedResources as Suspended
        super.doResume(transaction, suspended.inner)
        suspended.scope?.let { TransactionSynchronizationManager.bindResource(RESOURCE_KEY, it) }
    }

    override fun doCleanupAfterCompletion(transaction: Any) {
        TransactionSynchronizationManager.unbindResourceIfPossible(RESOURCE_KEY)
        super.doCleanupAfterCompletion(transaction)
    }

    private fun setConfig(
        con: Connection,
        name: String,
        value: String,
    ) {
        // Transaction-local (third argument true): reverts at commit or rollback, so it never reaches the next pool user.
        con.prepareStatement("select set_config(?, ?, true)").use {
            it.setString(1, name)
            it.setString(2, value)
            it.execute()
        }
    }

    private class Suspended(
        val inner: Any,
        val scope: TransactionScope?,
    )

    companion object {
        private val RESOURCE_KEY = Any()

        /** The scope the current transaction was started with, or null outside a transaction. */
        fun currentScope(): TransactionScope? =
            if (TransactionSynchronizationManager.isActualTransactionActive()) {
                TransactionSynchronizationManager.getResource(RESOURCE_KEY) as TransactionScope?
            } else {
                null
            }
    }
}
