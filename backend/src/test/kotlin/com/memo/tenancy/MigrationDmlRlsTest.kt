package com.memo.tenancy

import com.memo.testsupport.IntegrationTest
import com.memo.testsupport.TestDatabase
import com.memo.testsupport.repoRoot
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.io.path.extension
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.readText
import kotlin.io.path.relativeTo

/**
 * Data changes in migrations (design E01 §7). Every tenant table has FORCE ROW LEVEL SECURITY, which binds the schema owner
 * too; a migration sets no tenant, so an UPDATE, INSERT or DELETE on such a table silently changes nothing (or fails its
 * WITH CHECK). A migration that changes data in a forced table must lift FORCE for that table in the same file (and put
 * it back), or set `app.tenant_id` itself. Fails the build on a new migration that does neither.
 */
class MigrationDmlRlsTest : IntegrationTest() {
    /**
     * Migrations that already ran in some environment and cannot change. Each entry needs a reason; the data they missed is
     * recorded in the phase log.
     */
    private val known =
        mapOf(
            "workflow/V3__definitions_runtime.sql" to
                "Phase 2 E03: its two backfills ran under FORCE and changed no row; rounds and steps from before E03 keep the " +
                "defaults (Phase 3 E01 log, 2026-11-14)",
        )

    private val dml = Regex("""(?im)^\s*(?:UPDATE|INSERT\s+INTO|DELETE\s+FROM)\s+([a-z_][a-z0-9_]*)""")

    @Test
    fun `data changes in forced tables lift FORCE or set the tenant`() {
        val forced =
            DriverManager.getConnection(TestDatabase.jdbcUrl, "memo_migrator", TestDatabase.migratorPassword).use { c ->
                c
                    .createStatement()
                    .executeQuery(
                        """
                        select c.relname from pg_class c join pg_namespace n on n.oid = c.relnamespace
                        where n.nspname = 'memo' and c.relforcerowsecurity
                        """.trimIndent(),
                    ).use { rs -> buildSet { while (rs.next()) add(rs.getString(1)) } }
            }
        assertThat(forced).describedAs("forced tables found").isNotEmpty()

        val root = repoRoot.resolve("backend/src/main/resources/db/migration")
        val offenders =
            Files
                .walk(root)
                .use { paths -> paths.filter { it.extension == "sql" }.toList() }
                .flatMap { file ->
                    val name = file.relativeTo(root).invariantSeparatorsPathString
                    val sql = file.readText()
                    dml
                        .findAll(sql)
                        .map { it.groupValues[1].lowercase() }
                        .filter { it in forced }
                        .filterNot { table ->
                            Regex("""(?i)ALTER\s+TABLE\s+$table\s+NO\s+FORCE\s+ROW\s+LEVEL\s+SECURITY""").containsMatchIn(sql) ||
                                sql.contains("set_config('app.tenant_id'")
                        }.map { "$name: $it" }
                        .toList()
                }.filterNot { offender -> known.keys.any { offender.startsWith("$it:") } }
        assertThat(offenders)
            .describedAs("migrations changing data in tables with FORCE ROW LEVEL SECURITY without lifting it")
            .isEmpty()
    }
}
