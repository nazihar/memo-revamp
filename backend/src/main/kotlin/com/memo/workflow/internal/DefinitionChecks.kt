package com.memo.workflow.internal

import com.memo.workflow.WorkflowSubject
import com.memo.workflow.definition.Definition
import com.memo.workflow.definition.DefinitionCompiler
import com.memo.workflow.definition.DefinitionJson
import com.memo.workflow.definition.DefinitionValidator
import com.memo.workflow.definition.Validation
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode

/**
 * Validation and compilation as the lifecycle runs them (ADR-0038): the attribute catalogue is every subject type's
 * attributes (definitions are not tied to one subject type in E03), and fragments are the tenant's published fragments.
 * Inside a tenant transaction.
 */
@Component
class DefinitionChecks(
    private val versions: DefinitionVersions,
    private val compiled: CompiledDefinitions,
    subjects: List<WorkflowSubject>,
) {
    private val catalogue = subjects.flatMap { it.attributes.entries }.associate { it.key to it.value }

    fun validate(body: JsonNode): Validation = DefinitionValidator(catalogue, ::fragment).validate(body)

    /** What the runtime will read once [definition] is published: its fragments inlined, printed as schema-2 JSON. */
    fun compile(definition: Definition): JsonNode = DefinitionJson.print(DefinitionCompiler.inline(definition, ::fragment))

    private fun fragment(key: String): Definition? =
        versions
            .definition(key)
            ?.takeIf { it.fragment }
            ?.let { definition -> versions.versions(listOf(definition.id)).firstOrNull { it.state == VersionState.PUBLISHED } }
            ?.let { compiled.get(it.id) }
}
