package com.memorevamp.testsupport

import com.memorevamp.common.tenant.TenantId
import com.memorevamp.common.tenant.TenantTransactions
import com.memorevamp.tenancy.TenantRegistry
import com.memorevamp.tenancy.TenantSettings
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID

/** Builders: every test class creates its own fresh tenants, so tests never share mutable data (TESTING_STRATEGY §10). */
fun TenantRegistry.aTenant(label: String = "t"): TenantId {
    val suffix = UUID.randomUUID().toString().take(8)
    return create(slug = "$label-$suffix", name = "Test tenant $label $suffix").id
}

fun TenantTransactions.aSetting(
    settings: TenantSettings,
    tenant: TenantId,
    key: String = "test.key",
    value: String = "value-of-$tenant",
) = inTenant(tenant) { settings.put(key, value) }

/** Raw JDBC as the application role, outside Spring: proves the database enforces isolation on its own. */
object AppRoleJdbc {
    fun <T> connect(block: (Connection) -> T): T =
        DriverManager.getConnection(TestDatabase.jdbcUrl, "memo_app", TestDatabase.appPassword).use(block)

    /** Runs [block] in a transaction scoped to [tenant] (or no tenant when null) and always rolls back. */
    fun <T> inTransaction(
        tenant: TenantId?,
        block: (Connection) -> T,
    ): T =
        connect { c ->
            c.autoCommit = false
            try {
                if (tenant != null) {
                    c.prepareStatement("select set_config('app.tenant_id', ?, true)").use {
                        it.setString(1, tenant.toString())
                        it.execute()
                    }
                }
                block(c)
            } finally {
                c.rollback()
            }
        }

    fun Connection.long(
        sql: String,
        vararg params: Any,
    ): Long =
        prepareStatement(sql).use { st ->
            params.forEachIndexed { i, p -> st.setObject(i + 1, p) }
            st.executeQuery().use { rs ->
                rs.next()
                rs.getLong(1)
            }
        }

    fun Connection.update(
        sql: String,
        vararg params: Any,
    ): Int =
        prepareStatement(sql).use { st ->
            params.forEachIndexed { i, p -> st.setObject(i + 1, p) }
            st.executeUpdate()
        }
}
