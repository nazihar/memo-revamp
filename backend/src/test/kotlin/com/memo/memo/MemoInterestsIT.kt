package com.memo.memo

import com.memo.testsupport.Audit
import com.memo.testsupport.MemoIntegrationTest
import com.memo.testsupport.sendJson
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus

/**
 * Declared interests on memos (design E04 §4.4, test plan T4): the initiator lists interested people while the memo is
 * theirs, who then cannot be on the route; an approver stands aside on their task with a reason, and their step waits
 * for an administrator (the initiator chose them). Audited with ids only.
 */
class MemoInterestsIT : MemoIntegrationTest() {
    @Test
    fun `the initiator lists interested people, who cannot be approvers`() {
        val alice = person()
        val bob = person()
        val stranger = person()
        val id = aDraft(alice)

        assertThat(
            post("/api/v1/memos/$id/interests", alice.session, """{"userId":"${bob.id}"}""").errors(),
        ).containsExactly("reason:required")
        val declared = post("/api/v1/memos/$id/interests", alice.session, """{"userId":"${bob.id}","reason":"Beneficiary"}""")
        assertThat(declared).hasStatusOk()
        assertThat(declared.json()["interests"][0]["id"].asString()).isEqualTo(bob.id.toString())
        Audit.assertAudited(tenant.id, "memo.interest.declared", id, alice.id.toString())
        assertThat(
            post("/api/v1/memos/$id/interests", stranger.session, """{"userId":"${bob.id}","reason":"x"}"""),
        ).hasStatus(HttpStatus.NOT_FOUND)

        assertThat(route(id, alice, bob).errors()).containsExactly("route[0]:interested")

        val removed = mvc.sendJson(HttpMethod.DELETE, tenant, "/api/v1/memos/$id/interests/${bob.id}", alice.session)
        assertThat(removed.json()["interests"].size()).isZero()
        Audit.assertAudited(tenant.id, "memo.interest.removed", id, alice.id.toString())
        assertThat(route(id, alice, bob)).hasStatusOk()
    }

    @Test
    fun `an approver stands aside with a reason, and their step waits for an administrator`() {
        val alice = person()
        val bob = person()
        val id = aDraft(alice)
        uploadDocument(id, alice.session)
        route(id, alice, bob)
        post("/api/v1/memos/$id/submit", alice.session)
        val view = get(id, bob.session).json()
        assertThat((0 until view["allowedActions"].size()).map { view["allowedActions"][it].asString() }).contains("RECUSE")
        val task = view["myTask"]["id"].asString()

        assertThat(post("/api/v1/tasks/$task/recuse", bob.session, """{"remark":" "}""").errors()).containsExactly("reason:required")
        val recused = post("/api/v1/tasks/$task/recuse", bob.session, """{"remark":"I am the guarantor"}""")
        assertThat(recused).hasStatusOk()
        assertThat(recused.json()["interests"][0]["id"].asString()).isEqualTo(bob.id.toString())
        assertThat(recused.json()["holders"].size()).isZero()
        Audit.assertAudited(tenant.id, "memo.interest.declared", id, bob.id.toString())
        Audit.assertAudited(tenant.id, "workflow.task.recused", task, bob.id.toString())
    }
}
