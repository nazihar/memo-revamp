package com.memo.imports

import com.memo.common.tenant.TenantDsl
import com.memo.common.tenant.TenantTransactions
import com.memo.tenancy.TenantRegistry
import com.memo.tenancy.TenantSettings
import com.memo.testsupport.IntegrationTest
import com.memo.testsupport.aTestTenant
import org.assertj.core.api.Assertions.assertThat
import org.jooq.exception.DataAccessException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.beans.factory.annotation.Autowired
import java.sql.SQLException

/**
 * The E06 T1 spike, dry-run half (ADR-0055: the dry run validates in read-only transactions, so a business write fails
 * by construction): does `readInTenant` reach PostgreSQL as a read-only transaction, and does an explicit
 * `SET TRANSACTION READ ONLY` give SQLSTATE 25006? Not in CI (`MEMO_SPIKE_IMPORT`); recorded in the E06 log.
 */
@EnabledIfEnvironmentVariable(named = "MEMO_SPIKE_IMPORT", matches = ".+")
class ReadOnlyTransactionSpikeIT : IntegrationTest() {
    @Autowired lateinit var registry: TenantRegistry

    @Autowired lateinit var settings: TenantSettings

    @Autowired lateinit var transactions: TenantTransactions

    @Autowired lateinit var tenantDsl: TenantDsl

    @Test
    fun `readInTenant is read-only in PostgreSQL, and so is an explicit SET TRANSACTION READ ONLY`() {
        val tenant = registry.aTestTenant(transactions, settings, "readonly-spike").id
        val write = "update org_unit set name = name where tenant_id = current_setting('app.tenant_id')::uuid"

        val flag = transactions.readInTenant(tenant) { tenantDsl.current().fetchValue("show transaction_read_only") }
        val implicit = transactions.readInTenant(tenant) { sqlState { tenantDsl.current().execute(write) } }
        val explicit =
            transactions.inTenant(tenant) {
                tenantDsl.current().execute("set transaction read only")
                sqlState { tenantDsl.current().execute(write) }
            }
        println("ReadOnlyTransactionSpikeIT readInTenant: transaction_read_only=$flag, a write gives SQLSTATE $implicit")
        println("ReadOnlyTransactionSpikeIT SET TRANSACTION READ ONLY in a write transaction: a write gives SQLSTATE $explicit")
        assertThat(explicit).isEqualTo(READ_ONLY_SQL_TRANSACTION)
    }

    private fun sqlState(block: () -> Unit): String? =
        try {
            block()
            null
        } catch (e: DataAccessException) {
            (e.cause as? SQLException)?.sqlState ?: e.sqlState()
        }

    private companion object {
        const val READ_ONLY_SQL_TRANSACTION = "25006"
    }
}
