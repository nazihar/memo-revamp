package com.memo.memo

import com.memo.identity.MfaSeeding
import com.memo.testsupport.Audit
import com.memo.testsupport.MemoIntegrationTest
import com.memo.testsupport.Person
import com.memo.testsupport.adminSession
import jakarta.servlet.http.Cookie
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.servlet.assertj.MvcTestResult
import java.util.UUID

/**
 * Bulk actions on the inbox (design E05 §4; test plan E05 T4): each item as if alone with its own outcome, nothing rolled
 * back across items, at most 100, never a decision, reassignment only with the permission (and step-up), one audit entry.
 */
class InboxBulkIT : MemoIntegrationTest() {
    @Autowired lateinit var mfaSeeding: MfaSeeding

    @Test
    fun `watch and unwatch report each memo's outcome, and a memo the person cannot read is not found`() {
        val alice = person()
        val bob = person()
        val mine = aDraft(alice)
        val other = aDraft(alice)
        val hidden = aDraft(bob)
        val random = UUID.randomUUID().toString()

        val result = bulk(alice, "UNWATCH", mine, other)
        assertThat(outcomes(result)).containsExactly(mine to "OK", other to "OK")
        val watched = bulk(alice, "WATCH", mine, hidden, random)
        assertThat(outcomes(watched)).containsExactly(mine to "OK", hidden to "NOT_FOUND", random to "NOT_FOUND")
        assertThat(read("/api/v1/memos/$mine/watch", alice.session).json()["reason"].asString()).isEqualTo("MANUAL")
        assertThat(read("/api/v1/memos/$other/watch", alice.session).json()["watching"].asBoolean()).isFalse()
        Audit.assertAudited(tenant.id, "inbox.bulk.performed", null, alice.id.toString())
    }

    @Test
    fun `claims are checked one by one, and a task decided meanwhile is reported as changed`() {
        val alice = person()
        val bob = person()
        val first = submitted(alice, bob)
        val second = submitted(alice, bob)
        val decided = task(second, bob)
        assertThat(post("/api/v1/tasks/$decided/approve", bob.session)).hasStatusOk()

        // A step offered to Bob alone cannot be claimed; a decided one has moved on: both are reported, neither stops the rest.
        val result = bulk(bob, "CLAIM", task(first, bob), decided, UUID.randomUUID().toString())
        assertThat(outcomes(result).map { it.second }).containsExactly("CHANGED", "CHANGED", "NOT_FOUND")
    }

    @Test
    fun `reassigning needs the permission, and moves each open task on its own`() {
        val alice = person()
        val bob = person()
        val carol = person()
        val open = submitted(alice, bob)
        val decided = submitted(alice, bob)
        val openTask = task(open, bob)
        val decidedTask = task(decided, bob)
        assertThat(post("/api/v1/tasks/$decidedTask/approve", bob.session)).hasStatusOk()

        val refused = bulk(bob, "REASSIGN", openTask, to = carol.id)
        assertThat(outcomes(refused).single().second).isEqualTo("FORBIDDEN")

        // A tenant administrator holds workflow.tasks.reassign, and has just stepped up.
        val boss = mvc.adminSession(tenant, accounts, transactions, mfaSeeding)
        val moved = bulk(boss, "REASSIGN", openTask, decidedTask, to = carol.id, reason = "Bob is away")
        assertThat(outcomes(moved)).containsExactly(openTask to "OK", decidedTask to "CHANGED")
        assertThat(get(open, carol.session).json()["myTask"]["id"].isNull).isFalse()
    }

    @Test
    fun `no decision is accepted in bulk, nor too many items, nor a reassignment without a holder`() {
        val alice = person()
        val memo = aDraft(alice)
        assertThat(bulk(alice, "APPROVE", memo).errors()).containsExactly("action:NOT_ALLOWED")
        assertThat(bulk(alice, "WATCH", *Array(101) { UUID.randomUUID().toString() }).errors()).containsExactly("ids:COUNT")
        assertThat(bulk(alice, "WATCH").errors()).containsExactly("ids:COUNT")
        assertThat(bulk(alice, "REASSIGN", memo).errors()).containsExactly("to:REQUIRED")
    }

    private fun bulk(
        who: Person,
        action: String,
        vararg ids: String,
        to: UUID? = null,
        reason: String? = null,
    ): MvcTestResult = bulk(who.session, action, *ids, to = to, reason = reason)

    private fun bulk(
        session: Cookie,
        action: String,
        vararg ids: String,
        to: UUID? = null,
        reason: String? = null,
    ): MvcTestResult {
        val body =
            """{"action":"$action","ids":[${ids.joinToString(",") { "\"$it\"" }}]""" +
                (to?.let { ""","to":"$it"""" } ?: "") + (reason?.let { ""","reason":"$it"""" } ?: "") + "}"
        return post("/api/v1/inbox/bulk", session, body)
    }

    private fun outcomes(result: MvcTestResult): List<Pair<String, String>> {
        assertThat(result).hasStatusOk()
        val items = result.json()["results"]
        return (0 until items.size()).map { items[it]["id"].asString() to items[it]["outcome"].asString() }
    }

    private fun submitted(
        initiator: Person,
        approver: Person,
    ): String {
        val id = aDraft(initiator)
        uploadDocument(id, initiator.session)
        route(id, initiator, approver)
        assertThat(post("/api/v1/memos/$id/submit", initiator.session)).hasStatusOk()
        return id
    }

    private fun task(
        memo: String,
        approver: Person,
    ): String = get(memo, approver.session).json()["myTask"]["id"].asString()
}
