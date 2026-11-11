package com.memo.workflow.definition

import com.google.protobuf.Timestamp
import com.memo.workflow.AttributeType
import dev.cel.common.CelOptions
import dev.cel.common.types.CelType
import dev.cel.common.types.SimpleType
import dev.cel.compiler.CelCompilerFactory
import dev.cel.runtime.CelEvaluationException
import dev.cel.runtime.CelRuntime
import dev.cel.runtime.CelRuntimeFactory
import java.time.Instant

/** A condition that compiled; [reads] are the declared attributes its text mentions (recorded with each evaluation). */
class CompiledCondition internal constructor(
    val text: String,
    val reads: List<String>,
    private val program: CelRuntime.Program,
) {
    /** True or false for [values] (attribute name → value); [ConditionOutcome.Failed] when evaluation fails (a missing value). */
    fun evaluate(values: Map<String, Any?>): ConditionOutcome =
        try {
            val bound =
                values
                    .filterKeys { it in reads }
                    .mapNotNull { (name, value) -> value?.let { "$ITEM$name" to native(it) } }
                    .toMap()
            when (val result = program.eval(bound)) {
                is Boolean -> ConditionOutcome.Value(result)
                else -> ConditionOutcome.Failed("not a boolean")
            }
        } catch (e: CelEvaluationException) {
            ConditionOutcome.Failed(e.message ?: "evaluation failed")
        }

    private fun native(value: Any): Any =
        when (value) {
            is Int -> {
                value.toLong()
            }

            is Instant -> {
                Timestamp
                    .newBuilder()
                    .setSeconds(value.epochSecond)
                    .setNanos(value.nano)
                    .build()
            }

            else -> {
                value
            }
        }

    private companion object {
        const val ITEM = "item."
    }
}

sealed interface ConditionOutcome {
    data class Value(
        val result: Boolean,
    ) : ConditionOutcome

    data class Failed(
        val reason: String,
    ) : ConditionOutcome
}

/** Why a condition did not compile, with its position in the text (1-based; 0 when unknown). */
data class ConditionError(
    val message: String,
    val line: Int,
    val column: Int,
)

/**
 * The condition language (ADR-0039): CEL over `item.<attribute>` variables the definition declares, boolean result,
 * no comprehensions (so no loops), at most [MAX_LENGTH] characters. Regular expressions run on RE2.
 */
object Conditions {
    const val MAX_LENGTH = 1000
    private const val MAX_DEPTH = 32
    private val mentioned = Regex("""\bitem\.([A-Za-z][A-Za-z0-9]*)""")

    private val options: CelOptions =
        CelOptions
            .current()
            .enableComprehension(false)
            .enableHeterogeneousNumericComparisons(true)
            .maxExpressionCodePointSize(MAX_LENGTH)
            .maxParseRecursionDepth(MAX_DEPTH)
            .build()

    private val runtime: CelRuntime = CelRuntimeFactory.plannerRuntimeBuilder().setOptions(options).build()

    /** Compiles [text] against [attributes]; either the condition or the reason it is refused. */
    fun compile(
        text: String,
        attributes: List<Attribute>,
    ): Pair<CompiledCondition?, ConditionError?> =
        if (text.length > MAX_LENGTH) {
            null to ConditionError("longer than $MAX_LENGTH characters", 0, 0)
        } else {
            checked(text, attributes)
        }

    private fun checked(
        text: String,
        attributes: List<Attribute>,
    ): Pair<CompiledCondition?, ConditionError?> {
        val compiler =
            CelCompilerFactory
                .standardCelCompilerBuilder()
                .setOptions(options)
                .setResultType(SimpleType.BOOL)
                .apply { attributes.forEach { addVar("item.${it.name}", celType(it.type)) } }
                .build()
        val result = compiler.compile(text)
        if (result.hasError()) {
            val issue = result.errors.first()
            val location = issue.sourceLocation
            return null to ConditionError(issue.message, location.line.coerceAtLeast(0), location.column.coerceAtLeast(0) + 1)
        }
        val declared = attributes.map { it.name }.toSet()
        val reads =
            mentioned
                .findAll(text)
                .map { it.groupValues[1] }
                .filter { it in declared }
                .distinct()
                .toList()
        return CompiledCondition(text, reads, runtime.createProgram(result.ast)) to null
    }

    private fun celType(type: AttributeType): CelType =
        when (type) {
            AttributeType.STRING -> SimpleType.STRING
            AttributeType.INT -> SimpleType.INT
            AttributeType.DOUBLE -> SimpleType.DOUBLE
            AttributeType.BOOL -> SimpleType.BOOL
            AttributeType.TIMESTAMP -> SimpleType.TIMESTAMP
        }
}
