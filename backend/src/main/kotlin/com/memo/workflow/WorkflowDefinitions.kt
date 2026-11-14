package com.memo.workflow

import com.memo.workflow.definition.StepType
import com.memo.workflow.internal.CompiledDefinitions
import com.memo.workflow.internal.DefinitionVersions
import com.memo.workflow.internal.DoaVersions
import com.memo.workflow.internal.VersionState
import io.swagger.v3.oas.annotations.media.Schema
import org.springframework.stereotype.Service

/** A published definition a subject type may run on (design E03 §5): whether it takes the initiator's route. */
data class DefinitionChoice(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val key: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val name: String,
    /** The definition has a `route` step: the initiator chooses approvers when submitting. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val routed: Boolean,
)

/**
 * Whether a definition is still chosen by something that starts workflows (for memos, a memo type), implemented by the
 * owning module: a definition in use cannot be retired (design E03 §5). Runs inside the caller's tenant transaction.
 */
fun interface WorkflowDefinitionUsage {
    fun inUse(definitionKey: String): Boolean
}

/** The published definitions other modules may choose from (design E03 §4.7). Inside a tenant transaction. */
@Service
class WorkflowDefinitions(
    private val versions: DefinitionVersions,
    private val compiled: CompiledDefinitions,
    private val doa: DoaVersions,
) {
    /** Every published definition that is not a fragment, by name. */
    fun published(): List<DefinitionChoice> {
        val definitions = versions.definitions().filterNot { it.fragment }
        val published = versions.versions(definitions.map { it.id }).filter { it.state == VersionState.PUBLISHED }
        return definitions.mapNotNull { definition ->
            published.firstOrNull { it.definitionId == definition.id }?.let { version ->
                DefinitionChoice(definition.key, definition.name, compiled.get(version.id).steps.any { it.type == StepType.ROUTE })
            }
        }
    }

    /** [key] if it is published and not a fragment, else null. */
    fun choice(key: String): DefinitionChoice? = published().firstOrNull { it.key == key }

    /**
     * The item attributes in use (ADR-0043): declared by a published definition or fragment, with their type, or read by a
     * condition of a published DoA rule for [subjectType] (type unknown: null). A subject must keep offering them, with
     * that type, or the rules reading them would stop matching.
     */
    fun attributesInUse(subjectType: String): Map<String, AttributeType?> {
        val definitions = versions.definitions()
        val declared =
            versions
                .versions(definitions.map { it.id })
                .filter { it.state == VersionState.PUBLISHED }
                .flatMap { compiled.get(it.id).attributes }
                .associate { it.name to it.type }
        val read =
            doa
                .versions()
                .filter { it.state == VersionState.PUBLISHED }
                .flatMap { doa.rules(it.id) }
                .filter { it.subjectType == subjectType }
                .flatMap { rule ->
                    rule.condition?.let { text -> ITEM_ATTRIBUTE.findAll(text).map { it.groupValues[1] }.toList() }.orEmpty()
                }.toSet()
        return read.associateWith { null as AttributeType? } + declared
    }

    private companion object {
        val ITEM_ATTRIBUTE = Regex("""\bitem\.([A-Za-z_][A-Za-z0-9_]*)""")
    }
}
