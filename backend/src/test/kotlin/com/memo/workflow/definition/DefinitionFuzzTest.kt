package com.memo.workflow.definition

import com.code_intelligence.jazzer.api.FuzzedDataProvider
import com.code_intelligence.jazzer.junit.FuzzTest
import com.memo.workflow.AttributeType
import tools.jackson.core.JacksonException
import tools.jackson.databind.json.JsonMapper

/**
 * Fuzz targets for what tenant administrators write (ADR-0038, ADR-0039; threat model D1, E1): condition text and whole
 * definition bodies. In the PR stage they replay their stored inputs; the nightly stage fuzzes each for a few minutes
 * (`JAZZER_FUZZ=1`). A target fails on any exception other than a refusal, and on any broken invariant.
 */
class DefinitionFuzzTest {
    private val json = JsonMapper.builder().build()
    private val attributes =
        listOf(
            Attribute("amount", AttributeType.INT),
            Attribute("rate", AttributeType.DOUBLE),
            Attribute("category", AttributeType.STRING),
            Attribute("urgent", AttributeType.BOOL),
        )

    @FuzzTest(maxDuration = "5m")
    fun conditions(data: FuzzedDataProvider) {
        val values =
            mapOf(
                "amount" to data.consumeLong(),
                "rate" to data.consumeRegularDouble(),
                "category" to data.consumeString(MAX_VALUE),
                "urgent" to data.consumeBoolean(),
            )
        val text = data.consumeRemainingAsString()
        val (condition, error) = Conditions.compile(text, attributes)
        check((condition == null) != (error == null)) { "exactly one of condition and error" }
        if (condition != null) {
            check(text.length <= Conditions.MAX_LENGTH)
            check(condition.reads.all { name -> attributes.any { it.name == name } })
            // Evaluation either answers or fails as a refusal; it never throws.
            condition.evaluate(values)
        }
    }

    @FuzzTest(maxDuration = "5m")
    fun definitions(data: FuzzedDataProvider) {
        val body =
            try {
                json.readTree(data.consumeRemainingAsString())
            } catch (_: JacksonException) {
                return
            }
        val result = DefinitionValidator(null) { null }.validate(body)
        check(result.problems.all { it.pointer.isEmpty() || it.pointer.startsWith("/") })
        if (result.valid) check(DefinitionJson.parse(DefinitionJson.print(checkNotNull(result.definition))) == result.definition)
    }

    private companion object {
        const val MAX_VALUE = 200
    }
}
