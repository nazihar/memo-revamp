package com.memo.actions

import com.memo.actions.internal.ActionAccess
import com.memo.actions.internal.ActionLines
import com.memo.actions.internal.ActionStore
import com.memo.actions.internal.ActionTracking
import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import org.junit.jupiter.api.Test
import org.springframework.web.bind.annotation.RestController

/**
 * [ActionAccess] is the only reader of the action tables for the people actions are shown to (design E07 §1.14): the
 * controllers reach no store, the store is used only by it, by the services and listeners that write (they read what
 * they lock) and by the subject the engine asks; the action tables are read nowhere else. The actions module uses the
 * published APIs of meeting and minutes only, and memo, meeting and minutes never depend on actions.
 */
class ActionAccessArchTest {
    private val classes = ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS).importPackages("com.memo")

    @Test
    fun `the controllers read through ActionAccess only`() {
        noClasses()
            .that()
            .areAnnotatedWith(RestController::class.java)
            .should()
            .dependOnClassesThat()
            .belongToAnyOf(ActionStore::class.java, ActionLines::class.java, ActionTracking::class.java)
            .check(classes)
    }

    @Test
    fun `only ActionAccess, the writing services, the listeners and the subject use the store`() {
        noClasses()
            .that()
            .haveNameNotMatching(WRITERS)
            .should()
            .dependOnClassesThat()
            .belongToAnyOf(ActionStore::class.java, ActionLines::class.java, ActionTracking::class.java)
            .check(classes)
    }

    @Test
    fun `the action tables are read by the actions store and the rule only`() {
        noClasses()
            .that()
            .haveNameNotMatching(
                // T4: the date requests' store, and the columns the red-amber-green rule reads in SQL.
                """com\.memo\.actions\.internal\.""" +
                    """(ActionAccess|ActionStore|ActionStoreKt|ActionLines|ActionTracking|ActionDueRequests|RagColumns)(\$.*)?""",
            ).and()
            .resideOutsideOfPackage("com.memo.jooq..")
            .should()
            .dependOnClassesThat(
                JavaClass.Predicates
                    .resideInAPackage("com.memo.jooq..")
                    .and(JavaClass.Predicates.simpleNameStartingWith("Action")),
            ).check(classes)
    }

    private companion object {
        /** The rule, the services and listeners that write (they read what they lock), the minutes' port, the subject. */
        val WRITERS =
            listOf(
                """internal\.(ActionAccess|ActionRaising|ActionRaisingKt|ActionIssuing|ActionScenes|ActionMinutes)""",
                """internal\.(ActionWorkflowListener|ActionSourceListener)""",
                // T4: progress, dates, owners and endings, their effects, and the evidence files' purpose.
                """internal\.(ActionProgress|ActionDates|ActionOwnership|ActionEnding)""",
                """internal\.(ActionProgressEffects|ActionDateEffects|ActionEvidencePurpose)""",
                """internal\.(ActionStore|ActionStoreKt|ActionLines|ActionTracking|ActionDueRequests)""",
                """subject\.(ActionItemSubject|ActionSlots)""",
            ).joinToString("|", prefix = """com\.memo\.actions\.(""", postfix = """)(\$.*)?""")
    }

    @Test
    fun `actions uses the published meeting and minutes APIs, and nothing depends on actions`() {
        noClasses()
            .that()
            .resideInAPackage("com.memo.actions..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("com.memo.meeting.internal..", "com.memo.minutes.internal..", "com.memo.memo.internal..")
            .check(classes)
        noClasses()
            .that()
            .resideInAnyPackage("com.memo.memo..", "com.memo.meeting..", "com.memo.minutes..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("com.memo.actions..")
            .check(classes)
    }
}
