package com.memo.workflow.definition

import com.memo.workflow.AttributeType
import com.memo.workflow.calendar.SlaDuration
import com.networknt.schema.InputFormat
import com.networknt.schema.Schema
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SpecificationVersion
import com.networknt.schema.path.NodePath
import tools.jackson.databind.JsonNode
import java.util.Locale
import java.util.UUID

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
    references: DefinitionReferences = DefinitionReferences.ANY,
    private val fragments: (String) -> Definition?,
) {
    private val assignees = AssigneeChecks(references)

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
            problems += assignees.problems(at, step)
            if (step.type.human) actions(at, step)
            step.sla?.let { problems += slaProblems("$at/sla", it) }
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

/** The tenant's organisation as the validator checks references to it (design E04 §2); [ANY] accepts every id. */
interface DefinitionReferences {
    fun positionExists(id: UUID): Boolean

    fun roleExists(id: UUID): Boolean

    fun unitExists(id: UUID): Boolean

    fun queueExists(key: String): Boolean

    companion object {
        val ANY =
            object : DefinitionReferences {
                override fun positionExists(id: UUID) = true

                override fun roleExists(id: UUID) = true

                override fun unitExists(id: UUID) = true

                override fun queueExists(key: String) = true
            }
    }
}

/**
 * Who a step may go to (design E04 §2): the initiator only hears from notify steps, a fallback is a named person or a
 * queue, and positions, roles, units and queues must exist in the tenant.
 */
private class AssigneeChecks(
    private val references: DefinitionReferences,
) {
    fun problems(
        at: String,
        step: StepDef,
    ): List<DefinitionProblem> {
        val named =
            listOfNotNull(step.assignee?.let { "$at/assignee" to it }) +
                step.assignees.mapIndexed { a, assignee -> "$at/assignees/$a" to assignee } +
                step.recipients.mapIndexed { r, assignee -> "$at/recipients/$r" to assignee } +
                listOfNotNull(step.fallback?.let { "$at/fallback" to it }) +
                listOfNotNull(
                    step.sla
                        ?.escalate
                        ?.to
                        ?.let { "$at/sla/escalate/to" to it },
                )
        val kinds =
            listOfNotNull(
                step.assignee?.takeIf { step.type.human && it.kind == AssigneeKind.INITIATOR }?.let {
                    DefinitionProblem("$at/assignee", "ASSIGNEE_KIND", "the initiator cannot decide on their own item")
                },
                step.fallback?.takeIf { it.kind != AssigneeKind.PERSON && it.kind != AssigneeKind.QUEUE }?.let {
                    DefinitionProblem("$at/fallback", "FALLBACK_KIND", "a fallback is a named person or a queue")
                },
            ) +
                step.assignees.mapIndexedNotNull { a, assignee ->
                    DefinitionProblem("$at/assignees/$a", "ASSIGNEE_KIND", "the initiator cannot decide on their own item")
                        .takeIf { assignee.kind == AssigneeKind.INITIATOR }
                }
        return kinds + named.flatMap { (where, assignee) -> unknown(where, assignee) }
    }

    private fun unknown(
        at: String,
        assignee: Assignee,
    ): List<DefinitionProblem> =
        listOfNotNull(
            assignee.positionId?.takeUnless(references::positionExists)?.let {
                DefinitionProblem("$at/positionId", "UNKNOWN_POSITION", "no position $it in this organisation")
            },
            assignee.roleId?.takeUnless(references::roleExists)?.let {
                DefinitionProblem("$at/roleId", "UNKNOWN_ROLE", "no role $it in this organisation")
            },
            assignee.unit?.takeUnless(references::unitExists)?.let {
                DefinitionProblem("$at/unit", "UNKNOWN_UNIT", "no unit $it in this organisation")
            },
            assignee.queue?.takeUnless(references::queueExists)?.let {
                DefinitionProblem("$at/queue", "UNKNOWN_QUEUE", "no queue '$it' in this organisation")
            },
        )
}

/**
 * What an SLA must satisfy beyond the schema (design E05 §2): a reminder before the due time comes before it, a
 * reassignment says to whom, and nobody escalates to the initiator.
 */
internal fun slaProblems(
    at: String,
    sla: Sla,
): List<DefinitionProblem> {
    val before = sla.remind?.before
    val escalate = sla.escalate
    return listOfNotNull(
        DefinitionProblem("$at/remind/before", "SLA_REMIND_BEFORE", "a reminder before the due time must be shorter than the due time")
            .takeIf { before != null && roughMinutes(before) >= roughMinutes(sla.due) },
        DefinitionProblem("$at/escalate/to", "SLA_ESCALATE_TO", "say whom the task is reassigned to")
            .takeIf { escalate?.action == EscalateAction.REASSIGN && escalate.to == null },
        DefinitionProblem("$at/escalate/to", "ASSIGNEE_KIND", "the initiator cannot decide on their own item")
            .takeIf { escalate?.to?.kind == AssigneeKind.INITIATOR },
    )
}

/** A duration in minutes, a day counted as 24 hours: enough to compare a reminder with its due time. */
private fun roughMinutes(iso: String): Long =
    runCatching { SlaDuration.parse(iso) }.getOrNull()?.let { it.days * MINUTES_PER_DAY + it.minutes } ?: 0

private const val MINUTES_PER_DAY = 24 * 60L
