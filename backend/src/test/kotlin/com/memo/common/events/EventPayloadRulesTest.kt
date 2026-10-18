package com.memo.common.events

import com.memo.common.jobs.TenantScopedPayload
import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.context.event.EventListener
import org.springframework.transaction.event.TransactionalEventListener
import java.time.Instant
import java.util.UUID

/**
 * Events and task payloads are stored in platform tables the app role can read across tenants, so they carry ids only
 * (ADR-0017, ADR-0018; threat model I4). Listener parameters must say whether an event is tenant-scoped or platform-wide.
 */
class EventPayloadRulesTest {
    private val classes: JavaClasses =
        ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.memo")

    private val idTypes = setOf(UUID::class.java.name, Instant::class.java.name, "long", "int", "boolean")

    /** Strings are allowed only for identifiers, keys and codes, never free text. */
    private val idLikeName = Regex(".*(Id|Key|Code|Type|Name)$")

    @Test
    fun `events and task payloads carry ids only`() {
        val carriers =
            classes
                .filter {
                    it.isAssignableTo(TenantScopedEvent::class.java) ||
                        it.isAssignableTo(PlatformEvent::class.java) ||
                        it.isAssignableTo(TenantScopedPayload::class.java)
                }.filterNot { it.isInterface }
        val offenders =
            carriers.flatMap { c ->
                c.fields
                    .filterNot { it.modifiers.contains(com.tngtech.archunit.core.domain.JavaModifier.STATIC) }
                    .filterNot { f ->
                        val type = f.rawType
                        type.name in idTypes ||
                            type.isEnum ||
                            (type.name == String::class.java.name && idLikeName.matches(f.name))
                    }.map { "${c.simpleName}.${it.name}: ${it.rawType.simpleName}" }
            }
        assertThat(offenders).describedAs("event or payload fields that are not ids").isEmpty()
    }

    @Test
    fun `event listeners take tenant-scoped or platform events`() {
        methods()
            .that()
            .areAnnotatedWith(TransactionalEventListener::class.java)
            .or()
            .areAnnotatedWith(EventListener::class.java)
            .should()
            .haveRawParameterTypes(
                com.tngtech.archunit.base.DescribedPredicate.describe("one TenantScopedEvent or PlatformEvent parameter") { params ->
                    params.size == 1 &&
                        (params[0].isAssignableTo(TenantScopedEvent::class.java) || params[0].isAssignableTo(PlatformEvent::class.java))
                },
            ).allowEmptyShould(true)
            .check(classes)
    }
}
