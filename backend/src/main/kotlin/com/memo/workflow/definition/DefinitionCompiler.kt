package com.memo.workflow.definition

import tools.jackson.databind.JsonNode

/**
 * Turns an authored definition into what the runtime reads (ADR-0038): bodies of older schema versions are upgraded,
 * and sub-workflow steps are replaced by their fragment's steps, so a published version never changes afterwards.
 */
object DefinitionCompiler {
    /** The E01 built-in body (schema 1): the initiator's route, then the end. */
    private val SEQUENTIAL =
        Definition(start = "route", steps = listOf(StepDef(id = "route", type = StepType.ROUTE, next = Definition.END)))

    /** Reads a stored body of [schemaVersion]; schema-1 bodies are the E01 sequential approval. */
    fun read(
        body: JsonNode,
        schemaVersion: Int,
    ): Definition =
        when (schemaVersion) {
            1 -> SEQUENTIAL
            Definition.SCHEMA_VERSION -> DefinitionJson.parse(body)
            else -> throw IllegalArgumentException("unknown definition schema version $schemaVersion")
        }

    /**
     * Inlines every sub-workflow step: the fragment's steps are copied in with ids `<step>/<fragment step>`, its `end`
     * continues at the sub-workflow step's `next`, and links to the sub-workflow step go to the fragment's start.
     * The validator has already refused unknown fragments and fragments that contain routes or sub-workflows.
     */
    fun inline(
        definition: Definition,
        fragments: (String) -> Definition?,
    ): Definition {
        val calls = definition.steps.filter { it.type == StepType.SUBWORKFLOW }
        if (calls.isEmpty()) return definition
        val called =
            calls.associate { call ->
                call.id to
                    checkNotNull(fragments(checkNotNull(call.definition))) { "fragment '${call.definition}' vanished" }
            }
        // Every call's entry first: a call's `next` may itself be another call.
        val entries = called.mapValues { (id, fragment) -> "$id/${fragment.start}" }

        fun link(target: String): String = entries[target] ?: target
        val copied = mutableListOf<StepDef>()
        val attributes = definition.attributes.toMutableList()
        calls.forEach { call ->
            val fragment = called.getValue(call.id)
            val prefix = "${call.id}/"
            val exit = link(checkNotNull(call.next))

            fun map(target: String): String = if (target == Definition.END) exit else prefix + target
            fragment.steps.forEach { step ->
                copied +=
                    step.copy(
                        id = prefix + step.id,
                        next = step.next?.let(::map),
                        branches = step.branches.map { it.copy(next = map(it.next)) },
                        otherwise = step.otherwise?.let(::map),
                    )
            }
            fragment.attributes.filter { a -> attributes.none { it.name == a.name } }.forEach { attributes += it }
        }
        val own =
            definition.steps
                .filterNot { it.type == StepType.SUBWORKFLOW }
                .map { step ->
                    step.copy(
                        next = step.next?.let(::link),
                        branches = step.branches.map { it.copy(next = link(it.next)) },
                        otherwise = step.otherwise?.let(::link),
                    )
                }
        return definition.copy(attributes = attributes, start = link(definition.start), steps = own + copied)
    }
}
