package com.memo.memo

import com.memo.memo.internal.MemoTypeFields
import com.memo.memo.internal.MemoTypes
import com.memo.testsupport.AppRoleJdbc
import com.memo.testsupport.Audit
import com.memo.testsupport.MemoIntegrationTest
import com.memo.testsupport.Person
import com.memo.testsupport.Samples
import com.memo.testsupport.ageSessionFactors
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import org.springframework.test.web.servlet.assertj.MvcTestResult
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Approver actions and rounds over HTTP (test plan E01, T5): return, a new document version and resubmit start round 2
 * from the first approver with every action bound to the new version; approve, reject and withdraw end the memo; the
 * approval step-up window; idempotency bound to the actor; racing actions leave one winner and a 409.
 */
class MemoReviewIT : MemoIntegrationTest() {
    @Autowired lateinit var types: MemoTypes

    private lateinit var alice: Person
    private lateinit var bob: Person
    private lateinit var carol: Person

    /** A memo of [alice] with a document, routed to [bob] then [carol], submitted. */
    private fun submitted(type: String? = null): String {
        alice = person()
        bob = person()
        carol = person()
        val id = aDraft(alice)
        if (type != null) {
            val version = get(id, alice.session).json()["version"].asLong()
            put("/api/v1/memos/$id", alice.session, """{"subject":"Memo","typeId":"$type"}""", version)
        }
        uploadDocument(id, alice.session)
        route(id, alice, bob, carol)
        assertThat(post("/api/v1/memos/$id/submit", alice.session)).hasStatusOk()
        return id
    }

    private fun task(
        id: String,
        approver: Person,
    ): String = get(id, approver.session).json()["myTask"]["id"].asString()

    private fun act(
        action: String,
        task: String,
        approver: Person,
        remark: String? = null,
        key: String = UUID.randomUUID().toString(),
    ): MvcTestResult = post("/api/v1/tasks/$task/$action", approver.session, remark?.let { """{"remark":"$it"}""" } ?: "{}", key)

    @Test
    fun `return, a new version and resubmit start round 2 from the first approver, bound to the new version, until approved`() {
        val id = submitted()
        val bobView = get(id, bob.session).json()
        assertThat(bobView["allowedActions"].toString()).isEqualTo("""["APPROVE","RETURN","REJECT","RECUSE"]""")
        assertThat(bobView["holders"][0]["id"].asString()).isEqualTo(bob.id.toString())

        val round1 = task(id, bob)
        assertThat(act("return", round1, bob)).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
        val returned = act("return", round1, bob, "Annex B is missing")
        assertThat(returned).hasStatusOk()
        assertThat(returned.json()["status"].asString()).isEqualTo("RETURNED")
        val aliceView = get(id, alice.session).json()
        assertThat(aliceView["allowedActions"].toString()).isEqualTo("""["EDIT","UPLOAD_DOCUMENT","EDIT_ROUTE","RESUBMIT","WITHDRAW"]""")

        assertThat(uploadDocument(id, alice.session, Samples.docx("<doc>v2</doc>"))).hasStatus(HttpStatus.CREATED)
        val v2 = get(id, alice.session).json()["document"]
        assertThat(v2["number"].asInt()).isEqualTo(2)
        val resubmitted = post("/api/v1/memos/$id/resubmit", alice.session, """{"remark":"Annex added"}""")
        assertThat(resubmitted).hasStatusOk()
        assertThat(resubmitted.json()["round"].asInt()).isEqualTo(2)
        assertThat(resubmitted.json()["holders"][0]["id"].asString()).isEqualTo(bob.id.toString())

        assertThat(act("approve", task(id, bob), bob).json()["status"].asString()).isEqualTo("IN_REVIEW")
        val carolTask = task(id, carol)
        val approved = act("approve", carolTask, carol)
        assertThat(approved.json()["status"].asString()).isEqualTo("APPROVED")
        assertThat(get(id, alice.session).json()["allowedActions"]).isEmpty()

        val instance = get(id, alice.session).json().let { UUID.fromString(it["id"].asString()) }.let { memoInstance(it) }
        val round2Hashes = snapshots(instance, round = 2)
        assertThat(round2Hashes).hasSize(3).allSatisfy { assertThat(it).isEqualTo(v2["sha256"].asString()) }
        assertThat(snapshots(instance, round = 1)).doesNotContain(v2["sha256"].asString())
        Audit.assertAudited(tenant.id, "workflow.task.returned", round1, bob.id.toString())
        Audit.assertAudited(tenant.id, "workflow.instance.resubmitted", instance.toString(), alice.id.toString())
        Audit.assertAudited(tenant.id, "workflow.task.approved", carolTask, carol.id.toString())
    }

    @Test
    fun `approving needs a factor within the approval window when the type asks for it`() {
        val id = submitted()
        ageSessionFactors(bob.session, issuedAt = Instant.now().minus(Duration.ofMinutes(40)).truncatedTo(ChronoUnit.SECONDS))
        val stale = act("approve", task(id, bob), bob)
        assertThat(stale).hasStatus(HttpStatus.UNAUTHORIZED)
        assertThat(stale.response.contentAsString).contains("step-up-required")
        // 20 minutes is beyond the ordinary step-up window (5) but within the approval window (30).
        ageSessionFactors(bob.session, issuedAt = Instant.now().minus(Duration.ofMinutes(20)).truncatedTo(ChronoUnit.SECONDS))
        assertThat(act("approve", task(id, bob), bob)).hasStatusOk()

        val relaxed = asUser(admin) { types.create(MemoTypeFields("Routine", null, stepUpOnApprove = false, active = true)) }
        val routine = submitted(relaxed.id.toString())
        ageSessionFactors(bob.session)
        assertThat(act("approve", task(routine, bob), bob)).hasStatusOk()
        // Return and reject never need a step-up.
        ageSessionFactors(carol.session)
        assertThat(act("reject", task(routine, carol), carol, "Not now")).hasStatusOk()
    }

    @Test
    fun `reject and withdraw end the memo, and only the assignee may act on a task others can see`() {
        val id = submitted()
        val stranger = person()
        val bobTask = task(id, bob)
        assertThat(act("approve", bobTask, stranger)).hasStatus(HttpStatus.NOT_FOUND)
        assertThat(act("approve", bobTask, alice)).hasStatus(HttpStatus.FORBIDDEN)
        assertThat(act("approve", UUID.randomUUID().toString(), bob)).hasStatus(HttpStatus.NOT_FOUND)
        assertThat(act("reject", bobTask, bob)).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
        val rejected = act("reject", bobTask, bob, "Out of budget")
        assertThat(rejected.json()["status"].asString()).isEqualTo("REJECTED")
        assertThat(post("/api/v1/memos/$id/withdraw", alice.session)).hasStatus(HttpStatus.CONFLICT)
        assertThat(post("/api/v1/memos/$id/resubmit", alice.session)).hasStatus(HttpStatus.CONFLICT)

        val other = submitted()
        val open = task(other, bob)
        val withdrawn = post("/api/v1/memos/$other/withdraw", alice.session, """{"remark":"Superseded"}""")
        assertThat(withdrawn.json()["status"].asString()).isEqualTo("WITHDRAWN")
        assertThat(act("approve", open, bob)).hasStatus(HttpStatus.CONFLICT)
        assertThat(get(other, bob.session).json()["allowedActions"]).isEmpty()
        assertThat(uploadDocument(other, alice.session)).hasStatus(HttpStatus.FORBIDDEN)
    }

    @Test
    fun `one key is one action, bound to its actor, and racing actions leave one winner and a 409`() {
        val id = submitted()
        val bobTask = task(id, bob)
        val key = UUID.randomUUID().toString()
        val first = act("approve", bobTask, bob, key = key)
        val replay = act("approve", bobTask, bob, key = key)
        assertThat(replay).hasStatusOk()
        assertThat(replay.json()["version"].asLong()).isEqualTo(first.json()["version"].asLong())
        assertThat(actions(id)).isEqualTo(2)
        assertThat(act("approve", bobTask, alice, key = key)).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
        assertThat(act("approve", bobTask, bob)).hasStatus(HttpStatus.CONFLICT)

        // Carol approves on two devices at once.
        val carolTask = task(id, carol)
        assertThat(race({ act("approve", carolTask, carol) }, { act("approve", carolTask, carol) })).containsExactlyInAnyOrder(200, 409)

        // Return, then resubmit and withdraw at once, serialised on the memo row: if resubmit wins, the memo in review can
        // still be withdrawn; if withdraw wins, resubmit gets 409. Either way it ends withdrawn, memo and workflow alike.
        val next = submitted()
        act("return", task(next, bob), bob, "Again")
        val statuses =
            race(
                { post("/api/v1/memos/$next/resubmit", alice.session) },
                { post("/api/v1/memos/$next/withdraw", alice.session) },
            )
        assertThat(statuses).isSubsetOf(200, 409).contains(200)
        val status = get(next, alice.session).json()["status"].asString()
        assertThat(status).isEqualTo("WITHDRAWN")
        assertThat(instanceStatus(next)).isEqualTo("WITHDRAWN")
    }

    private fun race(vararg calls: () -> MvcTestResult): List<Int> {
        val pool = Executors.newFixedThreadPool(calls.size)
        try {
            return pool
                .invokeAll(calls.map { call -> Callable { call().response.status } }, 1, TimeUnit.MINUTES)
                .map { it.get() }
        } finally {
            pool.shutdownNow()
        }
    }

    private fun memoInstance(memo: UUID): UUID =
        AppRoleJdbc.inTransaction(tenant.id) { c ->
            c.prepareStatement("select workflow_instance_id from memo where id = ?").use {
                it.setObject(1, memo)
                it.executeQuery().use { rs -> rs.next().let { rs.getObject(1, UUID::class.java) } }
            }
        }

    /** The document hash bound to each action of [round]. */
    private fun snapshots(
        instance: UUID,
        round: Int,
    ): List<String> =
        AppRoleJdbc.inTransaction(tenant.id) { c ->
            c
                .prepareStatement(
                    "select subject_snapshot ->> 'sha256' from workflow_action where instance_id = ? and round = ? order by seq",
                ).use {
                    it.setObject(1, instance)
                    it.setInt(2, round)
                    it.executeQuery().use { rs -> generateSequence { if (rs.next()) rs.getString(1) else null }.toList() }
                }
        }

    private fun instanceStatus(memo: String): String =
        AppRoleJdbc.inTransaction(tenant.id) { c ->
            c.prepareStatement("select i.status from workflow_instance i join memo m on m.workflow_instance_id = i.id where m.id = ?").use {
                it.setObject(1, UUID.fromString(memo))
                it.executeQuery().use { rs -> rs.next().let { rs.getString(1) } }
            }
        }

    private fun actions(memo: String): Int =
        AppRoleJdbc.inTransaction(tenant.id) { c ->
            c
                .prepareStatement(
                    "select count(*) from workflow_action a join memo m on m.workflow_instance_id = a.instance_id where m.id = ?",
                ).use {
                    it.setObject(1, UUID.fromString(memo))
                    it.executeQuery().use { rs -> rs.next().let { rs.getInt(1) } }
                }
        }
}
