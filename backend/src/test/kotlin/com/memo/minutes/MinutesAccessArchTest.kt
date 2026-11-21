package com.memo.minutes

import com.memo.minutes.internal.DecisionReads
import com.memo.minutes.internal.DecisionStore
import com.memo.minutes.internal.MinutesAccess
import com.memo.minutes.internal.NoteStore
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import org.junit.jupiter.api.Test
import org.springframework.web.bind.annotation.RestController

/**
 * [MinutesAccess] is the only reader of the minutes tables for the people decisions are shown to (design E06 §1.9): the
 * controllers reach no store, and the reads are used only by it, by the services that record (they read what they lock)
 * and by the published [Decisions] (other modules decide access themselves).
 */
class MinutesAccessArchTest {
    private val classes =
        ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS).importPackages("com.memo.minutes")

    @Test
    fun `the controllers read through MinutesAccess only`() {
        noClasses()
            .that()
            .areAnnotatedWith(RestController::class.java)
            .should()
            .dependOnClassesThat()
            .belongToAnyOf(DecisionReads::class.java, DecisionStore::class.java, NoteStore::class.java)
            .check(classes)
    }

    @Test
    fun `only MinutesAccess, the recording services and the published reads use the decisions' reads`() {
        noClasses()
            .that()
            .haveNameNotMatching(
                """com\.memo\.minutes(\.internal)?\.(MinutesAccess|DecisionRecorder|ConsentDecisions|Decisions|DecisionReads)(\$.*)?""",
            ).should()
            .dependOnClassesThat()
            .belongToAnyOf(DecisionReads::class.java)
            .check(classes)
    }
}
