package com.memo.workflow.definition

import com.memo.workflow.AttributeType
import com.networknt.schema.InputFormat
import com.networknt.schema.Schema
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import com.networknt.schema.path.NodePath
import tools.jackson.databind.JsonNode
import java.util.Locale

/** One reason a definition is refused: where (a JSON pointer into the body), a stable code and a sentence for the admin. */
data class DefinitionProblem(
    val pointer: String,
    val code: String,
    val message: String,
)

/** The outcome of [DefinitionValidator.validate]: the parsed definition when the shape was right, and every problem found. */
data class Validation(
    val definition: Definition?,
    val problems: List<DefinitionProblem>,
) {
    val valid: Boolean get() = definition != null && problems.isEmpty()
}

/**
 * Checks a definition body (ADR-0038): the JSON Schema first, then the rules a schema cannot express. [catalogue] is the
 * subject type's attribute catalogue (null: not checked); [fragments] finds a published fragment by key.
 */
class DefinitionValidator(
    private val catalogue: Map<String, AttributeType>?,
    private val fragments: (String) -> Definition?,
) {
    fun validate(body: JsonNode): Validation {
        val shape =
            schema.validate(body) { context ->
                context.executionConfig { it.formatAssertionsEnabled(true).locale(Locale.ENGLISH) }
            }
        if (shape.isNotEmpty()) {
            return Validation(null, shape.map { DefinitionProblem(pointer(it.instanceLocation), "SCHEMA", it.message) }.distinct())
        }
        val definition = DefinitionJson.parse(body)
        return Validation(definition, Rules(definition).check())
    }

    private inner class Rules(
        private val definition: Definition,
    ) {
        private val problems = mutableListOf<DefinitionProblem>()
        private val index = definition.steps.withIndex().associate { (i, step) -> step.id to i }

        fun check(): List<DefinitionProblem> {
            ids()
            targets()
            if (problems.isEmpty()) graph()
            definition.steps.forEachIndexed { i, step -> step(i, step) }
            attributes()
            conditions()
            return problems
        }

        private fun ids() {
            definition.steps.forEachIndexed { i, step ->
                if (step.id == Definition.END) add("/steps/$i/id", "RESERVED_ID", "'end' is the final step and cannot be used as an id")
            }
            definition.steps
                .groupBy { it.id }
                .filterValues { it.size > 1 }
                .keys
                .forEach { id -> add("/steps/${index.getValue(id)}/id", "DUPLICATE_ID", "the id '$id' is used by more than one step") }
            if (definition.steps.count { it.type == StepType.ROUTE } > 1) {
                add("/steps", "MULTIPLE_ROUTES", "a definition can have at most one route step")
            }
        }

        private fun targets() {
            if (definition.start !in index) add("/start", "UNKNOWN_STEP", "the start step '${definition.start}' does not exist")
            definition.steps.forEachIndexed { i, step ->
                val links =
                    listOfNotNull(step.next?.let { it to "/steps/$i/next" }) +
                        step.branches.mapIndexed { b, branch -> branch.next to "/steps/$i/branches/$b/next" } +
                        listOfNotNull(step.otherwise?.let { it to "/steps/$i/otherwise" })
                links
                    .filter { (id, _) -> id != Definition.END && id !in index }
                    .forEach { (id, pointer) -> add(pointer, "UNKNOWN_STEP", "the step '$id' does not exist") }
            }
        }

        /** Every step reachable from the start, and no way back to a step already on the path. */
        private fun graph() {
            val reached = mutableSetOf<String>()
            val onPath = mutableSetOf<String>()
            val cycles = mutableSetOf<String>()

            fun walk(id: String) {
                when {
                    id == Definition.END || id in cycles -> {}

                    id in onPath -> {
                        cycles += id
                    }

                    reached.add(id) -> {
                        onPath += id
                        definition.step(id).targets.forEach(::walk)
                        onPath -= id
                    }
                }
            }
            walk(definition.start)
            cycles.forEach {
                add(
                    "/steps/${index.getValue(it)}",
                    "CYCLE",
                    "the step '$it' can be reached again from a later step; use send back instead of a loop",
                )
            }
            definition.steps.forEachIndexed { i, step ->
                if (step.id !in reached) add("/steps/$i", "UNREACHABLE", "the step '${step.id}' cannot be reached from the start")
            }
        }

        private fun step(
            i: Int,
            step: StepDef,
        ) {
            val at = "/steps/$i"
            when (step.type) {
                StepType.SIGN -> add(at, "NOT_AVAILABLE", "signing steps arrive in Phase 5")
                StepType.PARALLEL -> parallel(at, step)
                StepType.SUBWORKFLOW -> fragment(at, step)
                else -> Unit
            }
            listOfNotNull(step.assignee).plus(step.assignees).forEachIndexed { a, assignee ->
                if (step.type.human && assignee.kind != AssigneeKind.PERSON) {
                    val where = if (step.assignee != null) "$at/assignee" else "$at/assignees/$a"
                    add(where, "ASSIGNEE_KIND", "a step that decides needs a named person")
                }
            }
            if (step.type.human) actions(at, step)
        }

        private fun parallel(
            at: String,
            step: StepDef,
        ) {
            val n = step.n
            if (n != null && n > step.assignees.size) {
                add("$at/n", "PARALLEL_N", "n is $n but the step has only ${step.assignees.size} assignees")
            }
            val people = step.assignees.mapNotNull { it.userId }
            if (people.size != people.toSet().size) add("$at/assignees", "DUPLICATE_ASSIGNEE", "a person is listed twice")
        }

        private fun actions(
            at: String,
            step: StepDef,
        ) {
            val offered = step.offeredActions
            val positive = if (step.type == StepType.REVIEW) StepAction.RECOMMEND else StepAction.APPROVE
            val other = if (positive == StepAction.APPROVE) StepAction.RECOMMEND else StepAction.APPROVE
            if (positive !in offered) add("$at/actions", "ACTION_MISSING", "a ${step.type.json} step must offer ${positive.name}")
            if (other in offered) add("$at/actions", "ACTION_NOT_FOR_TYPE", "a ${step.type.json} step cannot offer ${other.name}")
            if (step.type == StepType.REVIEW && StepAction.REJECT in offered) {
                add("$at/actions", "ACTION_NOT_FOR_TYPE", "a review step cannot reject; it can return")
            }
            step.remarks.orEmpty().filterNot { it in offered }.forEach {
                add("$at/remarks", "REMARK_NOT_OFFERED", "a remark is required for ${it.name}, which the step does not offer")
            }
        }

        private fun fragment(
            at: String,
            step: StepDef,
        ) {
            val key = step.definition ?: return
            val fragment = fragments(key)
            when {
                fragment == null -> {
                    add("$at/definition", "FRAGMENT_NOT_FOUND", "no published fragment '$key'")
                }

                fragment.steps.any { it.type == StepType.ROUTE || it.type == StepType.SUBWORKFLOW } -> {
                    add("$at/definition", "FRAGMENT_NOT_ALLOWED", "the fragment '$key' contains a route or sub-workflow step")
                }

                else -> {
                    fragment.attributes.forEach { attribute ->
                        val own = definition.attributes.firstOrNull { it.name == attribute.name }
                        if (own != null && own.type != attribute.type) {
                            add("$at/definition", "ATTRIBUTE_CONFLICT", "'${attribute.name}' has another type in the fragment '$key'")
                        }
                    }
                }
            }
        }

        private fun attributes() {
            definition.attributes
                .groupBy { it.name }
                .filterValues { it.size > 1 }
                .keys
                .forEach { add("/attributes", "DUPLICATE_ATTRIBUTE", "the attribute '$it' is declared twice") }
            val known = catalogue ?: return
            definition.attributes.forEachIndexed { a, attribute ->
                val type = known[attribute.name]
                when {
                    type == null -> {
                        add("/attributes/$a", "UNKNOWN_ATTRIBUTE", "items of this kind have no attribute '${attribute.name}'")
                    }

                    type != attribute.type -> {
                        add("/attributes/$a", "ATTRIBUTE_TYPE", "'${attribute.name}' is a ${type.json}, not a ${attribute.type.json}")
                    }
                }
            }
        }

        private fun conditions() {
            val all =
                definition.steps.withIndex().flatMap { (i, step) ->
                    step.branches.withIndex().map { (b, branch) -> Triple(i, b, branch) }
                }
            if (all.size > MAX_CONDITIONS) add("/steps", "TOO_MANY_CONDITIONS", "at most $MAX_CONDITIONS conditions per definition")
            all.forEach { (i, b, branch) ->
                val (_, error) = Conditions.compile(branch.condition, definition.attributes)
                if (error != null) {
                    val where = if (error.line > 0) " (line ${error.line}, column ${error.column})" else ""
                    add("/steps/$i/branches/$b/when", "CONDITION", "${error.message}$where")
                }
            }
        }

        private fun add(
            pointer: String,
            code: String,
            message: String,
        ) {
            problems += DefinitionProblem(pointer, code, message)
        }
    }

    companion object {
        const val MAX_CONDITIONS = 20
        private const val SCHEMA = "/workflow/definition-schema-v2.json"

        private val schema: Schema =
            SchemaRegistry
                .withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
                .getSchema(checkNotNull(DefinitionValidator::class.java.getResource(SCHEMA)).readText(), InputFormat.JSON)

        private fun pointer(path: NodePath): String =
            (0 until path.nameCount).joinToString("") {
                "/" +
                    path
                        .getElement(it)
                        .toString()
                        .replace("~", "~0")
                        .replace("/", "~1")
            }
    }
}
