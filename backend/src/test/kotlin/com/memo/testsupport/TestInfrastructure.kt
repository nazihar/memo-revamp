package com.memo.testsupport

import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.MountableFile
import java.nio.file.Path
import java.security.SecureRandom
import java.util.Base64

/** Repository root (set by the Gradle test task), used to run the real deploy scripts in tests. */
val repoRoot: Path = Path.of(System.getProperty("repo.root") ?: error("system property repo.root is not set"))

/** Random per-run password: tests never contain fixed credentials (AGENTS.md §3.4). */
fun randomSecret(): String {
    val bytes = ByteArray(18).also { SecureRandom().nextBytes(it) }
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}

/**
 * A PostgreSQL container initialised with the same role script as Compose and on-prem installs
 * (`deploy/db/init/01-roles.sh`): Flyway runs as `memo_migrator`, the application connects as `memo_app`.
 * `MEMO_TEST_PG_SETTINGS` (space-separated `name=value`) adds server settings, for volume runs that need production-like
 * memory (E05 T1); unset, the image defaults apply.
 */
class PostgresInstance : AutoCloseable {
    val migratorPassword = randomSecret()
    val appPassword = randomSecret()

    val container: PostgreSQLContainer =
        PostgreSQLContainer(IMAGE)
            .withDatabaseName("memo")
            .withEnv("MEMO_MIGRATOR_PASSWORD", migratorPassword)
            .withEnv("MEMO_APP_PASSWORD", appPassword)
            .withCopyFileToContainer(
                MountableFile.forHostPath(repoRoot.resolve("deploy/db/init/01-roles.sh"), 0b111_101_101),
                "/docker-entrypoint-initdb.d/01-roles.sh",
            ).also { container ->
                val settings =
                    System
                        .getenv("MEMO_TEST_PG_SETTINGS")
                        ?.split(' ')
                        ?.filter { it.contains('=') }
                        .orEmpty()
                // Each cached Spring test context holds its own pool (10), so the image's 100 connections ran out once
                // the suite reached about ten contexts (CI on #223, 2026-11-22). The setting is the test server's only.
                val connections = if (settings.any { it.startsWith("max_connections=") }) emptyList() else listOf(MAX_CONNECTIONS)
                val command = listOf("postgres", "-c", "fsync=off") + (connections + settings).flatMap { listOf("-c", it) }
                if (settings.isNotEmpty()) container.withSharedMemorySize(SHARED_MEMORY)
                container.withCommand(*command.toTypedArray())
                container.start()
            }

    val jdbcUrl: String get() = container.jdbcUrl

    override fun close() = container.stop()

    companion object {
        const val IMAGE = "postgres:18.6"
        private const val SHARED_MEMORY = 1L shl 30
        private const val MAX_CONNECTIONS = "max_connections=300"
    }
}

/** One shared PostgreSQL per test JVM (most tests); `fresh()` gives an isolated, empty one (e.g. migration tests). */
object TestDatabase {
    private val sharedHolder = lazy { PostgresInstance() }
    private val shared: PostgresInstance by sharedHolder

    /**
     * For the stall watchdog: what the shared database is doing (statements running, their wait events, and who blocks
     * whom), read as the container's superuser. Empty when the database was never started.
     */
    fun activity(): String {
        if (!sharedHolder.isInitialized()) return ""
        val container = shared.container
        return java.sql.DriverManager.getConnection(container.jdbcUrl, container.username, container.password).use { connection ->
            connection.createStatement().use { statement ->
                statement.queryTimeout = ACTIVITY_TIMEOUT_SECONDS
                val activity =
                    statement.executeQuery(ACTIVITY_SQL).use { rows ->
                        buildString {
                            while (rows.next()) {
                                appendLine(
                                    "pid ${rows.getInt(1)} ${rows.getString(2)} wait ${rows.getString(3)}/${rows.getString(4)} " +
                                        "for ${rows.getString(5)} blocked by ${rows.getString(6)}: ${rows.getString(7)}",
                                )
                            }
                        }
                    }
                val tables =
                    statement.executeQuery(TABLES_SQL).use { rows ->
                        buildString {
                            while (rows.next()) appendLine("  ${rows.getString(1)}: ${rows.getString(2)}")
                        }
                    }
                "$activity\nLargest tables (live, dead, size, last analysed):\n$tables"
            }
        }
    }

    private const val ACTIVITY_TIMEOUT_SECONDS = 10
    private val ACTIVITY_SQL =
        """
        select pid, state, wait_event_type, wait_event, (now() - query_start)::text, pg_blocking_pids(pid)::text,
               left(regexp_replace(query, '\s+', ' ', 'g'), 400)
        from pg_stat_activity
        where datname = current_database() and pid <> pg_backend_pid() and state <> 'idle'
        order by query_start
        """.trimIndent()
    private val TABLES_SQL =
        """
        select relname, n_live_tup || ' live, ' || n_dead_tup || ' dead, ' || pg_size_pretty(pg_total_relation_size(relid))
               || ', analysed ' || coalesce(greatest(last_analyze, last_autoanalyze)::text, 'never')
        from pg_stat_user_tables
        order by pg_total_relation_size(relid) desc
        limit 12
        """.trimIndent()

    val migratorPassword: String get() = shared.migratorPassword
    val appPassword: String get() = shared.appPassword
    val jdbcUrl: String get() = shared.jdbcUrl

    fun fresh(): PostgresInstance = PostgresInstance()

    fun properties(): Map<String, String> =
        mapOf(
            "spring.datasource.url" to jdbcUrl,
            "spring.datasource.username" to "memo_app",
            "spring.datasource.password" to appPassword,
            "spring.flyway.user" to "memo_migrator",
            "spring.flyway.password" to migratorPassword,
            "memo.security.data-key" to dataKey,
        )

    /** A fresh data key per test JVM (DataKeys): no key material lives in code or fixtures. */
    private val dataKey: String =
        java.util.Base64
            .getUrlEncoder()
            .encodeToString(ByteArray(32).also { java.security.SecureRandom().nextBytes(it) })
}
