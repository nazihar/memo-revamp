package com.memo.tenancy

import com.memo.MemoApplication
import com.memo.testsupport.IntegrationTest
import com.memo.testsupport.TestDatabase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.modulith.core.ApplicationModules
import java.sql.Connection
import java.sql.DriverManager

/**
 * Catalog meta-test (TESTING_STRATEGY §3.3, ADR-0006). Runs against the schema migrated by the application and fails the
 * build when any table, view, function, index or role breaks the tenancy conventions — including tables added later.
 */
class RlsMetaTest : IntegrationTest() {
    /** Platform tables: no tenant data, therefore no RLS. Every entry needs a reason. */
    private val platformTables =
        mapOf(
            "system_info" to "single platform marker row (E01)",
            "system_scheduled_task" to "db-scheduler executions; task data carries ids only (ADR-0018)",
            "event_publication" to "Spring Modulith outbox; events carry ids only (ADR-0017)",
            "event_publication_archive" to "completed outbox entries; ids only (ADR-0017)",
        )

    /** Tables whose names a framework fixes, exempt from the module-prefix rule. */
    private val frameworkTables = setOf("event_publication", "event_publication_archive")

    /** SECURITY DEFINER functions allowed (none). */
    private val allowedSecurityDefiner = emptySet<String>()

    private fun <T> query(
        sql: String,
        map: (java.sql.ResultSet) -> T,
    ): List<T> =
        DriverManager.getConnection(TestDatabase.jdbcUrl, "memo_migrator", TestDatabase.migratorPassword).use { c: Connection ->
            c.createStatement().use { st ->
                st.executeQuery(sql).use { rs -> buildList { while (rs.next()) add(map(rs)) } }
            }
        }

    private fun tables(): List<String> =
        query(
            """
            select c.relname from pg_class c join pg_namespace n on n.oid = c.relnamespace
            where n.nspname = 'memo' and c.relkind in ('r', 'p') and c.relname not like 'flyway_schema_history%'
            """,
        ) { it.getString(1) }

    private fun tenantTables(): List<String> =
        query(
            """
            select c.relname from pg_class c join pg_namespace n on n.oid = c.relnamespace
            join pg_attribute a on a.attrelid = c.oid and a.attname = 'tenant_id' and not a.attisdropped
            where n.nspname = 'memo' and c.relkind in ('r', 'p')
            """,
        ) { it.getString(1) }

    @Test
    fun `there is at least one tenant table to check`() {
        assertThat(tenantTables()).contains("tenancy_setting")
    }

    @Test
    fun `every non-platform table has RLS enabled and forced`() {
        val violations =
            query(
                """
                select c.relname, c.relrowsecurity, c.relforcerowsecurity from pg_class c
                join pg_namespace n on n.oid = c.relnamespace
                where n.nspname = 'memo' and c.relkind in ('r', 'p') and c.relname not like 'flyway_schema_history%'
                """,
            ) { Triple(it.getString(1), it.getBoolean(2), it.getBoolean(3)) }
                .filter { (name, enabled, forced) -> name !in platformTables && !(enabled && forced) }
                .map { it.first }
        assertThat(violations).describedAs("tables without ENABLE + FORCE ROW LEVEL SECURITY").isEmpty()
    }

    @Test
    fun `every tenant table has a tenant policy with USING and WITH CHECK on app tenant_id`() {
        val policies =
            query(
                """
                select c.relname, p.polcmd, coalesce(pg_get_expr(p.polqual, p.polrelid), ''),
                       coalesce(pg_get_expr(p.polwithcheck, p.polrelid), '')
                from pg_policy p join pg_class c on c.oid = p.polrelid
                join pg_namespace n on n.oid = c.relnamespace where n.nspname = 'memo'
                """,
            ) { rs -> rs.getString(1) to Triple(rs.getString(2), rs.getString(3), rs.getString(4)) }
        val compliant =
            policies
                .filter { (_, p) ->
                    val (cmd, using, check) = p
                    cmd == "*" &&
                        using.contains("tenant_id") && using.contains("app.tenant_id") &&
                        check.contains("tenant_id") && check.contains("app.tenant_id")
                }.map { it.first }
                .toSet()
        assertThat(tenantTables().filterNot { it in compliant })
            .describedAs("tenant tables without a FOR ALL tenant policy (USING + WITH CHECK on app.tenant_id)")
            .isEmpty()
    }

    @Test
    fun `platform tables really are platform tables`() {
        assertThat(platformTables.keys).allSatisfy { assertThat(tables()).contains(it) }
        assertThat(tenantTables()).doesNotContainAnyElementsOf(platformTables.keys)
    }

    @Test
    fun `app role owns nothing and cannot bypass RLS`() {
        val role =
            query("select rolsuper, rolbypassrls from pg_roles where rolname = 'memo_app'") {
                it.getBoolean(1) to it.getBoolean(2)
            }.single()
        assertThat(role).isEqualTo(false to false)
        val owned =
            query(
                """
                select c.relname from pg_class c join pg_namespace n on n.oid = c.relnamespace
                where n.nspname = 'memo' and pg_get_userbyid(c.relowner) = 'memo_app'
                union all
                select p.proname from pg_proc p join pg_namespace n on n.oid = p.pronamespace
                where n.nspname = 'memo' and pg_get_userbyid(p.proowner) = 'memo_app'
                """,
            ) { it.getString(1) }
        assertThat(owned).describedAs("objects owned by the app role").isEmpty()
    }

    @Test
    fun `views run with the caller's rights`() {
        val unsafe =
            query(
                """
                select c.relname from pg_class c join pg_namespace n on n.oid = c.relnamespace
                where n.nspname = 'memo' and c.relkind in ('v', 'm')
                  and not coalesce('security_invoker=true' = any(c.reloptions), false)
                """,
            ) { it.getString(1) }
        assertThat(unsafe).describedAs("views without security_invoker=true").isEmpty()
    }

    @Test
    fun `no SECURITY DEFINER functions`() {
        val definers =
            query(
                """
                select p.proname from pg_proc p join pg_namespace n on n.oid = p.pronamespace
                where n.nspname = 'memo' and p.prosecdef
                """,
            ) { it.getString(1) }
        assertThat(definers.filterNot { it in allowedSecurityDefiner }).isEmpty()
    }

    @Test
    fun `tenant_id leads every multi-column index on tenant tables`() {
        val violations =
            query(
                """
                select c.relname, i.relname, a.attname
                from pg_index x
                join pg_class c on c.oid = x.indrelid
                join pg_class i on i.oid = x.indexrelid
                join pg_namespace n on n.oid = c.relnamespace
                join pg_attribute a on a.attrelid = c.oid and a.attnum = x.indkey[0]
                where n.nspname = 'memo' and x.indnatts > 1
                  and exists (select 1 from pg_attribute t where t.attrelid = c.oid and t.attname = 'tenant_id')
                """,
            ) { Triple(it.getString(1), it.getString(2), it.getString(3)) }
                .filter { it.third != "tenant_id" }
        assertThat(violations).describedAs("multi-column indexes not led by tenant_id").isEmpty()
    }

    @Test
    fun `every foreign key has a supporting index`() {
        val unindexed =
            query(
                """
                select con.conname
                from pg_constraint con
                join pg_namespace n on n.oid = con.connamespace
                where n.nspname = 'memo' and con.contype = 'f'
                  and not exists (
                    select 1 from pg_index x
                    where x.indrelid = con.conrelid
                      and (x.indkey::int2[])[0:cardinality(con.conkey) - 1] = con.conkey
                  )
                """,
            ) { it.getString(1) }
        assertThat(unindexed).describedAs("foreign keys without an index starting with their columns").isEmpty()
    }

    @Test
    fun `every table and view is prefixed with its owning module`() {
        val modules =
            ApplicationModules
                .of(MemoApplication::class.java)
                .map { it.identifier.toString() }
        val objects =
            query(
                """
                select c.relname from pg_class c join pg_namespace n on n.oid = c.relnamespace
                where n.nspname = 'memo' and c.relkind in ('r', 'p', 'v', 'm')
                  and c.relname not like 'flyway_schema_history%'
                """,
            ) { it.getString(1) }
        assertThat(objects.filter { name -> name !in frameworkTables && modules.none { name.startsWith("${it}_") } })
            .describedAs("tables or views not prefixed with a module identifier ($modules)")
            .isEmpty()
    }

    @Test
    fun `platform scope is accepted only by platform policies`() {
        val misuse =
            query(
                """
                select c.relname, p.polname from pg_policy p join pg_class c on c.oid = p.polrelid
                join pg_namespace n on n.oid = c.relnamespace
                where n.nspname = 'memo'
                  and (coalesce(pg_get_expr(p.polqual, p.polrelid), '') like '%app.scope%'
                       or coalesce(pg_get_expr(p.polwithcheck, p.polrelid), '') like '%app.scope%')
                  and p.polname not like 'platform\_%'
                """,
            ) { "${it.getString(1)}.${it.getString(2)}" }
        assertThat(misuse).describedAs("policies accepting app.scope must be named platform_* (ADR-0017)").isEmpty()
    }

    @Test
    fun `the registry accepts platform scope for reading only`() {
        val platformPolicies =
            query(
                """
                select c.relname, p.polcmd from pg_policy p join pg_class c on c.oid = p.polrelid
                join pg_namespace n on n.oid = c.relnamespace
                where n.nspname = 'memo' and p.polname = 'platform_read'
                """,
            ) { it.getString(1) to it.getString(2) }
        assertThat(platformPolicies).containsExactly("tenancy_tenant" to "r")
    }
}
