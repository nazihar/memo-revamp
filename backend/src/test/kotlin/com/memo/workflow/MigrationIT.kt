package com.memo.workflow

import com.memo.common.context.Actor
import com.memo.common.context.ActorContext
import com.memo.common.context.ActorScope
import com.memo.common.tenant.TenantDsl
import com.memo.identity.MfaSeeding
import com.memo.jooq.tables.references.WORKFLOW_ACTION
import com.memo.testsupport.Audit
import com.memo.testsupport.MemoIntegrationTest
import com.memo.testsupport.TestWorkflowSubject
import com.memo.testsupport.adminSession
import com.memo.testsupport.ageSessionFactors
import com.memo.testsupport.sendJson
import com.memo.workflow.internal.DefinitionLifecycle
import jakarta.servlet.http.Cookie
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import tools.jackson.databind.JsonNode
import java.util.UUID

/**
 * In-flight migration (design E03 §4.7, test plan T8): the preview says which running instances can move to the
 * published version and why the others cannot; the migration moves only those, each with a `MIGRATE` history row and
 * an audit entry, and they then follow the new version's path.
 */
class MigrationIT : MemoIntegrationTest() {
    @Autowired lateinit var mfaSeeding: MfaSeeding

    @Autowired lateinit var workflows: Workflows

    @Autowired lateinit var lifecycle: DefinitionLifecycle

    @Autowired lateinit var tenantDsl: TenantDsl

    private lateinit var approver: Cookie
    private val maker = UUID.randomUUID()
    private val checker = UUID.randomUUID()
    private val initiator = UUID.randomUUID()
    private val reviewer = UUID.randomUUID()
    private val inspector = UUID.randomUUID()
    private val boss = UUID.randomUUID()

    @BeforeEach
    fun admin() {
        approver = mvc.adminSession(tenant, accounts, transactions, mfaSeeding)
    }

    @Test
    fun `instances at steps the new version keeps move to it, the others stay with the reason`() {
        publish(1, v1())
        val atReview = start()
        val atCheck = start().let { act(reviewer) { workflows.approve(it.openTaskId!!) } }
        val atBoss =
            start()
                .let {
                    act(reviewer) { workflows.approve(it.openTaskId!!) }
                }.let { act(inspector) { workflows.approve(it.openTaskId!!) } }
        val returned = start().also { act(reviewer) { workflows.returnToInitiator(it.openTaskId!!, "fix the totals") } }
        assertThat(atBoss.currentAssignees).containsExactly(boss)
        publish(2, v2())

        val preview = send("/preview", approver, "{}")
        assertThat(preview).hasStatusOk()
        assertThat(preview.json()["toVersion"].asInt()).isEqualTo(2)
        val planned = items(preview.json())
        assertThat(planned.keys).containsExactlyInAnyOrder(atReview.instanceId, atCheck.instanceId, atBoss.instanceId, returned.instanceId)
        assertThat(planned.getValue(atReview.instanceId)["migratable"].asBoolean()).isTrue()
        assertThat(planned.getValue(returned.instanceId)["migratable"].asBoolean()).isTrue()
        assertThat(planned.getValue(atCheck.instanceId)["reason"].asString()).isEqualTo("STEP_CHANGED")
        assertThat(planned.getValue(atBoss.instanceId)["reason"].asString()).isEqualTo("STEP_MISSING")
        assertThat(planned.getValue(atBoss.instanceId)["fromVersion"].asInt()).isEqualTo(1)

        val all = listOf(atReview, atCheck, atBoss, returned).map { it.instanceId }
        val body = """{"instanceIds":[${all.joinToString(",") { "\"$it\"" }}]}"""
        ageSessionFactors(approver)
        assertThat(send("", approver, body)).hasStatus(HttpStatus.UNAUTHORIZED)
        approver = mvc.adminSession(tenant, accounts, transactions, mfaSeeding)
        val key = UUID.randomUUID().toString()
        val migrated = items(send("", approver, body, key).json())
        assertThat(
            migrated.filterValues { it["migrated"].asBoolean() }.keys,
        ).containsExactlyInAnyOrder(atReview.instanceId, returned.instanceId)
        assertThat(migrated.getValue(atBoss.instanceId)["reason"].asString()).isEqualTo("STEP_MISSING")
        // A replayed key answers the same and moves nothing twice.
        assertThat(items(send("", approver, body, key).json()).filterValues { it["migrated"].asBoolean() }.keys).hasSize(2)
        assertThat(migrations(atReview.instanceId)).containsExactly(mapOf("from" to 1, "to" to 2))
        assertThat(migrations(atBoss.instanceId)).isEmpty()
        Audit.assertAudited(tenant.id, "workflow.instance.migrated", atReview.instanceId.toString())

        // The migrated instance follows version 2: the check is now an approval, and no boss step follows it.
        val atCheckNow = act(reviewer) { workflows.approve(atReview.openTaskId!!) }
        assertThat(atCheckNow.currentAssignees).containsExactly(inspector)
        assertThat(act(inspector) { workflows.approve(atCheckNow.openTaskId!!) }.status).isEqualTo(InstanceStatus.APPROVED)
        // The one left behind still follows version 1.
        assertThat(act(boss) { workflows.approve(atBoss.openTaskId!!) }.status).isEqualTo(InstanceStatus.APPROVED)
    }

    @Test
    fun `migrating needs a published version, and lists of at most a hundred instances`() {
        val unknown = send("/preview", approver, "{}", path = "nothing")
        assertThat(unknown).hasStatus(HttpStatus.NOT_FOUND)
        val many = (1..101).joinToString(",") { "\"${UUID.randomUUID()}\"" }
        publish(1, v1())
        assertThat(send("", approver, """{"instanceIds":[$many]}""")).hasStatus(HttpStatus.BAD_REQUEST)
    }

    private fun start(): WorkflowView =
        act(initiator) { workflows.start(TestWorkflowSubject.TYPE, UUID.randomUUID(), emptyList(), definitionKey = KEY) }

    private fun publish(
        number: Int,
        body: String,
    ) {
        val draft =
            act(maker) {
                if (number == 1) {
                    lifecycle.create(KEY, "Purchase", null, false, json.readTree(body))
                } else {
                    lifecycle.newDraft(KEY, json.readTree(body))
                }
            }
        act(maker) { lifecycle.submit(KEY, number, draft.version.revision) }
        act(checker) { lifecycle.approve(KEY, number, null) }
    }

    private fun send(
        suffix: String,
        session: Cookie,
        body: String,
        key: String = UUID.randomUUID().toString(),
        path: String = KEY,
    ) = mvc.sendJson(
        HttpMethod.POST,
        tenant,
        "/api/v1/admin/workflow-definitions/$path/migrations$suffix",
        session,
        body,
        mapOf("Idempotency-Key" to key),
    )

    private fun items(response: JsonNode): Map<UUID, JsonNode> {
        val items = response["items"]
        return (0 until items.size()).associate { UUID.fromString(items[it]["instanceId"].asString()) to items[it] }
    }

    private fun migrations(instanceId: UUID): List<Map<String, Any?>> =
        act(initiator) {
            tenantDsl
                .current()
                .select(WORKFLOW_ACTION.DETAILS)
                .from(WORKFLOW_ACTION)
                .where(WORKFLOW_ACTION.INSTANCE_ID.eq(instanceId), WORKFLOW_ACTION.KIND.eq(ActionKind.MIGRATE.name))
                .fetch { row ->
                    val details = json.readTree(row.value1()!!.data())
                    mapOf("from" to details["from"].asInt(), "to" to details["to"].asInt())
                }
        }

    private fun v1() =
        """{"schemaVersion":2,"start":"review","steps":[
          {"id":"review","type":"review","assignee":{"kind":"person","userId":"$reviewer"},"next":"check"},
          {"id":"check","type":"review","assignee":{"kind":"person","userId":"$inspector"},"next":"boss"},
          {"id":"boss","type":"approve","assignee":{"kind":"person","userId":"$boss"},"next":"end"}]}"""

    private fun v2() =
        """{"schemaVersion":2,"start":"review","steps":[
          {"id":"review","type":"review","assignee":{"kind":"person","userId":"$reviewer"},"next":"check"},
          {"id":"check","type":"approve","assignee":{"kind":"person","userId":"$inspector"},"next":"end"}]}"""

    private fun <T> act(
        user: UUID,
        block: () -> T,
    ): T = ActorContext.call(ActorScope(Actor.User(user), null)) { transactions.inTenant(tenant.id, block) }

    private companion object {
        const val KEY = "purchase"
    }
}
