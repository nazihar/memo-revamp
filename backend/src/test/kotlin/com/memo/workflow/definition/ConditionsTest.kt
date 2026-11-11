package com.memo.workflow.definition

import com.memo.workflow.AttributeType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

/** The condition language's limits and evaluation (ADR-0039). */
class ConditionsTest {
    private val attributes =
        listOf(
            Attribute("amount", AttributeType.INT),
            Attribute("rate", AttributeType.DOUBLE),
            Attribute("category", AttributeType.STRING),
            Attribute("urgent", AttributeType.BOOL),
            Attribute("due", AttributeType.TIMESTAMP),
        )

    private fun compiled(text: String): CompiledCondition {
        val (condition, error) = Conditions.compile(text, attributes)
        assertThat(error).describedAs(text).isNull()
        return checkNotNull(condition)
    }

    private fun error(text: String): ConditionError = checkNotNull(Conditions.compile(text, attributes).second) { "'$text' compiled" }

    @Test
    fun `conditions over every attribute type evaluate to what they say`() {
        val values =
            mapOf(
                "amount" to 150_000,
                "rate" to 0.5,
                "category" to "capex",
                "urgent" to true,
                "due" to Instant.parse("2026-12-01T00:00:00Z"),
            )
        assertThat(compiled("item.amount > 100000").evaluate(values)).isEqualTo(ConditionOutcome.Value(true))
        assertThat(compiled("item.rate < 0.25").evaluate(values)).isEqualTo(ConditionOutcome.Value(false))
        assertThat(compiled("item.category == 'capex' && item.urgent").evaluate(values)).isEqualTo(ConditionOutcome.Value(true))
        assertThat(compiled("item.category.matches('^cap')").evaluate(values)).isEqualTo(ConditionOutcome.Value(true))
        assertThat(compiled("item.due > timestamp('2026-11-11T00:00:00Z')").evaluate(values)).isEqualTo(ConditionOutcome.Value(true))
    }

    @Test
    fun `the attributes a condition reads are recorded, and a missing value fails instead of guessing`() {
        val condition = compiled("item.amount > 10 || item.category == 'x'")
        assertThat(condition.reads).containsExactly("amount", "category")
        assertThat(condition.evaluate(mapOf("category" to "y"))).isInstanceOf(ConditionOutcome.Failed::class.java)
    }

    @Test
    fun `undeclared attributes, non-boolean results, macros and oversized text are refused with a position`() {
        assertThat(error("item.unknown > 1").message).isNotBlank()
        assertThat(error("item.amount + 1").message).isNotBlank()
        assertThat(error("[1, 2].all(x, x > 0)").message).isNotBlank()
        assertThat(error("[1, 2].exists(x, x > item.amount)").message).isNotBlank()
        assertThat(error("x".repeat(Conditions.MAX_LENGTH + 1)).message).contains("${Conditions.MAX_LENGTH}")
        val located = error("item.amount >\n  'a'")
        assertThat(located.line).isGreaterThanOrEqualTo(1)
        assertThat(located.column).isGreaterThanOrEqualTo(1)
    }

    @Test
    fun `deep nesting is refused before it can exhaust the stack`() {
        assertThat(error("(".repeat(200) + "true" + ")".repeat(200)).message).isNotBlank()
    }
}
