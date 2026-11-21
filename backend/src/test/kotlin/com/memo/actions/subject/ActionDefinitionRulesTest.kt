package com.memo.actions.subject

import com.memo.workflow.SubjectDefinition
import com.memo.workflow.SubjectDefinitionStep
import com.memo.workflow.definition.BoundSubject
import com.memo.workflow.definition.DefinitionValidator
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper

/**
 * The action item's own rules for its definitions (design E07 §1.8, ADR-0068's `checkDefinition`; test plan T2
 * `ActionDefinitionSeedIT` "passes the subject's check", and the bad definitions of T3's `ActionSubjectIT`): the built-in
 * definition passes the engine's validator with the subject's slots and rules, and each rule refuses what it should.
 */
class ActionDefinitionRulesTest {
    private val subject = ActionItemSubject()
    private val json = JsonMapper.builder().build()
    private val validator =
        DefinitionValidator(
            emptyMap(),
            subjects = { type ->
                BoundSubject(subject.attributes, subject.slots, subject::checkDefinition).takeIf { type == subject.type }
            },
        ) { null }

    private fun codes(body: String) = validator.validate(json.readTree(body)).problems.map { "${it.pointer} ${it.code}" }

    private fun owner(
        actions: String = """["RECOMMEND"]""",
        sla: String = """{"from":"subject","remind":{"before":"P5D"}}""",
        slot: String = "owner",
        next: String = "verify",
    ) = """{"id":"owner","type":"review","name":"Owner","assignee":{"kind":"subject","slot":"$slot"},"actions":$actions,
            "sla":$sla,"next":"$next"}"""

    private fun verify(actions: String = """["APPROVE","SEND_BACK"]""") =
        """{"id":"verify","type":"approve","name":"Verification","assignee":{"kind":"subject","slot":"verifier"},
            "actions":$actions,"remarks":["SEND_BACK"],"next":"end"}"""

    private fun body(vararg steps: String) =
        """{"schemaVersion":2,"subject":"action-item","start":"owner","steps":[${steps.joinToString(",")}]}"""

    @Test
    fun `the built-in definition passes the engine's checks and the subject's`() {
        val seeded = subject.seededDefinitions.single()
        assertThat(seeded.key).isEqualTo("action-item")
        assertThat(seeded.name).isEqualTo("Action items")
        val result = validator.validate(json.readTree(seeded.body))
        assertThat(result.problems).isEmpty()
        val definition = checkNotNull(result.definition)
        assertThat(definition.subject).isEqualTo("action-item")
        assertThat(definition.start).isEqualTo("owner")
        assertThat(definition.step("owner").sla!!.fromSubject).isTrue()
        assertThat(
            definition
                .step("verify")
                .sla!!
                .escalate!!
                .to!!
                .slot,
        ).isEqualTo("chair")
        assertThat(subject.slots).containsExactlyInAnyOrder("owner", "verifier", "chair", "secretaries")
        assertThat(subject.initiatorTakesPart).isTrue()
    }

    @Test
    fun `the action starts with its owner, due on the action's date`() {
        assertThat(codes(body(owner(), verify()))).isEmpty()
        assertThat(codes(body(owner(slot = "chair"), verify()))).containsExactly("/steps/0 ACTION_START_WITH_OWNER")
        assertThat(codes(body(owner(sla = """{"due":"P5D"}"""), verify()))).containsExactly("/steps/0 ACTION_START_WITH_OWNER")
    }

    @Test
    fun `it is sent back, never rejected or returned, anywhere`() {
        assertThat(codes(body(owner(actions = """["RECOMMEND","RETURN"]"""), verify())))
            .containsExactly("/steps/0/actions ACTION_NOT_REJECTED")
        assertThat(codes(body(owner(), verify(actions = """["APPROVE","REJECT","SEND_BACK"]"""))))
            .containsExactly("/steps/1/actions ACTION_NOT_REJECTED")
    }

    @Test
    fun `the owner keeps the action`() {
        assertThat(codes(body(owner(actions = """["RECOMMEND","FORWARD"]"""), verify())))
            .containsExactly("/steps/0/actions ACTION_OWNER_KEEPS")
        assertThat(codes(body(owner(actions = """["RECOMMEND","DELEGATE"]"""), verify())))
            .containsExactly("/steps/0/actions ACTION_OWNER_KEEPS")
    }

    @Test
    fun `closure is always verified by a later step`() {
        assertThat(codes(body(owner(next = "end")))).containsExactly("/steps ACTION_VERIFIED")
        // Through a step that holds no task (a notice), the end comes without verification.
        val notice =
            """{"id":"tell","type":"notify","recipients":[{"kind":"subject","slot":"chair"}],"message":"actionDone","next":"end"}"""
        assertThat(codes(body(owner(next = "tell"), notice))).containsExactly("/steps ACTION_VERIFIED")
        assertThat(codes(body(owner(next = "tell"), notice.replace("\"end\"", "\"verify\""), verify()))).isEmpty()
    }

    @Test
    fun `a branch around verification is refused, and a way back to the owner is not a way to the end`() {
        fun step(
            id: String,
            human: Boolean,
            vararg targets: String,
        ) = SubjectDefinitionStep(id, if (human) "approve" else "branch", human, null, false, emptyList(), targets.toList())
        val owner = SubjectDefinitionStep("owner", "review", true, "owner", true, listOf("RECOMMEND"), listOf("route"))
        val verify = step("verify", true, "end")
        val around = SubjectDefinition("action-item", "owner", listOf(owner, step("route", false, "verify", "end"), verify))
        assertThat(ActionDefinitionRules.problems(around).map { it.code }).containsExactly(ActionDefinitionRules.VERIFIED)
        val back = SubjectDefinition("action-item", "owner", listOf(owner, step("route", false, "verify", "owner"), verify))
        assertThat(ActionDefinitionRules.problems(back)).isEmpty()
        val missing = SubjectDefinition("action-item", "start", listOf(owner))
        assertThat(ActionDefinitionRules.problems(missing).map { "${it.pointer} ${it.code}" })
            .containsExactly("/steps ACTION_START_WITH_OWNER")
    }
}
