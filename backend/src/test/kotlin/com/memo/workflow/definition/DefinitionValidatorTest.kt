package com.memo.workflow.definition

import com.memo.workflow.AttributeType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

/** Every rule of design E03 §2, each with a definition that passes and one that fails at the expected pointer. */
class DefinitionValidatorTest {
    private val json = JsonMapper.builder().build()
    private val alice = "00000000-0000-0000-0000-00000000000a"
    private val bob = "00000000-0000-0000-0000-00000000000b"
    private val carol = "00000000-0000-0000-0000-00000000000c"
    private val catalogue = mapOf("memoTypeId" to AttributeType.STRING, "classificationRank" to AttributeType.INT)
    private val fragments = mutableMapOf<String, Definition>()
    private val validator = DefinitionValidator(catalogue) { fragments[it] }

    private fun body(text: String): JsonNode = json.readTree(text.replace("ALICE", alice).replace("BOB", bob).replace("CAROL", carol))

    private fun problems(text: String) = validator.validate(body(text)).problems.map { it.code to it.pointer }

    private val valid =
        """
        {"schemaVersion":2,
         "attributes":[{"name":"classificationRank","type":"int"}],
         "start":"review",
         "steps":[
           {"id":"review","type":"review","name":"Department review","assignee":{"kind":"person","userId":"ALICE"},"next":"route"},
           {"id":"route","type":"route","next":"size"},
           {"id":"size","type":"branch","branches":[{"when":"item.classificationRank >= 3","next":"board"}],"otherwise":"end"},
           {"id":"board","type":"parallel","mode":"n-of-m","n":2,
            "assignees":[{"kind":"person","userId":"ALICE"},{"kind":"person","userId":"BOB"},{"kind":"person","userId":"CAROL"}],
                    "next":"tell"},
           {"id":"tell","type":"notify","recipients":[{"kind":"initiator"}],"message":"approvedByBoard","next":"end"}
         ]}
        """

    @Test
    fun `a definition using every runnable step type is valid`() {
        val result = validator.validate(body(valid))
        assertThat(result.problems).isEmpty()
        assertThat(result.valid).isTrue()
        assertThat(result.definition!!.steps).hasSize(5)
    }

    @Test
    fun `the shape is checked by the schema, with pointers to the offending value`() {
        assertThat(
            problems("""{"schemaVersion":2,"start":"a","steps":[{"id":"A","type":"approve"}]}""").map { it.first },
        ).containsOnly("SCHEMA")
        assertThat(
            problems(
                """{"schemaVersion":2,"start":"a","steps":[{"id":"a","type":"approve","assignee":{"kind":"person",
                    "userId":"nope"},"next":"end"}]}""",
            ),
        ).contains("SCHEMA" to "/steps/0/assignee/userId")
        assertThat(
            problems(
                """{"schemaVersion":2,"start":"a","steps":[{"id":"a","type":"parallel","mode":"all","n":1,
                    "assignees":[{"kind":"person","userId":"ALICE"},{"kind":"person","userId":"BOB"}],"next":"end"}]}""",
            ),
        ).extracting<String> { it.first }
            .contains("SCHEMA")
        assertThat(
            problems(
                """{"schemaVersion":2,"start":"a","steps":[{"id":"a","type":"branch","branches":[{"when":"true","next":"end"}],
                    "otherwise":"end","next":"end"}]}""",
            ),
        ).extracting<String> { it.first }
            .contains("SCHEMA")
        assertThat(problems("""{"schemaVersion":1,"start":"a","steps":[]}""").map { it.first }).containsOnly("SCHEMA")
    }

    @Test
    fun `ids are unique, 'end' is reserved, and every link resolves`() {
        assertThat(problems(definition("a", approveStep("a", "end"), approveStep("a", "end")))).contains("DUPLICATE_ID" to "/steps/1/id")
        assertThat(problems(approve("end", next = "end", start = "end"))).contains("RESERVED_ID" to "/steps/0/id")
        assertThat(problems(approve("a", next = "missing"))).contains("UNKNOWN_STEP" to "/steps/0/next")
        assertThat(problems(approve("a", next = "end", start = "b"))).contains("UNKNOWN_STEP" to "/start")
    }

    @Test
    fun `every step is reachable and the graph has no loops`() {
        assertThat(problems(definition("a", approveStep("a", "end"), approveStep("b", "end")))).contains("UNREACHABLE" to "/steps/1")
        assertThat(
            problems(definition("a", approveStep("a", "b"), approveStep("b", "a"))),
        ).extracting<String> { it.first }.contains("CYCLE")
    }

    @Test
    fun `at most one route step, and signing waits for Phase 5`() {
        val routes = """{"schemaVersion":2,"start":"r1","steps":[{"id":"r1","type":"route","next":"r2"},{"id":"r2",
                    "type":"route","next":"end"}]}"""
        assertThat(problems(routes)).contains("MULTIPLE_ROUTES" to "/steps")
        val sign = """{"schemaVersion":2,"start":"s","steps":[{"id":"s","type":"sign","assignee":{"kind":"person",
                    "userId":"ALICE"},"next":"end"}]}"""
        assertThat(problems(sign)).contains("NOT_AVAILABLE" to "/steps/0")
    }

    @Test
    fun `deciding steps need named people, offer their own positive action and require remarks only for offered actions`() {
        val initiatorDecides = """{"schemaVersion":2,"start":"a","steps":[{"id":"a","type":"approve",
                    "assignee":{"kind":"initiator"},"next":"end"}]}"""
        assertThat(problems(initiatorDecides)).contains("ASSIGNEE_KIND" to "/steps/0/assignee")
        // E04: steps may go to resolvers over the organisation; a fallback is a named person.
        val resolved = """{"schemaVersion":2,"start":"a","steps":[{"id":"a","type":"approve",
                    "assignee":{"kind":"supervisor","levels":2},"fallback":{"kind":"unitHead","unit":"initiator"},"next":"end"}]}"""
        assertThat(problems(resolved)).containsExactly("FALLBACK_KIND" to "/steps/0/fallback")
        val missingLevels = """{"schemaVersion":2,"start":"a","steps":[{"id":"a","type":"approve",
                    "assignee":{"kind":"supervisor"},"next":"end"}]}"""
        assertThat(problems(missingLevels).map { it.first }).containsOnly("SCHEMA")
        assertThat(problems(approve("a", next = "end", actions = """["RECOMMEND","RETURN"]""")).map { it.first })
            .contains("ACTION_MISSING", "ACTION_NOT_FOR_TYPE")
        val reviewRejects =
            """{"schemaVersion":2,"start":"r","steps":[{"id":"r","type":"review","assignee":{"kind":"person","userId":"ALICE"},
                    "actions":["RECOMMEND","REJECT"],"next":"end"}]}"""
        assertThat(problems(reviewRejects)).contains("ACTION_NOT_FOR_TYPE" to "/steps/0/actions")
        assertThat(problems(approve("a", next = "end", actions = """["APPROVE","RETURN"]""", remarks = """["FORWARD"]""")))
            .contains("REMARK_NOT_OFFERED" to "/steps/0/remarks")
        assertThat(
            problems(approve("a", next = "end", actions = """["APPROVE","RETURN","FORWARD"]""", remarks = """["FORWARD"]""")),
        ).isEmpty()
    }

    @Test
    fun `a parallel step cannot need more approvals than it has people, nor list a person twice`() {
        val tooMany =
            """{"schemaVersion":2,"start":"p","steps":[{"id":"p","type":"parallel","mode":"n-of-m","n":3,
            "assignees":[{"kind":"person","userId":"ALICE"},{"kind":"person","userId":"BOB"}],"next":"end"}]}"""
        assertThat(problems(tooMany)).contains("PARALLEL_N" to "/steps/0/n")
        val twice =
            """{"schemaVersion":2,"start":"p","steps":[{"id":"p","type":"parallel","mode":"all",
            "assignees":[{"kind":"person","userId":"ALICE"},{"kind":"person","userId":"ALICE"}],"next":"end"}]}"""
        assertThat(problems(twice)).contains("DUPLICATE_ASSIGNEE" to "/steps/0/assignees")
    }

    @Test
    fun `attributes must exist in the subject's catalogue with the same type`() {
        fun withAttribute(attribute: String) =
            """{"schemaVersion":2,"attributes":[$attribute],"start":"a","steps":[${approveStep("a", "end")}]}"""
        assertThat(problems(withAttribute("""{"name":"amount","type":"double"}"""))).contains("UNKNOWN_ATTRIBUTE" to "/attributes/0")
        assertThat(
            problems(withAttribute("""{"name":"classificationRank","type":"string"}""")),
        ).contains("ATTRIBUTE_TYPE" to "/attributes/0")
        assertThat(
            DefinitionValidator(null) { null }.validate(body(withAttribute("""{"name":"amount","type":"double"}"""))).problems,
        ).isEmpty()
    }

    @Test
    fun `conditions compile against the declared attributes, at most twenty per definition`() {
        fun branch(condition: String) =
            """{"schemaVersion":2,"attributes":[{"name":"classificationRank","type":"int"}],"start":"b","steps":[
            {"id":"b","type":"branch","branches":[{"when":${json.writeValueAsString(condition)},"next":"a"}],"otherwise":"end"},
            ${approveStep("a", "end")}]}"""
        assertThat(problems(branch("item.classificationRank > 2"))).isEmpty()
        assertThat(problems(branch("item.amount > 2"))).contains("CONDITION" to "/steps/0/branches/0/when")
        assertThat(problems(branch("item.classificationRank"))).contains("CONDITION" to "/steps/0/branches/0/when")
        val twentyOne =
            (1..21).joinToString(
                ",",
            ) { i ->
                """{"id":"b$i","type":"branch","branches":[{"when":"true","next":"${if (i < 21) "b${i + 1}" else "end"}"}],
                    "otherwise":"end"}"""
            }
        assertThat(problems("""{"schemaVersion":2,"start":"b1","steps":[$twentyOne]}""")).contains("TOO_MANY_CONDITIONS" to "/steps")
    }

    @Test
    fun `sub-workflows must name a published fragment without routes or nested sub-workflows`() {
        val call = """{"schemaVersion":2,"start":"s","steps":[{"id":"s","type":"subworkflow","definition":"legal","next":"end"}]}"""
        assertThat(problems(call)).contains("FRAGMENT_NOT_FOUND" to "/steps/0/definition")
        fragments["legal"] = Definition(start = "r", steps = listOf(StepDef(id = "r", type = StepType.ROUTE, next = Definition.END)))
        assertThat(problems(call)).contains("FRAGMENT_NOT_ALLOWED" to "/steps/0/definition")
        fragments["legal"] = Definition(start = "l", steps = listOf(StepDef(id = "l", type = StepType.APPROVE, next = Definition.END)))
        assertThat(problems(call)).isEmpty()
    }

    private fun definition(
        start: String,
        vararg steps: String,
    ): String = """{"schemaVersion":2,"start":"$start","steps":[${steps.joinToString(",")}]}"""

    private fun approveStep(
        id: String,
        next: String,
        actions: String? = null,
        remarks: String? = null,
    ): String {
        val extra = listOfNotNull(actions?.let { "\"actions\":$it" }, remarks?.let { "\"remarks\":$it" }).joinToString("") { ",$it" }
        return """{"id":"$id","type":"approve","assignee":{"kind":"person","userId":"ALICE"}$extra,"next":"$next"}"""
    }

    /** A definition starting at [start] with one approve step. */
    private fun approve(
        id: String,
        next: String,
        start: String = "a",
        actions: String? = null,
        remarks: String? = null,
    ): String = """{"schemaVersion":2,"start":"$start","steps":[${approveStep(id, next, actions, remarks)}]}"""
}
