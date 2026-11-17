package com.memo.workflow

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
 * The workflow V15 backfill (Phase 3 E01 T8): rounds and steps written before Phase 2 E03 kept the V3 defaults because
 * V3's updates ran under forced RLS and changed nothing, as on dev. This reproduces that on a fresh PostgreSQL (stop at
 * V2, add a pre-E03 round of two steps, migrate to V14 and see the defaults survive), then checks that V15 fills the
 * route and the route indexes, leaves a round started since E03 alone, and puts FORCE back.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RouteBackfillIT {
    private lateinit var db: PostgresInstance
    private val tenant = UUID.randomUUID()
    private val version = UUID.randomUUID()
    private val oldRound = UUID.randomUUID()
    private val newRound = UUID.randomUUID()
    private val first = UUID.randomUUID()
    private val second = UUID.randomUUID()

    @BeforeAll
    fun migrate() {
        db = TestDatabase.fresh()
        flyway("2").migrate()
        connect { c ->
            setTenant(c)
            val definition = UUID.randomUUID()
            c.execute(
                "insert into workflow_definition values ('$definition', '$tenant', 'sequential-approval', 'Sequential', true, now())",
            )
            c.execute(
                """
                insert into workflow_definition_version (id, tenant_id, definition_id, number, body, schema_version, state, created_at)
                values ('$version', '$tenant', '$definition', 1, '{}', 1, 'PUBLISHED', now())
                """,
            )
            val instance = instance(c)
            c.execute(
                """
                insert into workflow_round (id, tenant_id, instance_id, number, outcome, started_by, started_at)
                values ('$oldRound', '$tenant', '$instance', 1, 'OPEN', '$first', now())
                """,
            )
            for ((ordinal, assignee) in listOf(1 to first, 2 to second)) {
                c.execute(
                    """
                    insert into workflow_step (id, tenant_id, instance_id, round_id, ordinal, type, assignee_id, state)
                    values ('${UUID.randomUUID()}', '$tenant', '$instance', '$oldRound', $ordinal, 'APPROVE', '$assignee',
                            '${if (ordinal == 1) "ACTIVE" else "PENDING"}')
                    """,
                )
            }
        }
        flyway("14").migrate()
        // As on dev: V3 changed nothing.
        assertThat(route(oldRound)).isEmpty()
        assertThat(routeIndexes(oldRound)).containsOnlyNulls()
        // A round started since E03, on a definition step of its own with no route index.
        connect { c ->
            setTenant(c)
            val instance = instance(c)
            c.execute(
                """
                insert into workflow_round (id, tenant_id, instance_id, number, outcome, started_by, started_at)
                values ('$newRound', '$tenant', '$instance', 1, 'OPEN', '$first', now())
                """,
            )
            c.execute(
                """
                insert into workflow_step (id, tenant_id, instance_id, round_id, ordinal, type, assignee_id, state, node_id)
                values ('${UUID.randomUUID()}', '$tenant', '$instance', '$newRound', 1, 'REVIEW', '$second', 'ACTIVE', 'review')
                """,
            )
        }
        flyway(null).migrate()
    }

    @AfterAll
    fun stop() = db.close()

    @Test
    fun `a round from before E03 gets its route and its steps their route indexes`() {
        assertThat(route(oldRound)).containsExactly(first, second)
        assertThat(routeIndexes(oldRound)).containsExactly(0, 1)
    }

    @Test
    fun `a round started since E03 is left alone`() {
        assertThat(route(newRound)).isEmpty()
        assertThat(routeIndexes(newRound)).containsOnlyNulls()
    }

    @Test
    fun `forced row-level security is back on`() {
        connect { c ->
            val forced =
                c.createStatement().executeQuery(
                    "select relname from pg_class where relname in ('workflow_round', 'workflow_step') and relforcerowsecurity",
                )
            val names = generateSequence { if (forced.next()) forced.getString(1) else null }.toList()
            assertThat(names).containsExactlyInAnyOrder("workflow_round", "workflow_step")
        }
    }

    private fun instance(c: Connection): UUID {
        val id = UUID.randomUUID()
        c.execute(
            """
            insert into workflow_instance (id, tenant_id, subject_type, subject_id, definition_version_id, initiator_id, status,
                                           current_round, current_step_id, created_at, updated_at)
            values ('$id', '$tenant', 'memo', '${UUID.randomUUID()}', '$version', '$first', 'RUNNING', 1, '${UUID.randomUUID()}', now(), now())
            """,
        )
        return id
    }

    private fun route(round: UUID): List<UUID> =
        connect { c ->
            setTenant(c)
            val rs = c.createStatement().executeQuery("select route from workflow_round where id = '$round'")
            rs.next()
            (rs.getArray(1).array as Array<*>).map { it as UUID }
        }

    private fun routeIndexes(round: UUID): List<Int?> =
        connect { c ->
            setTenant(c)
            val rs = c.createStatement().executeQuery("select route_index from workflow_step where round_id = '$round' order by ordinal")
            buildList { while (rs.next()) add(rs.getObject(1) as Int?) }
        }

    private fun flyway(target: String?): Flyway =
        Flyway
            .configure()
            .dataSource(db.jdbcUrl, "memo_migrator", db.migratorPassword)
            .schemas("memo")
            .defaultSchema("memo")
            .createSchemas(false)
            .locations("classpath:db/migration/workflow")
            .table("flyway_schema_history_workflow")
            .apply { if (target != null) target(target) }
            .load()

    /** One transaction (the tenant is set transaction-locally, never for the session, ADR-0006), committed at the end. */
    private fun <T> connect(block: (Connection) -> T): T =
        DriverManager.getConnection(db.jdbcUrl, "memo_migrator", db.migratorPassword).use { c ->
            c.autoCommit = false
            block(c).also { c.commit() }
        }

    private fun setTenant(c: Connection) =
        c.prepareStatement("select set_config('app.tenant_id', ?, true)").use {
            it.setString(1, tenant.toString())
            it.execute()
        }

    private fun Connection.execute(sql: String) = createStatement().use { it.execute(sql.trimIndent()) }
}
