package com.memo.memo

import com.memo.testsupport.AppRoleJdbc
import com.memo.testsupport.MemoIntegrationTest
import com.memo.testsupport.Person
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.test.web.servlet.assertj.MvcTestResult
import tools.jackson.databind.JsonNode
import java.util.UUID

/**
 * The E03 task actions on memos (design E03 §4.4, §12; test plan T5): what each person may do according to the step,
 * referrals and opinions, a question to the initiator, and a send-back, through the memo endpoints and view.
 */
class MemoTaskActionsIT : MemoIntegrationTest() {
    private fun consultingType(session: Person) {
        val all = """["APPROVE","RETURN","REJECT","SEND_BACK","FORWARD","DELEGATE","ASK","REFER"]"""
        val body = """{"schemaVersion":2,"start":"route","steps":[{"id":"route","type":"route","name":"Approvers","actions":$all,
                    "next":"end"}]}"""
        val typeId = UUID.fromString(generalType(session.session))
        AppRoleJdbc.inTransaction(tenant.id) { c ->
            val definition = UUID.randomUUID()
            c
                .prepareStatement(
                    "insert into workflow_definition (id, tenant_id, key, name, built_in, created_at) " +
                        "values (?, ?, 'consult', 'Consult', false, now())",
                ).use {
                    it.setObject(1, definition)
                    it.setObject(2, tenant.id.value)
                    it.executeUpdate()
                }
            c
                .prepareStatement(
                    """
                insert into workflow_definition_version
                    (id, tenant_id, definition_id, number, body, compiled, schema_version, state, created_at)
                values (?, ?, ?, 1, ?::jsonb, ?::jsonb, 2, 'PUBLISHED', now())
                """,
                ).use {
                    it.setObject(1, UUID.randomUUID())
                    it.setObject(2, tenant.id.value)
                    it.setObject(3, definition)
                    it.setString(4, body)
                    it.setString(5, body)
                    it.executeUpdate()
                }
            c.prepareStatement("update memo_type set definition_key = 'consult' where id = ?").use {
                it.setObject(1, typeId)
                it.executeUpdate()
            }
            c.commit()
        }
    }

    private fun view(
        id: String,
        person: Person,
    ): JsonNode = get(id, person.session).json()

    private fun act(
        task: String,
        action: String,
        person: Person,
        body: String,
    ): MvcTestResult = post("/api/v1/tasks/$task/$action", person.session, body)

    @Test
    fun `holders see their step's actions, consult others, answer questions and send the memo back`() {
        val alice = person()
        val bob = person()
        val carol = person()
        val dave = person()
        val stranger = person()
        consultingType(alice)
        val id = aDraft(alice)
        uploadDocument(id, alice.session)
        route(id, alice, bob, carol)
        assertThat(post("/api/v1/memos/$id/submit", alice.session)).hasStatusOk()

        val atBob = view(id, bob)
        assertThat(atBob["myTask"]["role"].asString()).isEqualTo("DECIDE")
        assertThat(atBob["myTask"]["stepName"].asString()).isEqualTo("Approvers")
        assertThat(atBob["allowedActions"].toList().map { it.asString() })
            .containsExactly("APPROVE", "RETURN", "REJECT", "FORWARD", "DELEGATE", "ASK", "REFER")
        val bobTask = atBob["myTask"]["id"].asString()
        assertThat(act(bobTask, "refer", stranger, """{"to":["${dave.id}"],"remark":"views?"}""")).hasStatus(HttpStatus.NOT_FOUND)

        assertThat(act(bobTask, "refer", bob, """{"to":["${dave.id}"],"remark":"Is the estimate sound?"}""")).hasStatusOk()
        val atDave = view(id, dave)
        assertThat(atDave["myTask"]["role"].asString()).isEqualTo("OPINION")
        assertThat(atDave["allowedActions"].toList().map { it.asString() }).containsExactly("OPINION")
        assertThat(act(atDave["myTask"]["id"].asString(), "opinion", dave, """{"remark":"Within 5%"}""")).hasStatusOk()
        assertThat(act(bobTask, "approve", bob, "{}")).hasStatusOk()

        val atCarol = view(id, carol)
        assertThat(atCarol["allowedActions"].toList().map { it.asString() }).contains("SEND_BACK")
        val target = atCarol["sendBackTargets"].single()
        assertThat(target["holder"]["id"].asString()).isEqualTo(bob.id.toString())
        val carolTask = atCarol["myTask"]["id"].asString()

        assertThat(act(carolTask, "ask", carol, """{"to":"${alice.id}","remark":"Which budget line?"}""")).hasStatusOk()
        val waiting = view(id, carol)
        assertThat(waiting["myTask"]["waiting"].asBoolean()).isTrue()
        assertThat(waiting["allowedActions"].toList().map { it.asString() }).doesNotContain("APPROVE", "SEND_BACK")
        val atAlice = view(id, alice)
        assertThat(atAlice["myTask"]["role"].asString()).isEqualTo("CLARIFY")
        assertThat(atAlice["allowedActions"].toList().map { it.asString() }).contains("ANSWER")
        assertThat(act(atAlice["myTask"]["id"].asString(), "answer", alice, """{"remark":"Line 4.2"}""")).hasStatusOk()

        val back = act(carolTask, "send-back", carol, """{"targetStepId":"${target["stepId"].asString()}","remark":"Recheck with 4.2"}""")
        assertThat(back).hasStatusOk()
        assertThat(view(id, bob)["myTask"]["role"].asString()).isEqualTo("DECIDE")
        val kinds =
            read("/api/v1/memos/$id/history", alice.session).json().flatMap { round ->
                round["actions"].toList().map { it["kind"].asString() }
            }
        assertThat(kinds).contains("REFER", "OPINION", "ASK", "ANSWER", "SEND_BACK")
    }
}
