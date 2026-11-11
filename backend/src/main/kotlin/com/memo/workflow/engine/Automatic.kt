package com.memo.workflow.engine

import com.memo.workflow.ActionKind
import com.memo.workflow.definition.CompiledCondition
import com.memo.workflow.definition.ConditionOutcome
import com.memo.workflow.definition.Conditions
import com.memo.workflow.definition.Definition
import com.memo.workflow.definition.StepDef
import java.time.Instant
import java.util.UUID

/** An action the engine records itself (a branch taken, a notification, an escalation, a carried approval). */
internal fun automatic(
    kind: ActionKind,
    round: Int,
    actor: UUID,
    details: Map<String, Any?>,
): ActionRecord = ActionRecord(kind, round, null, actor, null, details)

/**
 * Branch steps (design E03 §4.2, ADR-0039): the first condition that is true wins, else `otherwise`; the record keeps
 * the attribute values read and which conditions could not be evaluated. Conditions compile once per [Branches].
 */
internal class Branches(
    private val context: TransitionContext,
    private val definition: Definition,
) {
    private val compiled = mutableMapOf<String, CompiledCondition>()
    private val values: Map<String, Any?> by lazy { context.attributes() }

    fun choose(
        node: StepDef,
        round: Int,
        actor: UUID,
    ): Pair<String, ActionRecord> {
        val inputs = linkedMapOf<String, Any?>()
        val failed = mutableListOf<Int>()
        val taken =
            node.branches.indices.firstOrNull { i ->
                val condition = condition(node.branches[i].condition)
                condition.reads.forEach { inputs[it] = shown(values[it]) }
                when (val outcome = condition.evaluate(values)) {
                    is ConditionOutcome.Value -> outcome.result
                    is ConditionOutcome.Failed -> false.also { failed += i }
                }
            }
        val next = taken?.let { node.branches[it].next } ?: checkNotNull(node.otherwise)
        val details = mapOf("step" to node.id, "taken" to (taken ?: "otherwise"), "inputs" to inputs, "failed" to failed)
        return next to automatic(ActionKind.ROUTE, round, actor, details)
    }

    private fun condition(text: String): CompiledCondition =
        compiled.getOrPut(text) {
            val (condition, error) = Conditions.compile(text, definition.attributes)
            checkNotNull(condition) { "a published condition does not compile: ${error?.message}" }
        }

    private companion object {
        const val MAX_SHOWN = 200

        /** Attribute values as the history shows them: JSON-friendly and short. */
        fun shown(value: Any?): Any? =
            when (value) {
                null, is Boolean, is Number -> value
                is Instant -> value.toString()
                else -> value.toString().take(MAX_SHOWN)
            }
    }
}
