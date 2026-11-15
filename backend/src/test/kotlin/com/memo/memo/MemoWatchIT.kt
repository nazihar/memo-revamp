package com.memo.memo

import com.memo.common.crypto.DataKeys
import com.memo.common.tenant.TenantDsl
import com.memo.memo.internal.MemoWatchDelivery
import com.memo.testsupport.AppRoleJdbc
import com.memo.testsupport.Audit
import com.memo.testsupport.MailOutbox
import com.memo.testsupport.MemoIntegrationTest
import com.memo.testsupport.Person
import com.memo.testsupport.sendJson
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import java.util.UUID

/**
 * Following memos (design E05 §4, flow J; test plan E05 T4): by hand for readers only (audited), automatically for the
 * initiator, those who act and those mentioned (unless turned off); news to followers but never to the actor, in the app
 * after a quiet spell and by email at once or in the digest; watches dropped once the person may not read the memo; and
 * the inbox by due time.
 */
class MemoWatchIT : MemoIntegrationTest() {
    @Autowired lateinit var delivery: MemoWatchDelivery

    @Autowired lateinit var tenantDsl: TenantDsl

    @Autowired lateinit var keys: DataKeys

    private val outbox by lazy { MailOutbox(keys, json) }

    @Test
    fun `a reader follows and unfollows by hand, and others cannot`() {
        val alice = person()
        val memo = aDraft(alice)
        // The initiator follows from the start.
        assertThat(watch(alice, memo).json()["reason"].asString()).isEqualTo("INITIATED")

        assertThat(mvc.sendJson(HttpMethod.DELETE, tenant, "/api/v1/memos/$memo/watch", alice.session, "{}", emptyMap()))
            .hasStatus(HttpStatus.NO_CONTENT)
        Audit.assertAudited(tenant.id, "memo.watch.removed", memo)
        assertThat(watch(alice, memo).json()["watching"].asBoolean()).isFalse()

        val again = put("/api/v1/memos/$memo/watch", alice.session, "{}", null)
        assertThat(again.json()["reason"].asString()).isEqualTo("MANUAL")
        Audit.assertAudited(tenant.id, "memo.watch.added", memo)
        assertThat(read("/api/v1/inbox/watching", alice.session).json()["items"][0]["memoId"].asString()).isEqualTo(memo)

        assertThat(put("/api/v1/memos/$memo/watch", person().session, "{}", null)).hasStatus(HttpStatus.NOT_FOUND)
    }

    @Test
    fun `those who act follow automatically unless they turned it off, and followers hear of others' actions only`() {
        val alice = person()
        val bob = person()
        val carol = person()
        // Carol hears at once by email and does not follow automatically.
        val settings = put("/api/v1/me/watch-settings", carol.session, """{"autoWatch":false,"email":"IMMEDIATE"}""", null)
        assertThat(settings).hasStatusOk()
        Audit.assertAudited(tenant.id, "memo.watch.settings_changed", carol.id.toString())
        val memo = submitted(alice, bob, carol)
        // Carol, an approver later on, reads it by a share and follows it by hand before Bob decides.
        post("/api/v1/memos/$memo/shares", alice.session, """{"userId":"${carol.id}","reason":"Next approver"}""")
        assertThat(put("/api/v1/memos/$memo/watch", carol.session, "{}", null)).hasStatusOk()
        approve(memo, bob)
        assertThat(watch(bob, memo).json()["reason"].asString()).isEqualTo("ACTED")

        // A note by Carol: Alice and Bob hear of it, Carol (who wrote it) does not.
        post("/api/v1/memos/$memo/notes", carol.session, """{"body":${note("Checked the figures.")}}""")
        approve(memo, carol)
        assertThat(watch(carol, memo).json()["reason"].asString()).isEqualTo("MANUAL")
        quietSpellOver()
        asUser(admin) { delivery.flush() }

        assertThat(kinds(alice)).contains("MEMO_WATCHED")
        assertThat(kinds(bob)).contains("MEMO_WATCHED")
        // Carol wrote the note and decided last: she heard only of Bob's approval, by email at once as she chose.
        assertThat(kinds(carol)).contains("MEMO_WATCHED")
        assertThat(outbox.all(tenant).filter { it.template == "WATCH_UPDATE" }.map { it.to }).containsOnly(carol.account.email)

        // The daily digest goes to those on the default: Alice and Bob, once.
        asUser(admin) { delivery.digest(force = true) }
        assertThat(outbox.all(tenant).filter { it.template == "WATCH_DIGEST" }.map { it.to })
            .containsExactlyInAnyOrder(alice.account.email, bob.account.email)
        asUser(admin) { delivery.digest(force = true) }
        assertThat(outbox.all(tenant).count { it.template == "WATCH_DIGEST" }).isEqualTo(2)
    }

    @Test
    fun `a person mentioned follows the memo, and a watch goes once they may not read it`() {
        val alice = person()
        val dave = person()
        val memo = aDraft(alice)
        post("/api/v1/memos/$memo/shares", alice.session, """{"userId":"${dave.id}","reason":"For the rent"}""")
        val mention = """{"type":"mention","attrs":{"id":"${dave.id}","label":"Dave"}}"""
        val body = """{"type":"doc","content":[{"type":"paragraph","content":[$mention,{"type":"text","text":" please look"}]}]}"""
        assertThat(post("/api/v1/memos/$memo/notes", alice.session, """{"body":$body}""")).hasStatus(HttpStatus.CREATED)
        assertThat(watch(dave, memo).json()["reason"].asString()).isEqualTo("MENTIONED")
        assertThat(read("/api/v1/inbox/watching", dave.session).json()["items"].size()).isEqualTo(1)

        // Dave loses access: the watch is not listed, and the nightly sweep removes it.
        write("update memo_participant set revoked_at = now() where memo_id = '$memo' and user_id = '${dave.id}'")
        assertThat(read("/api/v1/inbox/watching", dave.session).json()["items"].size()).isZero()
        asUser(admin) { delivery.sweep() }
        assertThat(count("select count(*) from memo_watch where memo_id = '$memo' and user_id = '${dave.id}'")).isZero()
    }

    @Test
    fun `the inbox by due time puts overdue first and tasks without a due time last`() {
        val alice = person()
        val bob = person()
        val none = submitted(alice, bob)
        val later = submitted(alice, bob)
        val overdue = submitted(alice, bob)
        due(later, bob, "now() + interval '2 days'")
        due(overdue, bob, "now() - interval '1 day'")

        val byDue = read("/api/v1/inbox/tasks?sort=due", bob.session).json()["items"]
        assertThat((0 until byDue.size()).map { byDue[it]["memo"]["id"].asString() }).containsExactly(overdue, later, none)
        val pages = read("/api/v1/inbox/tasks?sort=due&limit=2", bob.session).json()
        val next = read("/api/v1/inbox/tasks?sort=due&limit=2&cursor=${pages["nextCursor"].asString()}", bob.session).json()["items"]
        assertThat(next[0]["memo"]["id"].asString()).isEqualTo(none)
        val byAssigned = read("/api/v1/inbox/tasks", bob.session).json()["items"]
        assertThat(byAssigned[0]["memo"]["id"].asString()).isEqualTo(overdue)
        assertThat(read("/api/v1/inbox/tasks?sort=nonsense", bob.session)).hasStatus(HttpStatus.BAD_REQUEST)
    }

    private fun watch(
        who: Person,
        memo: String,
    ) = read("/api/v1/memos/$memo/watch", who.session)

    private fun submitted(
        initiator: Person,
        vararg approvers: Person,
    ): String {
        val id = aDraft(initiator)
        uploadDocument(id, initiator.session)
        route(id, initiator, *approvers)
        assertThat(post("/api/v1/memos/$id/submit", initiator.session)).hasStatusOk()
        return id
    }

    private fun approve(
        memo: String,
        approver: Person,
    ) {
        val task = get(memo, approver.session).json()["myTask"]["id"].asString()
        assertThat(post("/api/v1/tasks/$task/approve", approver.session)).hasStatusOk()
    }

    private fun note(text: String) = """{"type":"doc","content":[{"type":"paragraph","content":[{"type":"text","text":"$text"}]}]}"""

    /** As if the news were older than the quiet spell. */
    private fun quietSpellOver() = write("update memo_watch_event set created_at = created_at - interval '10 minutes'")

    /** A committed change as the application role (AppRoleJdbc rolls back). */
    private fun write(sql: String) {
        asUser(admin) { tenantDsl.current().execute(sql) }
    }

    private fun due(
        memo: String,
        assignee: Person,
        at: String,
    ) {
        val task = get(memo, assignee.session).json()["myTask"]["id"].asString()
        write("update workflow_task set due_at = $at where id = '$task'")
    }

    private fun kinds(user: Person): List<String> =
        AppRoleJdbc.inTransaction(tenant.id) { c ->
            c.prepareStatement("select kind from notification_item where user_id = ? order by created_at, id").use {
                it.setObject(1, user.id)
                it.executeQuery().use { rs -> generateSequence { if (rs.next()) rs.getString(1) else null }.toList() }
            }
        }

    private fun count(sql: String): Long =
        AppRoleJdbc.inTransaction(tenant.id) { c ->
            c.createStatement().use { st -> st.executeQuery(sql).use { rs -> if (rs.next()) rs.getLong(1) else 0L } }
        }
}
