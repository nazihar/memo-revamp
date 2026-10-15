package com.memo.contract

import com.memo.testsupport.IntegrationTest
import com.memo.testsupport.TestDatabase
import com.memo.testsupport.repoRoot
import org.assertj.core.api.Assertions.assertThat
import org.jooq.codegen.GenerationTool
import org.jooq.meta.jaxb.Configuration
import org.jooq.meta.jaxb.Database
import org.jooq.meta.jaxb.Generate
import org.jooq.meta.jaxb.Generator
import org.jooq.meta.jaxb.Jdbc
import org.jooq.meta.jaxb.Target
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.io.path.relativeTo
import kotlin.io.path.walk

/**
 * jOOQ code generation (ADR-0007). The application context has already migrated the Testcontainers database with the real
 * Spring Modulith Flyway ordering, so generation sees exactly the runtime schema.
 *  - normal run: fails if `backend/src/main/jooq` differs from freshly generated code;
 *  - `./gradlew :backend:updateJooq`: rewrites `backend/src/main/jooq`.
 */
class JooqCodegenTest : IntegrationTest() {
    private val committedRoot: Path = repoRoot.resolve("backend/src/main/jooq")

    private fun generateInto(dir: Path) {
        GenerationTool.generate(
            Configuration()
                .withJdbc(
                    Jdbc()
                        .withDriver("org.postgresql.Driver")
                        .withUrl(TestDatabase.jdbcUrl)
                        .withUser("memo_migrator")
                        .withPassword(TestDatabase.migratorPassword),
                ).withGenerator(
                    Generator()
                        .withName("org.jooq.codegen.KotlinGenerator")
                        .withDatabase(
                            Database()
                                .withName("org.jooq.meta.postgres.PostgresDatabase")
                                .withInputSchema("memo")
                                .withExcludes("flyway_schema_history.*"),
                        ).withGenerate(
                            Generate()
                                .withGeneratedAnnotation(false)
                                .withPojos(false)
                                .withDaos(false)
                                .withRecords(true)
                                .withJavaTimeTypes(true)
                                .withKotlinNotNullRecordAttributes(true)
                                .withKotlinNotNullPojoAttributes(true),
                        ).withTarget(
                            Target()
                                .withPackageName(PACKAGE)
                                .withDirectory(dir.toString())
                                .withClean(true),
                        ),
                ),
        )
    }

    private fun snapshot(root: Path): Map<String, String> =
        if (!Files.exists(root)) {
            emptyMap()
        } else {
            root
                .walk()
                .filter { it.isRegularFile() }
                .associate { it.relativeTo(root).toString().replace('\\', '/') to it.readText().replace("\r\n", "\n") }
        }

    @OptIn(ExperimentalPathApi::class)
    @Test
    fun `committed jooq sources match the migrated schema`() {
        if (System.getProperty("jooq.update") == "true") {
            committedRoot.deleteRecursively()
            generateInto(committedRoot)
            return
        }
        val fresh = Files.createTempDirectory("jooq-codegen")
        try {
            generateInto(fresh)
            assertThat(snapshot(committedRoot))
                .describedAs("jOOQ sources are stale: run ./gradlew :backend:updateJooq and commit the result")
                .isEqualTo(snapshot(fresh))
        } finally {
            fresh.deleteRecursively()
        }
    }

    private companion object {
        const val PACKAGE = "com.memo.jooq"
    }
}
