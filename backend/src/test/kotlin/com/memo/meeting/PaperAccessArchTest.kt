package com.memo.meeting

import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import org.junit.jupiter.api.Test

/**
 * The guards of the one paper rule (test plan E03, T4; ADR-0061, design E03 §1.9): no class but [PaperAccess] and the
 * revision store reads the revision tables, so no read path can skip the rule; and only the meeting module (papers) and the
 * minutes module's copy endpoint (E06 T6: a reader's copy, chosen by their redaction set) call the files module's
 * `FileServing`, which streams a version without the purpose's `mayRead`.
 */
class PaperAccessArchTest {
    private val classes: JavaClasses =
        ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.memo")

    @Test
    fun `only PaperAccess and the revision store read the revision tables`() {
        val readers = """com\.memo\.meeting\.(PaperAccess|internal\.RevisionStore)(\$.*)?"""
        noClasses()
            .that()
            .resideOutsideOfPackage("com.memo.jooq..")
            .and()
            .haveNameNotMatching(readers)
            .should()
            .dependOnClassesThat(
                JavaClass.Predicates
                    .resideInAPackage("com.memo.jooq..")
                    .and(JavaClass.Predicates.simpleNameStartingWith("MeetingRevision")),
            ).orShould()
            .callMethodWhere(
                com.tngtech.archunit.core.domain.JavaCall.Predicates.target(
                    com.tngtech.archunit.core.domain.properties.HasName.Predicates
                        .nameStartingWith("getMEETING_REVISION"),
                ),
            ).because("every read of a published revision goes through the paper rule (ADR-0061)")
            .check(classes)
    }

    @Test
    fun `only the meeting module and the minutes' file streams (copies and records) stream a file through FileServing`() {
        noClasses()
            .that()
            .resideOutsideOfPackages("com.memo.meeting..", "com.memo.files..")
            .and()
            .haveNameNotMatching("""com\.memo\.minutes\.internal\.MinutesFileStreams(\$.*)?""")
            .should()
            .dependOnClassesThat()
            .haveFullyQualifiedName("com.memo.files.FileServing")
            .because("FileServing skips the purpose's mayRead: only callers behind their own rule may use it (ADR-0061)")
            .check(classes)
    }
}
