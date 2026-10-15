package com.memo

import com.memo.testsupport.repoRoot
import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.web.bind.annotation.RestController
import java.nio.file.Files
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.io.path.walk

/** Starter architecture rules (TESTING_STRATEGY §3.2, E02 design §13). Module boundaries themselves: [ModularityTest]. */
class ArchitectureRulesTest {
    private val classes: JavaClasses =
        ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.memo")

    @Test
    fun `controllers do not use jOOQ directly`() {
        noClasses()
            .that()
            .areAnnotatedWith(RestController::class.java)
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("org.jooq..", "com.memo.jooq..")
            .check(classes)
    }

    @Test
    fun `no wall-clock reads outside the Clock bean`() {
        noClasses()
            .that()
            .resideOutsideOfPackage("com.memo.common.time..")
            .should()
            .callMethod(java.time.Instant::class.java, "now")
            .orShould()
            .callMethod(java.time.OffsetDateTime::class.java, "now")
            .orShould()
            .callMethod(java.time.LocalDateTime::class.java, "now")
            .orShould()
            .callMethod(java.time.ZonedDateTime::class.java, "now")
            .orShould()
            .callMethod(java.time.LocalDate::class.java, "now")
            .orShould()
            .callMethod(System::class.java, "currentTimeMillis")
            .because("inject java.time.Clock so tests control time (TESTING_STRATEGY §3.1)")
            .check(classes)
    }

    @Test
    fun `generated jOOQ tables are only used by their owning module`() {
        // Table classes are named after the table, which is prefixed with the owning module (ADR-0006).
        val tableModules = mapOf("Tenancy" to "tenancy", "System" to "system")
        tableModules.forEach { (prefix, module) ->
            noClasses()
                .that()
                .resideOutsideOfPackage("com.memo.$module..")
                .and()
                .resideOutsideOfPackage("com.memo.jooq..")
                .should()
                .dependOnClassesThat(
                    JavaClass.Predicates
                        .resideInAPackage("com.memo.jooq..")
                        .and(JavaClass.Predicates.simpleNameStartingWith(prefix)),
                ).because("tables of module '$module' are private to it; use its public API")
                .check(classes)
        }
    }

    @Test
    fun `no session-level tenant settings in code or migrations`() {
        val forbidden =
            listOf(
                Regex("""(?i)\bset\s+(session\s+)?app\."""),
                Regex("""(?i)\bset\s+session\b"""),
                Regex("""(?i)set_config\s*\([^)]*,\s*false\s*\)"""),
            )
        val roots = listOf(repoRoot.resolve("backend/src/main"), repoRoot.resolve("deploy"))
        val offenders =
            roots
                .flatMap { root -> root.walk().filter { it.isRegularFile() && it.extension in setOf("kt", "java", "sql", "sh") } }
                .filterNot { it.toString().replace('\\', '/').contains("/src/main/jooq/") }
                .filter { file -> forbidden.any { it.containsMatchIn(file.readText()) } }
                .map { repoRoot.relativize(it).toString() }
        assertThat(offenders).describedAs("session-level SET is forbidden; use set_config(..., true) (ADR-0006)").isEmpty()
        assertThat(Files.exists(roots.first())).isTrue()
    }
}
