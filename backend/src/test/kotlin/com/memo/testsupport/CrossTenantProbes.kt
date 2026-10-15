package com.memo.testsupport

import com.memo.common.tenant.TenantId
import com.memo.testsupport.AppRoleJdbc.long
import com.memo.testsupport.AppRoleJdbc.update
import java.sql.SQLException
import java.time.OffsetDateTime
import java.util.UUID

/**
 * The cross-tenant probe set (TESTING_STRATEGY §3.3), run as the application role over raw JDBC. Each probe acts as the
 * attacker against data owned by the victim and reports a leak if it can see or change it. Every probe runs in its own
 * transaction that is rolled back, so even a successful attack in the negative test leaves no trace.
 */
object CrossTenantProbes {
    data class Leak(
        val probe: String,
        val detail: String,
    )

    fun run(
        attacker: TenantId,
        victim: TenantId,
    ): List<Leak> {
        val v = victim.value
        val leaks = mutableListOf<Leak>()

        fun probe(
            name: String,
            tenant: TenantId? = attacker,
            check: (java.sql.Connection) -> String?,
        ) {
            val result =
                try {
                    AppRoleJdbc.inTransaction(tenant, check)
                } catch (_: SQLException) {
                    null // rejected by the database: not a leak
                }
            if (result != null) leaks += Leak(name, result)
        }

        probe("read by id") { c ->
            c.long("select count(*) from tenancy_setting where tenant_id = ?", v).takeIf { it > 0 }?.let { "$it rows" }
        }
        probe("list") { c ->
            c
                .long("select count(*) from tenancy_setting where tenant_id <> ?", attacker.value)
                .takeIf { it > 0 }
                ?.let { "$it foreign rows" }
        }
        probe("tenant registry") { c ->
            c.long("select count(*) from tenancy_tenant where id = ?", v).takeIf { it > 0 }?.let { "victim tenant row visible" }
        }
        probe("join") { c ->
            c
                .long(
                    "select count(*) from tenancy_setting s join tenancy_tenant t on t.id = s.tenant_id where s.tenant_id = ?",
                    v,
                ).takeIf { it > 0 }
                ?.let { "$it joined rows" }
        }
        probe("view") { c ->
            c.long("select count(*) from tenancy_setting_view where tenant_id = ?", v).takeIf { it > 0 }?.let { "$it rows via view" }
        }
        probe("function") { c ->
            val visible = c.long("select tenancy_setting_count()")
            val own = c.long("select count(*) from tenancy_setting where tenant_id = ?", attacker.value)
            if (visible > own) "function sees $visible rows, attacker owns $own" else null
        }
        probe("update") { c ->
            c.update("update tenancy_setting set value = 'pwned' where tenant_id = ?", v).takeIf { it > 0 }?.let { "$it rows updated" }
        }
        probe("delete") { c ->
            c.update("delete from tenancy_setting where tenant_id = ?", v).takeIf { it > 0 }?.let { "$it rows deleted" }
        }
        probe("insert with foreign tenant_id") { c ->
            val n =
                c.update(
                    "insert into tenancy_setting (id, tenant_id, key, value, created_at, updated_at) values (?, ?, 'pwned.key', 'x', ?, ?)",
                    UUID.randomUUID(),
                    v,
                    OffsetDateTime.now(),
                    OffsetDateTime.now(),
                )
            if (n > 0) "row inserted into victim tenant" else null
        }
        probe("no tenant context", tenant = null) { c ->
            c.long("select count(*) from tenancy_setting").takeIf { it > 0 }?.let { "$it rows without any tenant" }
        }
        return leaks
    }
}
