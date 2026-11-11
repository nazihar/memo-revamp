package com.memo.memo

import com.memo.common.crypto.DataKeys
import com.memo.identity.MfaSeeding
import com.memo.testsupport.AppRoleJdbc
import com.memo.testsupport.MailOutbox
import com.memo.testsupport.MemoIntegrationTest
import com.memo.testsupport.Person
import com.memo.testsupport.adminSession
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.util.UUID

/**
 * Workflow notifications (test plan E01, T8; design §12): each event reaches the right people in-app and by email, and
 * no email carries the memo's subject or a remark, only its reference and a link (`WorkflowMailContentTest` in the plan).
 */
class MemoNotificationIT : MemoIntegrationTest() {
    @Autowired lateinit var keys: DataKeys

    @Autowired lateinit var mfaSeeding: MfaSeeding

    private val outbox by lazy { MailOutbox(keys, json) }

    private fun kinds(user: Person): List<String> =
        AppRoleJdbc.inTransaction(tenant.id) { c ->
            c.prepareStatement("select kind from notification_item where user_id = ? order by created_at, id").use {
                it.setObject(1, user.id)
                it.executeQuery().use { rs -> generateSequence { if (rs.next()) rs.getString(1) else null }.toList() }
            }
        }

    private fun task(
        id: String,
        approver: Person,
    ) = get(id, approver.session).json()["myTask"]["id"].asString()

    @Test
    fun `assignment, return, approval and withdrawal notify the right people with the reference only`() {
        val alice = person()
        val bob = person()
        val carol = person()
        val id = aDraft(alice)
        val subject = "Confidential acquisition of Example Ltd"
        val type = generalType(alice.session)
        put("/api/v1/memos/$id", alice.session, """{"subject":"$subject","typeId":"$type"}""", 0)
        uploadDocument(id, alice.session)
        route(id, alice, bob, carol)
        post("/api/v1/memos/$id/submit", alice.session)
        assertThat(kinds(bob)).containsExactly("TASK_ASSIGNED")

        post("/api/v1/tasks/${task(id, bob)}/return", bob.session, """{"remark":"Price is wrong"}""")
        assertThat(kinds(alice)).containsExactly("MEMO_RETURNED")
        post("/api/v1/memos/$id/resubmit", alice.session)
        post("/api/v1/tasks/${task(id, bob)}/approve", bob.session)
        assertThat(kinds(bob)).containsExactly("TASK_ASSIGNED", "TASK_ASSIGNED")
        assertThat(kinds(carol)).containsExactly("TASK_ASSIGNED")
        post("/api/v1/memos/$id/withdraw", alice.session)
        assertThat(kinds(carol)).containsExactly("TASK_ASSIGNED", "MEMO_WITHDRAWN")

        val reference = get(id, alice.session).json()["reference"].asString()
        val mails = outbox.all(tenant).filter { it.template.startsWith("TASK_") || it.template.startsWith("MEMO_") }
        assertThat(mails.map { it.template }).contains("TASK_ASSIGNED", "MEMO_RETURNED", "MEMO_WITHDRAWN")
        assertThat(mails).allSatisfy { mail ->
            assertThat(mail.params["reference"]).isEqualTo(reference)
            assertThat(mail.params["link"]).endsWith("/memos/$id")
            assertThat(mail.params.values.joinToString()).doesNotContain(subject, "Price is wrong")
        }
    }

    @Test
    fun `approval tells the initiator, a blocked task tells the initiator and the reassigners, reassignment both approvers`() {
        val alice = person(clearance = 1)
        val bob = person(clearance = 1)
        val carol = person(clearance = 1)
        val erin = person(clearance = 1)
        val id = aDraft(alice, rank = 1)
        uploadDocument(id, alice.session)
        route(id, alice, bob, carol)
        post("/api/v1/memos/$id/submit", alice.session)
        clear(carol.id, 0)
        post("/api/v1/tasks/${task(id, bob)}/approve", bob.session)
        assertThat(kinds(carol)).isEmpty()
        assertThat(kinds(alice)).containsExactly("TASK_BLOCKED")
        assertThat(blockedNotices(admin.id.value)).isEqualTo(1)

        val session = mvc.adminSession(tenant, accounts, transactions, mfaSeeding)
        val blocked = read("/api/v1/admin/workflow/tasks?filter=blocked", session).json()["items"][0]["taskId"].asString()
        post("/api/v1/admin/workflow/tasks/$blocked/reassign", session, """{"newAssigneeId":"${erin.id}","reason":"Carol moved"}""")
        assertThat(kinds(carol)).containsExactly("TASK_REASSIGNED")
        assertThat(kinds(erin)).containsExactly("TASK_REASSIGNED")

        post("/api/v1/tasks/${task(id, erin)}/approve", erin.session)
        assertThat(kinds(alice)).containsExactly("TASK_BLOCKED", "MEMO_APPROVED")
    }

    private fun blockedNotices(user: UUID): Int =
        AppRoleJdbc.inTransaction(tenant.id) { c ->
            c.prepareStatement("select count(*) from notification_item where user_id = ? and kind = 'TASK_BLOCKED'").use {
                it.setObject(1, user)
                it.executeQuery().use { rs -> rs.next().let { rs.getInt(1) } }
            }
        }
}
