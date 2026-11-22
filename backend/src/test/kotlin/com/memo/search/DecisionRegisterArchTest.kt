package com.memo.search

import com.memo.jooq.tables.SearchDecisionStats
import com.memo.jooq.tables.SearchFindDecisions
import com.memo.search.internal.decision.DecisionRegisterAccess
import com.memo.testsupport.repoRoot
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.io.path.extension
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.readText
import kotlin.io.path.relativeTo

/**
 * The register's one rule has one caller (ADR-0069, test plan E08 T1 architecture): [DecisionRegisterAccess] is the only
 * code that calls `search_find_decisions` and `search_decision_stats` (it computes the viewer's parameters from live
 * state), and every SECURITY DEFINER function is created in `db/migration/search/` (the "one privileged database path").
 */
class DecisionRegisterArchTest {
    private val classes =
        ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS).importPackages("com.memo")

    @Test
    fun `only DecisionRegisterAccess calls the register's reader functions`() {
        noClasses()
            .that()
            .doNotHaveFullyQualifiedName(DecisionRegisterAccess::class.java.name)
            .and()
            .resideOutsideOfPackage("com.memo.jooq..")
            .should()
            .dependOnClassesThat()
            .belongToAnyOf(SearchFindDecisions::class.java, SearchDecisionStats::class.java)
            .check(classes)
    }

    @Test
    fun `security definer functions are created in the search migrations only`() {
        val root = repoRoot.resolve("backend/src/main/resources/db/migration")
        val elsewhere =
            Files
                .walk(root)
                .use { paths -> paths.filter { it.extension == "sql" }.toList() }
                .filter { file -> file.readText().lines().any { !it.trimStart().startsWith("--") && DEFINER.containsMatchIn(it) } }
                .map { it.relativeTo(root).invariantSeparatorsPathString }
                .filterNot { it.startsWith("search/") }
        assertThat(elsewhere).isEmpty()
    }

    private companion object {
        val DEFINER = Regex("(?i)SECURITY\\s+DEFINER")
    }
}
