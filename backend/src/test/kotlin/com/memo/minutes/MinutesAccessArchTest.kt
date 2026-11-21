package com.memo.minutes

import com.memo.minutes.internal.DecisionReads
import com.memo.minutes.internal.DecisionStore
import com.memo.minutes.internal.MinutesAccess
import com.memo.minutes.internal.NoteStore
import com.memo.minutes.internal.ResolutionAccess
import com.memo.minutes.internal.ResolutionCounters
import com.memo.minutes.internal.ResolutionStore
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import org.junit.jupiter.api.Test
import org.springframework.web.bind.annotation.RestController

/**
 * [MinutesAccess] and [ResolutionAccess] are the only readers of the minutes tables for the people decisions and resolutions
 * are shown to (design E06 §1.9): the controllers reach no store, and the reads are used only by them, by the services that
 * record, number and release (they read what they lock) and by the published [Decisions] and [Resolutions] (other modules
 * decide access themselves).
 */
class MinutesAccessArchTest {
    private val classes =
        ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS).importPackages("com.memo.minutes")

    @Test
    fun `the controllers read through MinutesAccess and ResolutionAccess only`() {
        noClasses()
            .that()
            .areAnnotatedWith(RestController::class.java)
            .should()
            .dependOnClassesThat()
            .belongToAnyOf(
                DecisionReads::class.java,
                DecisionStore::class.java,
                NoteStore::class.java,
                ResolutionStore::class.java,
                ResolutionCounters::class.java,
            ).check(classes)
    }

    @Test
    fun `only MinutesAccess, the recording services and the published reads use the decisions' reads`() {
        noClasses()
            .that()
            .haveNameNotMatching(
                named(
                    "MinutesAccess",
                    "ResolutionAccess",
                    "DecisionRecorder",
                    "ConsentDecisions",
                    "DecisionRelease",
                    "MinutesRelease",
                    "Decisions",
                    "DecisionReads",
                ),
            ).should()
            .dependOnClassesThat()
            .belongToAnyOf(DecisionReads::class.java)
            .check(classes)
    }

    @Test
    fun `only the resolution services, the readers and the published reads use the resolutions' stores`() {
        noClasses()
            .that()
            .haveNameNotMatching(
                named(
                    "ResolutionAccess",
                    "MinutesAccess",
                    "ResolutionNumbers",
                    "DecisionRelease",
                    "MinutesRelease",
                    "Decisions",
                    "Resolutions",
                    "ResolutionStore",
                    "ResolutionCounters",
                ),
            ).should()
            .dependOnClassesThat()
            .belongToAnyOf(ResolutionStore::class.java, ResolutionCounters::class.java)
            .check(classes)
    }

    /** A pattern matching the minutes module's [names] (and their nested classes), for `haveNameNotMatching`. */
    private fun named(vararg names: String): String = "com\\.memo\\.minutes(\\.internal)?\\.(${names.joinToString("|")})(\\$.*)?"
}
