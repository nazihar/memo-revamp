package com.memo.tenancy

import com.memo.common.tenant.TenantTransactions
import com.memo.testsupport.CrossTenantProbes
import com.memo.testsupport.IntegrationTest
import com.memo.testsupport.TestDatabase
import com.memo.testsupport.aSetting
import com.memo.testsupport.aTenant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Isolated
import org.springframework.beans.factory.annotation.Autowired
import java.sql.DriverManager

/**
 * Deliberate negative test (TESTING_STRATEGY §12, Phase 0): with RLS switched off on the reference table, the very same
 * probes MUST detect leaks. Proves the cross-tenant suite actually tests RLS and is not passing for another reason.
 */
@Isolated
class RlsNegativeIT : IntegrationTest() {
    @Autowired lateinit var registry: TenantRegistry

    @Autowired lateinit var settings: TenantSettings

    @Autowired lateinit var transactions: TenantTransactions

    private fun asOwner(sql: String) =
        DriverManager.getConnection(TestDatabase.jdbcUrl, "memo_migrator", TestDatabase.migratorPassword).use { c ->
            c.createStatement().use { it.execute(sql) }
        }

    @Test
    fun `cross-tenant probes detect leaks when RLS is disabled`() {
        val a = registry.aTenant("neg-a")
        val b = registry.aTenant("neg-b")
        transactions.aSetting(settings, a, value = "secret-of-a")
        transactions.aSetting(settings, b)

        // Both tables: the tenant registry has its own RLS, which alone already blocks the join and view probes.
        asOwner(TABLES.joinToString("; ") { "ALTER TABLE $it NO FORCE ROW LEVEL SECURITY; ALTER TABLE $it DISABLE ROW LEVEL SECURITY" })
        try {
            val leaks = CrossTenantProbes.run(attacker = b, victim = a).map { it.probe }
            assertThat(leaks).containsExactlyInAnyOrder(
                "read by id",
                "list",
                "tenant registry",
                "join",
                "view",
                "function",
                "update",
                "delete",
                "insert with foreign tenant_id",
                "no tenant context",
            )
        } finally {
            asOwner(TABLES.joinToString("; ") { "ALTER TABLE $it ENABLE ROW LEVEL SECURITY; ALTER TABLE $it FORCE ROW LEVEL SECURITY" })
        }
        // And with RLS restored, the same probes are clean again.
        assertThat(CrossTenantProbes.run(attacker = b, victim = a)).isEmpty()
    }

    private companion object {
        val TABLES = listOf("tenancy_setting", "tenancy_tenant")
    }
}
