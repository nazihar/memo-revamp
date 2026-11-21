package com.memo.workflow.definition

import com.memo.workflow.AttributeType
import com.memo.workflow.DefinitionProblem
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

/**
 * The validator's rules for definitions bound to a subject type (ADR-0068, E07 test plan T1): slots and due times only
 * with `subject`, only that subject's slots, its own attributes and its own rules; the JSON of the new keys round-trips.
 */
class SubjectDefinitionValidatorTest {
    private val json = JsonMapper.builder().build()
    private val fragments = mutableMapOf<String, Definition>()
    private val checked = mutableListOf<String>()
    private val subjects =
        mapOf(
            "action-item" to
                BoundSubject(mapOf("priority" to AttributeType.STRING), setOf("owner", "verifier", "chair")) { definition ->
                    checked += definition.subject
                    definition.steps
                        .filter { "REJECT" in it.actions }
                        .map { DefinitionProblem("${definition.pointer(it.id)}/actions", "NO_REJECT", "not rejected") }
                },
        )
    private val validator =
        DefinitionValidator(mapOf("memoTypeId" to AttributeType.STRING), subjects = subjects::get) { fragments[it] }

    private fun problems(text: String) = validator.validate(json.readTree(text)).problems.map { it.code to it.pointer }

    private val bound =
        """
        {"schemaVersion":2,"subject":"action-item","attributes":[{"name":"priority","type":"string"}],"start":"owner","steps":[
          {"id":"owner","type":"review","name":"Owner","assignee":{"kind":"subject","slot":"owner"},"actions":["RECOMMEND"],
           "sla":{"from":"subject","remind":{"before":"P5D","after":["P5D"]},"escalate":{"after":"P10D","action":"notify"}},
           "next":"verify"},
          {"id":"verify","type":"approve","name":"Verification","assignee":{"kind":"subject","slot":"verifier"},
           "actions":["APPROVE","SEND_BACK"],"remarks":["SEND_BACK"],
           "sla":{"due":"P5D","remind":{"after":["P3D"]},
                  "escalate":{"after":"P10D","action":"notify","to":{"kind":"subject","slot":"chair"}}},
           "next":"end"}]}
        """

    @Test
    fun `the action-item definition of the design is valid, and its subject's rules run`() {
        val result = validator.validate(json.readTree(bound))
        assertThat(result.problems).isEmpty()
        assertThat(checked).containsExactly("action-item")
        val definition = checkNotNull(result.definition)
        assertThat(definition.subject).isEqualTo("action-item")
        assertThat(definition.step("owner").assignee).isEqualTo(Assignee(AssigneeKind.SUBJECT, slot = "owner"))
        assertThat(definition.step("owner").sla!!.fromSubject).isTrue()
        assertThat(definition.step("owner").sla!!.due).isNull()
        assertThat(
            definition
                .step("verify")
                .sla!!
                .escalate!!
                .to!!
                .slot,
        ).isEqualTo("chair")
    }

    @Test
    fun `the new keys round-trip through print and parse`() {
        val definition = checkNotNull(validator.validate(json.readTree(bound)).definition)
        val printed = DefinitionJson.print(definition)
        assertThat(DefinitionJson.parse(printed)).isEqualTo(definition)
        assertThat(printed).isEqualTo(json.readTree(bound))
    }

    @Test
    fun `slots and due times from the subject need a bound definition`() {
        val unbound = bound.replace(""""subject":"action-item",""", "").replace("""{"name":"priority","type":"string"}""", "")
        assertThat(problems(unbound)).containsExactlyInAnyOrder(
            "SUBJECT_REQUIRED" to "/steps/0/assignee",
            "SUBJECT_REQUIRED" to "/steps/0/sla/from",
            "SUBJECT_REQUIRED" to "/steps/1/assignee",
            "SUBJECT_REQUIRED" to "/steps/1/sla/escalate/to",
        )
    }

    @Test
    fun `a slot the subject does not name is refused`() {
        assertThat(problems(bound.replace(""""slot":"verifier"""", """"slot":"auditor""""))).containsExactly(
            "UNKNOWN_SLOT" to "/steps/1/assignee/slot",
        )
    }

    @Test
    fun `an unknown subject type is refused`() {
        assertThat(problems(bound.replace(""""subject":"action-item"""", """"subject":"minutes""""))).contains(
            "UNKNOWN_SUBJECT" to "/subject",
        )
    }

    @Test
    fun `a bound definition reads its own subject's attributes`() {
        assertThat(problems(bound.replace(""""name":"priority"""", """"name":"memoTypeId""""))).containsExactly(
            "UNKNOWN_ATTRIBUTE" to "/attributes/0",
        )
    }

    @Test
    fun `the subject's own problems are reported`() {
        assertThat(problems(bound.replace(""""actions":["APPROVE","SEND_BACK"]""", """"actions":["APPROVE","SEND_BACK","REJECT"]""")))
            .containsExactly("NO_REJECT" to "/steps/1/actions")
    }

    @Test
    fun `a fragment bound to another subject type is refused`() {
        fragments["checks"] =
            Definition(
                start = "c",
                steps =
                    listOf(
                        StepDef(id = "c", type = StepType.APPROVE, assignee = Assignee(AssigneeKind.SUBJECT, slot = "x"), next = "end"),
                    ),
                subject = "minutes",
            )
        val withFragment =
            bound.replace(
                """"next":"end"}]}""",
                """"next":"more"},{"id":"more","type":"subworkflow","definition":"checks","next":"end"}]}""",
            )
        assertThat(problems(withFragment)).containsExactly("WRONG_SUBJECT" to "/steps/2/definition")
    }

    @Test
    fun `an SLA has a duration or the subject's due time, not both and not neither`() {
        assertThat(problems(bound.replace(""""sla":{"from":"subject",""", """"sla":{"from":"subject","due":"P1D","""))).isNotEmpty()
        assertThat(problems(bound.replace(""""sla":{"from":"subject",""", """"sla":{""")).map { it.first }).contains("SCHEMA")
        assertThat(problems(bound.replace(""""from":"subject"""", """"from":"item""""))).isNotEmpty()
    }

    @Test
    fun `without a subject lookup, subjects and slots are not checked`() {
        val open = DefinitionValidator(null) { null }
        val body: JsonNode = json.readTree(bound.replace(""""subject":"action-item"""", """"subject":"anything""""))
        assertThat(open.validate(body).problems).isEmpty()
    }
}
