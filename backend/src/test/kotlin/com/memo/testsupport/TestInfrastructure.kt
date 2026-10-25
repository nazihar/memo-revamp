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
            ).also { it.start() }

    val jdbcUrl: String get() = container.jdbcUrl

    override fun close() = container.stop()

    companion object {
        const val IMAGE = "postgres:18.6"
    }
}

/** One shared PostgreSQL per test JVM (most tests); `fresh()` gives an isolated, empty one (e.g. migration tests). */
object TestDatabase {
    private val shared: PostgresInstance by lazy { PostgresInstance() }

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
