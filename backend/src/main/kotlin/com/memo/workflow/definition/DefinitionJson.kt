package com.memo.workflow.definition

import com.memo.workflow.AttributeType
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.JsonNodeFactory
import tools.jackson.databind.node.ObjectNode
import java.util.UUID

/**
 * Reads and writes definitions as JSON (ADR-0038). [parse] expects a body that passed the JSON Schema, so it trusts the
 * shape; [print] writes the canonical form (fixed property order, absent values left out), and parse(print(d)) = d.
 */
object DefinitionJson {
    private val nodes = JsonNodeFactory.instance

    fun parse(json: JsonNode): Definition =
        Definition(
            attributes = json.array("attributes").map { Attribute(it["name"].asString(), AttributeType.of(it["type"].asString())) },
            resubmit = json.text("resubmit")?.let(ResubmitPolicy::of),
            start = json["start"].asString(),
            steps = json.array("steps").map(::step),
        )

    fun print(definition: Definition): ObjectNode {
        val out = nodes.objectNode().put("schemaVersion", Definition.SCHEMA_VERSION)
        if (definition.attributes.isNotEmpty()) {
            val attributes = out.putArray("attributes")
            definition.attributes.forEach { attributes.addObject().put("name", it.name).put("type", it.type.json) }
        }
        definition.resubmit?.let { out.put("resubmit", it.json) }
        out.put("start", definition.start)
        val steps = out.putArray("steps")
        definition.steps.forEach { steps.add(print(it)) }
        return out
    }

    private fun step(json: JsonNode): StepDef =
        StepDef(
            id = json["id"].asString(),
            type = StepType.of(json["type"].asString()),
            name = json.text("name"),
            next = json.text("next"),
            assignee = json["assignee"]?.let(::assignee),
            assignees = json.array("assignees").map(::assignee),
            mode = json.text("mode")?.let(ParallelMode::of),
            n = json["n"]?.asInt(),
            actions = json["actions"]?.let { actions -> actions.toList().map { StepAction.valueOf(it.asString()) } },
            remarks = json["remarks"]?.let { remarks -> remarks.toList().map { StepAction.valueOf(it.asString()) } },
            branches = json.array("branches").map { Branch(it["when"].asString(), it["next"].asString()) },
            otherwise = json.text("otherwise"),
            recipients = json.array("recipients").map(::assignee),
            message = json.text("message"),
            definition = json.text("definition"),
            forum = json.text("forum"),
            sla = json["sla"]?.let { Sla(it["due"].asString(), it["workingTime"]?.asBoolean()) },
        )

    private fun assignee(json: JsonNode): Assignee =
        Assignee(AssigneeKind.of(json["kind"].asString()), json.text("userId")?.let(UUID::fromString))

    private fun print(step: StepDef): ObjectNode {
        val out = nodes.objectNode().put("id", step.id).put("type", step.type.json)
        step.name?.let { out.put("name", it) }
        people(out, step)
        behaviour(out, step)
        routing(out, step)
        return out
    }

    private fun people(
        out: ObjectNode,
        step: StepDef,
    ) {
        step.assignee?.let { out.set("assignee", print(it)) }
        if (step.assignees.isNotEmpty()) out.putArray("assignees").addAll(step.assignees.map(::print))
        step.mode?.let { out.put("mode", it.json) }
        step.n?.let { out.put("n", it) }
        if (step.recipients.isNotEmpty()) out.putArray("recipients").addAll(step.recipients.map(::print))
    }

    private fun behaviour(
        out: ObjectNode,
        step: StepDef,
    ) {
        step.actions?.let { actions -> out.putArray("actions").apply { actions.forEach { add(it.name) } } }
        step.remarks?.let { remarks -> out.putArray("remarks").apply { remarks.forEach { add(it.name) } } }
        step.message?.let { out.put("message", it) }
        step.definition?.let { out.put("definition", it) }
        step.forum?.let { out.put("forum", it) }
        step.sla?.let { sla ->
            val node = out.putObject("sla").put("due", sla.due)
            sla.workingTime?.let { node.put("workingTime", it) }
        }
    }

    private fun routing(
        out: ObjectNode,
        step: StepDef,
    ) {
        if (step.branches.isNotEmpty()) {
            val branches = out.putArray("branches")
            step.branches.forEach { branches.addObject().put("when", it.condition).put("next", it.next) }
        }
        step.otherwise?.let { out.put("otherwise", it) }
        step.next?.let { out.put("next", it) }
    }

    private fun print(assignee: Assignee): ObjectNode {
        val out = nodes.objectNode().put("kind", assignee.kind.json)
        assignee.userId?.let { out.put("userId", it.toString()) }
        return out
    }

    private fun JsonNode.text(name: String): String? = get(name)?.takeUnless { it.isNull }?.asString()

    private fun JsonNode.array(name: String): List<JsonNode> = get(name)?.toList().orEmpty()
}
