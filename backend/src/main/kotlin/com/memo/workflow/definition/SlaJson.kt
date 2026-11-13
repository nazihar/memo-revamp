package com.memo.workflow.definition

import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ObjectNode

/** A step's `sla` in a definition body (design E05 §2), read and printed for [DefinitionJson]. */
internal object SlaJson {
    fun read(
        json: JsonNode,
        assignee: (JsonNode) -> Assignee,
    ): Sla =
        Sla(
            json["due"].asString(),
            json["workingTime"]?.asBoolean(),
            json["remind"]?.let { r ->
                Remind(r["before"]?.takeUnless { it.isNull }?.asString(), r["after"]?.toList().orEmpty().map { it.asString() })
            },
            json["escalate"]?.let { e ->
                Escalate(e["after"].asString(), EscalateAction.of(e["action"].asString()), e["to"]?.let(assignee))
            },
        )

    fun write(
        node: ObjectNode,
        sla: Sla,
        assignee: (Assignee) -> ObjectNode,
    ) {
        node.put("due", sla.due)
        sla.workingTime?.let { node.put("workingTime", it) }
        sla.remind?.let { remind ->
            val r = node.putObject("remind")
            remind.before?.let { r.put("before", it) }
            if (remind.after.isNotEmpty()) r.putArray("after").apply { remind.after.forEach { add(it) } }
        }
        sla.escalate?.let { escalate ->
            val e = node.putObject("escalate").put("after", escalate.after).put("action", escalate.action.json)
            escalate.to?.let { e.set("to", assignee(it)) }
        }
    }
}
