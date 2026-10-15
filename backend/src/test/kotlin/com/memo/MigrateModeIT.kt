package com.memo

import com.memo.system.info.SystemInfoRepository
import com.memo.testsupport.TestDatabase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.WebApplicationType
import org.springframework.boot.builder.SpringApplicationBuilder
import java.sql.DriverManager

/**
 * ADR-0016: the migration job migrates an empty database as the migrator and exits 0; app instances then start with
 * Flyway disabled, holding only the application role's credentials.
 */
class MigrateModeIT {
    @Test
    fun `migrate mode is detected from args or environment`() {
        assertThat(isMigrateMode(arrayOf("--memo.mode=migrate"), emptyMap())).isTrue()
        assertThat(isMigrateMode(emptyArray(), mapOf("MEMO_MODE" to "migrate"))).isTrue()
        assertThat(isMigrateMode(emptyArray(), emptyMap())).isFalse()
    }

    @Test
    fun `migration job migrates a fresh database, then the app runs without migrator rights`() {
        TestDatabase.fresh().use { db ->
            val exit =
                runMigrations(
                    arrayOf(
                        "--spring.datasource.url=${db.jdbcUrl}",
                        "--spring.datasource.username=memo_migrator",
                        "--spring.datasource.password=${db.migratorPassword}",
                        "--spring.flyway.user=memo_migrator",
                        "--spring.flyway.password=${db.migratorPassword}",
                    ),
                )
            assertThat(exit).isZero()

            val histories =
                DriverManager.getConnection(db.jdbcUrl, "memo_migrator", db.migratorPassword).use { c ->
                    c
                        .createStatement()
                        .executeQuery(
                            "select tablename from pg_tables where schemaname = 'memo' and tablename like 'flyway_schema_history%'",
                        ).use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
                }
            assertThat(histories).contains("flyway_schema_history_system", "flyway_schema_history_tenancy")

            val app =
                SpringApplicationBuilder(MemoApplication::class.java)
                    .web(WebApplicationType.NONE)
                    .run(
                        "--spring.flyway.enabled=false",
                        "--spring.datasource.url=${db.jdbcUrl}",
                        "--spring.datasource.username=memo_app",
                        "--spring.datasource.password=${db.appPassword}",
                    )
            app.use {
                assertThat(it.getBean(SystemInfoRepository::class.java).schemaMarker()).isEqualTo(SystemInfoRepository.EXPECTED_MARKER)
            }
        }
    }
}
