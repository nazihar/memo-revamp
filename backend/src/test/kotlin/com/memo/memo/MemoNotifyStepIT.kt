package com.memo.memo

import com.memo.memo.internal.MemoTypeFields
import com.memo.memo.internal.MemoTypes
import com.memo.memo.internal.RoutePolicy
import com.memo.testsupport.AppRoleJdbc
import com.memo.testsupport.MemoIntegrationTest
import com.memo.testsupport.Person
import com.memo.testsupport.aPassword
import com.memo.testsupport.anActiveUser
import com.memo.workflow.internal.DefinitionLifecycle
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus

/**
 * A definition's notify step on a memo (design E03 §2, test plan T9): a recipient cleared for the memo becomes a
 * participant and gets `MEMO_UPDATE`, so the link opens the memo; one who is not cleared is told nothing.
 */
class MemoNotifyStepIT : MemoIntegrationTest() {
    @Autowired lateinit var lifecycle: DefinitionLifecycle

    @Autowired lateinit var types: MemoTypes

    @Test
    fun `a cleared recipient of a notify step is told and can open the memo, an uncleared one is not`() {
        val alice = person(clearance = 1)
        val bob = person(clearance = 1)
        val carol = person(clearance = 1)
        val dave = person()
        val checker = accounts.anActiveUser(transactions, tenant, aPassword(), tenantAdmin = true)
        val body =
            """{"schemaVersion":2,"start":"approve","steps":[
              {"id":"approve","type":"approve","name":"Buyer","assignee":{"kind":"person","userId":"${bob.id}"},"next":"tell"},
              {"id":"tell","type":"notify","recipients":[{"kind":"person","userId":"${carol.id}"},{"kind":"person","userId":"${dave.id}"}],
               "message":"purchaseApproved","next":"end"}]}"""
        val draft = asUser(admin) { lifecycle.create("notify", "Notify", null, false, json.readTree(body)) }
        asUser(admin) { lifecycle.submit("notify", 1, draft.version.revision) }
        asUser(checker) { lifecycle.approve("notify", 1, null) }
        val type = asUser(admin) { types.create(MemoTypeFields("Purchase", null, false, true, "notify", RoutePolicy.FIXED)) }

        val memo =
            create(alice.session, """{"subject":"Laptops","typeId":"${type.id}","classificationId":"${levels()[1].id}"}""")
                .json()["id"]
                .asString()
        uploadDocument(memo, alice.session)
        assertThat(post("/api/v1/memos/$memo/submit", alice.session)).hasStatusOk()
        assertThat(get(memo, carol.session)).hasStatus(HttpStatus.NOT_FOUND)
        val task = get(memo, bob.session).json()["myTask"]["id"].asString()
        assertThat(post("/api/v1/tasks/$task/approve", bob.session)).hasStatusOk()

        assertThat(kinds(carol)).containsExactly("MEMO_UPDATE")
        assertThat(get(memo, carol.session)).hasStatusOk()
        assertThat(kinds(dave)).isEmpty()
        assertThat(get(memo, dave.session)).hasStatus(HttpStatus.NOT_FOUND)
        val history = read("/api/v1/memos/$memo/history", alice.session).json()[0]["actions"]
        val notify = (0 until history.size()).map { history[it] }.single { it["kind"].asString() == "NOTIFY" }
        assertThat(notify["details"]["message"].asString()).isEqualTo("purchaseApproved")
    }

    private fun kinds(user: Person): List<String> =
        AppRoleJdbc.inTransaction(tenant.id) { c ->
            c.prepareStatement("select kind from notification_item where user_id = ? order by created_at, id").use {
                it.setObject(1, user.id)
                it.executeQuery().use { rs -> generateSequence { if (rs.next()) rs.getString(1) else null }.toList() }
            }
        }
}
