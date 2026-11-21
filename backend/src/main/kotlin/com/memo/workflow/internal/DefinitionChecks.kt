package com.memo.workflow.internal

import com.memo.common.authz.RoleHolders
import com.memo.org.OrgDirectory
import com.memo.workflow.AttributeType
import com.memo.workflow.EscalationTargets
import com.memo.workflow.WorkflowSubject
import com.memo.workflow.definition.BoundSubject
import com.memo.workflow.definition.Definition
import com.memo.workflow.definition.DefinitionCompiler
import com.memo.workflow.definition.DefinitionJson
import com.memo.workflow.definition.DefinitionReferences
import com.memo.workflow.definition.DefinitionValidator
import com.memo.workflow.definition.Validation
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import java.util.UUID

/**
 * Validation and compilation as the lifecycle runs them (ADR-0038): the attribute catalogue is every subject type's
 * attributes (definitions are not tied to one subject type in E03), and fragments are the tenant's published fragments.
 * Inside a tenant transaction.
 */
@Component
class DefinitionChecks(
    private val versions: DefinitionVersions,
    private val compiled: CompiledDefinitions,
    private val directory: OrgDirectory,
    private val roles: RoleHolders,
    private val queues: Queues,
    private val subjects: List<WorkflowSubject>,
    private val forums: List<EscalationTargets>,
) {
    /**
     * The attributes every subject offers now, in this tenant (a memo type's fields among them, ADR-0043). When two subject
     * types offer one name with different types, the type [body] declares counts if any of them offers it.
     */
    private fun catalogue(body: JsonNode?): Map<String, AttributeType> {
        val declared = body?.get("attributes")?.associate { it.path("name").asString() to it.path("type").asString() }.orEmpty()
        return subjects
            .flatMap { it.attributes.entries }
            .groupBy({ it.key }, { it.value })
            .mapValues { (name, types) -> types.firstOrNull { it.json == declared[name] } ?: types.first() }
    }

    fun validate(body: JsonNode): Validation =
        DefinitionValidator(catalogue(body), References(), subjects = ::bound, fragments = ::fragment).validate(body)

    /** A subject type as a definition bound to it is checked (ADR-0068): its attributes, slots and own rules. */
    private fun bound(type: String): BoundSubject? =
        subjects.singleOrNull { it.type == type }?.let { subject ->
            BoundSubject(subject.attributes, subject.slots, subject::checkDefinition)
        }

    /** What the runtime will read once [definition] is published: its fragments inlined, printed as schema-2 JSON. */
    fun compile(definition: Definition): JsonNode = DefinitionJson.print(DefinitionCompiler.inline(definition, ::fragment))

    private fun fragment(key: String): Definition? =
        versions
            .definition(key)
            ?.takeIf { it.fragment }
            ?.let { definition -> versions.versions(listOf(definition.id)).firstOrNull { it.state == VersionState.PUBLISHED } }
            ?.let { compiled.get(it.id) }

    /** Whether the positions, roles, units and queues something names exist in the tenant. */
    fun references(): DefinitionReferences = References()

    /** Positions, roles and units a definition names must exist in the tenant (design E04 §2). */
    private inner class References : DefinitionReferences {
        override fun positionExists(id: UUID) = directory.positions(listOf(id)).isNotEmpty()

        override fun roleExists(id: UUID) = roles.exists(id)

        override fun unitExists(id: UUID) = directory.unitExists(id)

        override fun queueExists(key: String) = queues.find(key) != null

        override fun forumExists(key: String) = forums.isEmpty() || forums.any { key in it.active(setOf(key)) }
    }
}
