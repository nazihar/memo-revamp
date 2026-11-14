package com.memo.memo

import com.memo.testsupport.PostgresInstance
import com.memo.testsupport.TestDatabase
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID

/**
 * The memo V6 backfill (test plan E01, T1; ADR-0043, design E01 §7) on a database holding Phase 2 data: every type, in
 * every tenant, becomes published version 1 with the same settings and a unique code, and forced RLS is back on
 * afterwards. Runs the memo module's own migrations (its tables refer to no other module) on a fresh PostgreSQL, stopping
 * at V5 to add the data.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MemoTypeBackfillIT {
    private lateinit var db: PostgresInstance
    private val tenantA = UUID.randomUUID()
    private val tenantB = UUID.randomUUID()
    private val level = UUID.randomUUID()

    @BeforeAll
    fun migrate() {
        db = TestDatabase.fresh()
        flyway("5").migrate()
        connect { c ->
            type(c, tenantA, "General memo", key = "general")
            type(c, tenantA, "Credit facility", policy = "FIXED", definition = "credit-facility", doa = true)
            type(c, tenantA, "Cash flow", classification = level, stepUp = false)
            type(c, tenantA, "--", active = false)
            type(c, tenantB, "General memo", key = "general")
        }
        flyway(null).migrate()
    }

    @AfterAll
    fun stop() = db.close()

    @Test
    fun `every type becomes published version 1 with its settings`() {
        connect { c ->
            tenant(c, tenantA)
            val rows =
                c
                    .createStatement()
                    .executeQuery(
                        """
                        select t.name, t.code, t.published_version, v.number, v.state, v.name, v.code, v.definition_key,
                               v.route_policy, v.doa_check, v.step_up_on_approve, v.default_classification_id, v.created_by
                        from memo_type t join memo_type_version v on v.memo_type_id = t.id order by t.name
                        """.trimIndent(),
                    ).use { rs ->
                        buildList {
                            while (rs.next()) {
                                assertThat(rs.getInt(3)).isEqualTo(1)
                                assertThat(rs.getInt(4)).isEqualTo(1)
                                assertThat(rs.getString(5)).isEqualTo("PUBLISHED")
                                assertThat(rs.getString(6)).isEqualTo(rs.getString(1))
                                assertThat(rs.getString(7)).isEqualTo(rs.getString(2))
                                assertThat(rs.getObject(13)).isNull()
                                add(
                                    listOf(
                                        rs.getString(1),
                                        rs.getString(2),
                                        rs.getString(8),
                                        rs.getString(9),
                                        rs.getBoolean(10),
                                        rs.getBoolean(11),
                                        rs.getObject(12),
                                    ),
                                )
                            }
                        }
                    }
            assertThat(rows).containsExactly(
                listOf("--", "T", "sequential-approval", "INITIATOR_DEFINED", false, true, null),
                listOf("Cash flow", "CF2", "sequential-approval", "INITIATOR_DEFINED", false, false, level),
                listOf("Credit facility", "CF", "credit-facility", "FIXED", true, true, null),
                listOf("General memo", "GM", "sequential-approval", "INITIATOR_DEFINED", false, true, null),
            )
        }
    }

    @Test
    fun `each tenant sees only its own versions, and RLS is forced again`() {
        connect { c ->
            tenant(c, tenantB)
            val names =
                c.createStatement().executeQuery("select name, code from memo_type_version").use { rs ->
                    buildList { while (rs.next()) add(rs.getString(1) to rs.getString(2)) }
                }
            assertThat(names).containsExactly("General memo" to "GM")

            val forced =
                c
                    .createStatement()
                    .executeQuery(
                        "select relname, relforcerowsecurity from pg_class where relname in ('memo_type', 'memo_type_version')",
                    ).use { rs -> buildMap { while (rs.next()) put(rs.getString(1), rs.getBoolean(2)) } }
            assertThat(forced).containsEntry("memo_type", true).containsEntry("memo_type_version", true)
        }
    }

    private fun flyway(target: String?): Flyway =
        Flyway
            .configure()
            .dataSource(db.jdbcUrl, "memo_migrator", db.migratorPassword)
            .schemas("memo")
            .defaultSchema("memo")
            .createSchemas(false)
            .locations("classpath:db/migration/memo")
            .table("flyway_schema_history_memo")
            .apply { if (target != null) target(target) }
            .load()

    /** One transaction (the tenant is set transaction-locally, never for the session, ADR-0006), committed at the end. */
    private fun <T> connect(block: (Connection) -> T): T =
        DriverManager.getConnection(db.jdbcUrl, "memo_migrator", db.migratorPassword).use { c ->
            c.autoCommit = false
            block(c).also { c.commit() }
        }

    private fun tenant(
        c: Connection,
        tenant: UUID,
    ) = c.prepareStatement("select set_config('app.tenant_id', ?, true)").use {
        it.setString(1, tenant.toString())
        it.execute()
    }

    @Suppress("LongParameterList")
    private fun type(
        c: Connection,
        tenant: UUID,
        name: String,
        key: String? = null,
        policy: String = "INITIATOR_DEFINED",
        definition: String = "sequential-approval",
        doa: Boolean = false,
        classification: UUID? = null,
        stepUp: Boolean = true,
        active: Boolean = true,
    ) {
        tenant(c, tenant)
        c
            .prepareStatement(
                """
                insert into memo_type (id, tenant_id, key, name, default_classification_id, route_policy, definition_key,
                                       step_up_on_approve, active, doa_check, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, clock_timestamp(), clock_timestamp())
                """.trimIndent(),
            ).use {
                it.setObject(1, UUID.randomUUID())
                it.setObject(2, tenant)
                it.setString(3, key)
                it.setString(4, name)
                it.setObject(5, classification)
                it.setString(6, policy)
                it.setString(7, definition)
                it.setBoolean(8, stepUp)
                it.setBoolean(9, active)
                it.setBoolean(10, doa)
                it.executeUpdate()
            }
    }
}
