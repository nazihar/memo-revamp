package com.memo.memo

import com.memo.identity.MfaSeeding
import com.memo.testsupport.Audit
import com.memo.testsupport.MemoIntegrationTest
import com.memo.testsupport.Person
import com.memo.testsupport.adminSession
import com.memo.testsupport.ageSessionFactors
import jakarta.servlet.http.Cookie
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import java.util.UUID

/**
 * Reassignment and blocked tasks (test plan E01, T6): an approver who lost their clearance gets a blocked task; holders
 * of `workflow.tasks.reassign` find it (the title only if they are cleared), reassign it with a reason after step-up to
 * someone eligible, and the new approver can act.
 */
class MemoReassignIT : MemoIntegrationTest() {
    @Autowired lateinit var mfaSeeding: MfaSeeding

    private fun tasks(
        session: Cookie,
        query: String = "",
    ) = read("/api/v1/admin/workflow/tasks$query", session)

    private fun reassign(
        task: String,
        session: Cookie,
        to: UUID,
        reason: String? = "Approver left the bank",
    ) = post(
        "/api/v1/admin/workflow/tasks/$task/reassign",
        session,
        """{"newAssigneeId":"$to"${reason?.let { ""","reason":"$it"""" } ?: ""}}""",
    )

    @Test
    fun `a blocked task is listed for administrators and reassigned with a reason to someone eligible`() {
        val alice = person(clearance = 1)
        val bob = person(clearance = 1)
        val carol = person(clearance = 1)
        val erin = person(clearance = 1)
        val uncleared = person()
        val id = aDraft(alice, rank = 1)
        uploadDocument(id, alice.session)
        route(id, alice, bob, carol)
        post("/api/v1/memos/$id/submit", alice.session)
        clear(carol.id, 0)
        assertThat(post("/api/v1/tasks/${myTask(id, bob)}/approve", bob.session)).hasStatusOk()

        val admin = mvc.adminSession(tenant, accounts, transactions, mfaSeeding)
        assertThat(tasks(bob.session)).hasStatus(HttpStatus.FORBIDDEN)
        val blocked = tasks(admin, "?filter=blocked").json()["items"]
        assertThat(blocked.size()).isEqualTo(1)
        val item = blocked[0]
        assertThat(item["assignee"]["id"].asString()).isEqualTo(carol.id.toString())
        assertThat(item["blocked"].asString()).isEqualTo("NO_CLEARANCE")
        // The administrator is not cleared for the memo: its title stays hidden.
        assertThat(item["subject"]["restricted"].asBoolean()).isTrue()
        assertThat(item["subject"].absent("title")).isTrue()
        assertThat(item["subject"]["reference"].asString()).startsWith("M-")
        assertThat(tasks(admin, "?filter=nonsense")).hasStatus(HttpStatus.BAD_REQUEST)
        val task = item["taskId"].asString()

        assertThat(reassign(task, admin, erin.id, reason = null).json()["reason"].asString()).isEqualTo("REMARK_REQUIRED")
        assertThat(reassign(task, admin, alice.id).json()["reason"].asString()).isEqualTo("INITIATOR_AS_APPROVER")
        assertThat(reassign(task, admin, bob.id).json()["reason"].asString()).isEqualTo("ASSIGNEE_IN_ROUND")
        assertThat(reassign(task, admin, uncleared.id).json()["reason"].asString()).isEqualTo("ASSIGNEE_NOT_ELIGIBLE")
        assertThat(reassign(UUID.randomUUID().toString(), admin, erin.id)).hasStatus(HttpStatus.NOT_FOUND)

        val reassigned = reassign(task, admin, erin.id)
        assertThat(reassigned).hasStatusOk()
        assertThat(reassigned.json()["currentAssignees"][0].asString()).isEqualTo(erin.id.toString())
        Audit.assertAudited(tenant.id, "workflow.task.reassigned", task)
        assertThat(tasks(admin, "?filter=blocked").json()["items"].size()).isZero()

        val erinView = get(id, erin.session).json()
        assertThat(erinView["allowedActions"].toString()).isEqualTo("""["APPROVE","RETURN","REJECT"]""")
        assertThat(post("/api/v1/tasks/${erinView["myTask"]["id"].asString()}/approve", erin.session).json()["status"].asString())
            .isEqualTo("APPROVED")
    }

    @Test
    fun `reassigning needs a fresh step-up, and a cleared administrator sees the title`() {
        val alice = person()
        val bob = person()
        val dave = person()
        val id = aDraft(alice)
        uploadDocument(id, alice.session)
        route(id, alice, bob)
        post("/api/v1/memos/$id/submit", alice.session)
        val admin = mvc.adminSession(tenant, accounts, transactions, mfaSeeding)

        val open = tasks(admin).json()["items"][0]
        assertThat(open["subject"]["restricted"].asBoolean()).isFalse()
        assertThat(open["subject"]["title"].asString()).isEqualTo("Memo")
        assertThat(open.absent("blocked")).isTrue()
        assertThat(tasks(admin, "?assignee=${dave.id}").json()["items"].size()).isZero()

        ageSessionFactors(admin)
        assertThat(reassign(open["taskId"].asString(), admin, dave.id)).hasStatus(HttpStatus.UNAUTHORIZED)
        assertThat(get(id, bob.session).json()["myTask"]["id"].asString()).isEqualTo(open["taskId"].asString())
    }

    private fun myTask(
        id: String,
        approver: Person,
    ): String = get(id, approver.session).json()["myTask"]["id"].asString()
}
