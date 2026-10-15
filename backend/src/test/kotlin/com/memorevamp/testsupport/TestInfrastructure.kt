package com.memorevamp.testsupport

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
 * One PostgreSQL container per test JVM, initialised with the same role script as Compose and on-prem installs
 * (`deploy/db/init/01-roles.sh`): Flyway runs as `memo_migrator`, the application connects as `memo_app`.
 */
object TestDatabase {
    const val IMAGE = "postgres:18.6"
    val migratorPassword = randomSecret()
    val appPassword = randomSecret()

    val container: PostgreSQLContainer by lazy {
        PostgreSQLContainer(IMAGE)
            .withDatabaseName("memo")
            .withEnv("MEMO_MIGRATOR_PASSWORD", migratorPassword)
            .withEnv("MEMO_APP_PASSWORD", appPassword)
            .withCopyFileToContainer(
                MountableFile.forHostPath(repoRoot.resolve("deploy/db/init/01-roles.sh"), 0b111_101_101),
                "/docker-entrypoint-initdb.d/01-roles.sh",
            ).also { it.start() }
    }

    val jdbcUrl: String get() = container.jdbcUrl

    fun properties(): Map<String, String> =
        mapOf(
            "spring.datasource.url" to jdbcUrl,
            "spring.datasource.username" to "memo_app",
            "spring.datasource.password" to appPassword,
            "spring.flyway.user" to "memo_migrator",
            "spring.flyway.password" to migratorPassword,
        )
}
