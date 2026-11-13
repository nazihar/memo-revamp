package com.memo.memo

import com.memo.identity.UserLifecycle
import com.memo.testsupport.AppRoleJdbc
import com.memo.testsupport.Audit
import com.memo.testsupport.MemoIntegrationTest
import com.memo.testsupport.WorkflowEventRecorder
import com.memo.testsupport.aSetting
import com.memo.workflow.BlockedReason
import com.memo.workflow.WorkflowQueries
import com.memo.workflow.WorkflowTaskCreated
import com.memo.workflow.Workflows
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import java.util.UUID

/**
 * Route and submit (test plan E01, T4): the route's rules per entry, submit into round 1 with the document and route
 * checked again, the review lock on the memo and its document, approvers becoming participants when their task is
 * created, and a task created blocked for an approver who lost their clearance after submit.
 */
class MemoSubmitIT : MemoIntegrationTest() {
    @Autowired lateinit var workflows: Workflows

    @Autowired lateinit var queries: WorkflowQueries

    @Autowired lateinit var lifecycle: UserLifecycle

    @Autowired lateinit var recorder: WorkflowEventRecorder

    @Test
    fun `a route names other active people cleared for the memo, each once, within the tenant's limit`() {
        val alice = person(clearance = 1)
        val bob = person(clearance = 1)
        val uncleared = person()
        val leaver = person(clearance = 1)
        asUser(admin) { lifecycle.deactivate(leaver.account.id, accounts.find(leaver.account.id)!!.version, admin.id) }
        val id = aDraft(alice, rank = 1)

        val refused = route(id, alice, bob, alice, bob, uncleared, leaver)
        assertThat(refused).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
        assertThat(refused.errors()).containsExactly(
            "route[1]:self",
            "route[2]:duplicate",
            "route[3]:approver-not-cleared",
            "route[4]:approver-inactive",
        )
        val stranger = put("/api/v1/memos/$id/route", alice.session, routeBody(listOf(UUID.randomUUID())), 0)
        assertThat(stranger.errors()).containsExactly("route[0]:approver-inactive")

        transactions.aSetting(settings, tenant.id, "workflow.route.max", "2")
        val third = person(clearance = 1)
        val fourth = person(clearance = 1)
        assertThat(route(id, alice, bob, third, fourth).errors()).containsExactly("route:too-long")

        val saved = route(id, alice, bob, third)
        assertThat(saved).hasStatusOk()
        assertThat(saved.json()["route"][1]["approver"]["id"].asString()).isEqualTo(third.id.toString())
        assertThat(saved.json()["version"].asLong()).isEqualTo(1)
        assertThat(put("/api/v1/memos/$id/route", alice.session, routeBody(listOf(bob.id)), 0)).hasStatus(HttpStatus.PRECONDITION_FAILED)
        assertThat(put("/api/v1/memos/$id/route", bob.session, routeBody(listOf(third.id)), 1)).hasStatus(HttpStatus.NOT_FOUND)
        Audit.assertAudited(tenant.id, "memo.route.updated", id, alice.id.toString())
    }

    @Test
    fun `submit needs a document and a route, starts round 1, and locks the memo and its document`() {
        val alice = person()
        val bob = person()
        val carol = person()
        val id = aDraft(alice)

        val empty = post("/api/v1/memos/$id/submit", alice.session)
        assertThat(empty).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
        assertThat(empty.errors()).containsExactly("document:required", "route:empty")
        assertThat(uploadDocument(id, alice.session)).hasStatus(HttpStatus.CREATED)
        assertThat(route(id, alice, bob, carol)).hasStatusOk()
        assertThat(post("/api/v1/memos/$id/submit", bob.session)).hasStatus(HttpStatus.NOT_FOUND)

        val key = UUID.randomUUID().toString()
        val submitted = post("/api/v1/memos/$id/submit", alice.session, """{"remark":"Please review"}""", key)
        assertThat(submitted).hasStatusOk()
        val memo = submitted.json()
        assertThat(memo["status"].asString()).isEqualTo("IN_REVIEW")
        assertThat(memo["round"].asInt()).isEqualTo(1)
        assertThat(memo["allowedActions"].toString()).isEqualTo("""["WITHDRAW","RECALL"]""")
        assertThat(post("/api/v1/memos/$id/submit", alice.session, """{"remark":"Please review"}""", key).json()["status"].asString())
            .isEqualTo("IN_REVIEW")
        assertThat(post("/api/v1/memos/$id/submit", alice.session)).hasStatus(HttpStatus.CONFLICT)

        // The first approver takes part from now on; the second only when their step is reached.
        assertThat(get(id, bob.session)).hasStatusOk()
        assertThat(get(id, carol.session)).hasStatus(HttpStatus.NOT_FOUND)

        // Review lock (ADR-0036): no edits, no route changes, no new document versions until the memo comes back.
        val version = memo["version"].asLong()
        val type = generalType(alice.session)
        assertThat(
            put("/api/v1/memos/$id", alice.session, """{"subject":"Changed","typeId":"$type"}""", version),
        ).hasStatus(HttpStatus.CONFLICT)
        assertThat(put("/api/v1/memos/$id/route", alice.session, routeBody(listOf(carol.id)), version)).hasStatus(HttpStatus.CONFLICT)
        assertThat(uploadDocument(id, alice.session)).hasStatus(HttpStatus.FORBIDDEN)
        assertThat(post("/api/v1/memos/$id/discard", alice.session)).hasStatus(HttpStatus.CONFLICT)

        val instance = asUser(alice.account) { queries.viewOf("memo", UUID.fromString(id))!!.instanceId }
        Audit.assertAudited(tenant.id, "workflow.instance.started", instance.toString(), alice.id.toString())
        val snapshot =
            AppRoleJdbc.inTransaction(tenant.id) { c ->
                c.prepareStatement("select subject_snapshot::text from workflow_action where instance_id = ? and seq = 1").use {
                    it.setObject(1, instance)
                    it.executeQuery().use { rs -> rs.next().let { rs.getString(1) } }
                }
            }
        assertThat(snapshot).contains("documentVersionId", "sha256", memo["document"]["sha256"].asString())
    }

    @Test
    fun `an approver who lost their clearance after submit gets a blocked task when their step is reached`() {
        val alice = person(clearance = 1)
        val bob = person(clearance = 1)
        val carol = person(clearance = 1)
        val id = aDraft(alice, rank = 1)
        uploadDocument(id, alice.session)
        route(id, alice, bob, carol)
        assertThat(post("/api/v1/memos/$id/submit", alice.session)).hasStatusOk()

        clear(carol.id, 0)
        recorder.events.clear()
        asUser(bob.account) { workflows.approve(openTask(id)) }

        val created = recorder.events.filterIsInstance<WorkflowTaskCreated>().single()
        assertThat(created.assigneeId).isEqualTo(carol.id)
        assertThat(created.blocked).isEqualTo(BlockedReason.NO_CLEARANCE)
        // A participant now, but not cleared: the memo stays invisible to her.
        assertThat(get(id, carol.session)).hasStatus(HttpStatus.NOT_FOUND)
        assertThat(get(id, alice.session).json()["status"].asString()).isEqualTo("IN_REVIEW")
    }

    private fun openTask(memo: String): UUID =
        AppRoleJdbc.inTransaction(tenant.id) { c ->
            c
                .prepareStatement(
                    "select t.id from workflow_task t join workflow_instance i on i.id = t.instance_id " +
                        "where i.subject_id = ? and t.state = 'OPEN'",
                ).use {
                    it.setObject(1, UUID.fromString(memo))
                    it.executeQuery().use { rs -> rs.next().let { rs.getObject(1, UUID::class.java) } }
                }
        }
}
