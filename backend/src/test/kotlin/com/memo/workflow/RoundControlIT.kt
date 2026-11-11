package com.memo.workflow

import com.memo.common.context.Actor
import com.memo.common.context.ActorContext
import com.memo.common.context.ActorScope
import com.memo.common.tenant.TenantDsl
import com.memo.common.tenant.TenantId
import com.memo.common.tenant.TenantTransactions
import com.memo.jooq.tables.references.WORKFLOW_ACTION
import com.memo.jooq.tables.references.WORKFLOW_DEFINITION
import com.memo.jooq.tables.references.WORKFLOW_DEFINITION_VERSION
import com.memo.tenancy.TenantRegistry
import com.memo.testsupport.Audit
import com.memo.testsupport.IntegrationTest
import com.memo.testsupport.TestWorkflowSubject
import com.memo.testsupport.aTenant
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.jooq.JSONB
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Round control (design E03 §4.4, §4.6; test plan T6): the initiator's recall, an administrator's cancellation, and the
 * `resume` and `skip-unchanged` resubmission policies.
 */
class RoundControlIT : IntegrationTest() {
    @Autowired lateinit var workflows: Workflows

    @Autowired lateinit var transactions: TenantTransactions

    @Autowired lateinit var registry: TenantRegistry

    @Autowired lateinit var tenantDsl: TenantDsl

    @Autowired lateinit var subject: TestWorkflowSubject

    private var tenant = TenantId(UUID(0, 0))
    private val initiator = UUID.randomUUID()
    private val first = UUID.randomUUID()
    private val second = UUID.randomUUID()
    private val third = UUID.randomUUID()
    private val admin = UUID.randomUUID()

    @BeforeEach
    fun setUp() {
        tenant = registry.aTenant("rounds")
        publish("resume", "resume")
        publish("carry", "skip-unchanged")
    }

    @AfterEach
    fun reset() {
        subject.documentVersion.clear()
    }

    private fun route() = listOf(first, second, third)

    @Test
    fun `the initiator recalls before anyone acted, and not after`() {
        val started = act(initiator) { workflows.start(TestWorkflowSubject.TYPE, UUID.randomUUID(), route()) }
        assertThat(started.recallable).isTrue()
        refused("NOT_INITIATOR") { act(first) { workflows.recall(started.instanceId) } }
        val recalled = act(initiator) { workflows.recall(started.instanceId, "forgot the annex") }
        assertThat(recalled.status).isEqualTo(InstanceStatus.RETURNED)
        assertThat(recalled.currentAssignees).isEmpty()
        Audit.assertAudited(tenant, "workflow.instance.recalled", started.instanceId.toString(), initiator.toString())
        assertThat(act(initiator) { workflows.resubmit(started.instanceId, route()) }.round).isEqualTo(2)

        val other = act(initiator) { workflows.start(TestWorkflowSubject.TYPE, UUID.randomUUID(), route()) }
        val acted = act(first) { workflows.approve(other.openTaskId!!) }
        assertThat(acted.recallable).isFalse()
        refused("NOT_RECALLABLE") { act(initiator) { workflows.recall(other.instanceId) } }
    }

    @Test
    fun `an administrator cancels with a reason, and a cancelled workflow accepts nothing more`() {
        val started = act(initiator) { workflows.start(TestWorkflowSubject.TYPE, UUID.randomUUID(), route()) }
        refused("REMARK_REQUIRED") { act(admin) { workflows.cancel(started.instanceId, null) } }
        val cancelled = act(admin) { workflows.cancel(started.instanceId, "duplicate of M-12") }
        assertThat(cancelled.status).isEqualTo(InstanceStatus.CANCELLED)
        assertThat(cancelled.currentAssignees).isEmpty()
        refused("WRONG_STATE") { act(admin) { workflows.cancel(started.instanceId, "again") } }
        refused("WRONG_STATE") { act(first) { workflows.approve(started.openTaskId!!) } }
        Audit.assertAudited(tenant, "workflow.instance.cancelled", started.instanceId.toString(), admin.toString())
    }

    @Test
    fun `resume starts the next round at the approver who returned the item`() {
        val started = act(initiator) { workflows.start(TestWorkflowSubject.TYPE, UUID.randomUUID(), route(), definitionKey = "resume") }
        val atSecond = act(first) { workflows.approve(started.openTaskId!!) }
        act(second) { workflows.returnToInitiator(atSecond.openTaskId!!, "fix section 2") }
        val round2 = act(initiator) { workflows.resubmit(started.instanceId, route()) }
        assertThat(round2.round).isEqualTo(2)
        assertThat(round2.currentAssignees).containsExactly(second)
    }

    @Test
    fun `skip-unchanged carries approvals over when the document did not change, and asks again when it did`() {
        val item = UUID.randomUUID()
        val started = act(initiator) { workflows.start(TestWorkflowSubject.TYPE, item, route(), definitionKey = "carry") }
        val atSecond = act(first) { workflows.approve(started.openTaskId!!) }
        val atThird = act(second) { workflows.approve(atSecond.openTaskId!!) }
        act(third) { workflows.returnToInitiator(atThird.openTaskId!!, "one question") }
        val unchanged = act(initiator) { workflows.resubmit(started.instanceId, route()) }
        assertThat(unchanged.currentAssignees).containsExactly(third)
        val carried = actions(started.instanceId).filter { it.first == "CARRY" }.map { it.second }
        assertThat(carried).containsExactly(first, second)

        val other = UUID.randomUUID()
        val again = act(initiator) { workflows.start(TestWorkflowSubject.TYPE, other, route(), definitionKey = "carry") }
        val otherSecond = act(first) { workflows.approve(again.openTaskId!!) }
        act(second) { workflows.returnToInitiator(otherSecond.openTaskId!!, "please update") }
        subject.documentVersion[other] = 2
        val changed = act(initiator) { workflows.resubmit(again.instanceId, route()) }
        assertThat(changed.currentAssignees).containsExactly(first)
        assertThat(actions(again.instanceId).map { it.first }).doesNotContain("CARRY")
    }

    private fun refused(
        reason: String,
        block: () -> Any,
    ) {
        assertThatThrownBy { block() }.isInstanceOfSatisfying(WorkflowRefusedException::class.java) {
            assertThat(it.reason).isEqualTo(reason)
        }
    }

    private fun publish(
        key: String,
        policy: String,
    ) = act(initiator) {
        val dsl = tenantDsl.current()
        val now = OffsetDateTime.now()
        val id = UUID.randomUUID()
        dsl
            .insertInto(WORKFLOW_DEFINITION)
            .set(WORKFLOW_DEFINITION.ID, id)
            .set(WORKFLOW_DEFINITION.TENANT_ID, tenant.value)
            .set(WORKFLOW_DEFINITION.KEY, key)
            .set(WORKFLOW_DEFINITION.NAME, key)
            .set(WORKFLOW_DEFINITION.BUILT_IN, false)
            .set(WORKFLOW_DEFINITION.CREATED_AT, now)
            .execute()
        val body =
            JSONB.valueOf(
                """{"schemaVersion":2,"resubmit":"$policy","start":"route","steps":[{"id":"route","type":"route","next":"end"}]}""",
            )
        dsl
            .insertInto(WORKFLOW_DEFINITION_VERSION)
            .set(WORKFLOW_DEFINITION_VERSION.ID, UUID.randomUUID())
            .set(WORKFLOW_DEFINITION_VERSION.TENANT_ID, tenant.value)
            .set(WORKFLOW_DEFINITION_VERSION.DEFINITION_ID, id)
            .set(WORKFLOW_DEFINITION_VERSION.NUMBER, 1)
            .set(WORKFLOW_DEFINITION_VERSION.BODY, body)
            .set(WORKFLOW_DEFINITION_VERSION.COMPILED, body)
            .set(WORKFLOW_DEFINITION_VERSION.SCHEMA_VERSION, 2)
            .set(WORKFLOW_DEFINITION_VERSION.STATE, "PUBLISHED")
            .set(WORKFLOW_DEFINITION_VERSION.CREATED_AT, now)
            .execute()
    }

    private fun actions(instanceId: UUID): List<Pair<String, UUID>> =
        act(initiator) {
            tenantDsl
                .current()
                .select(WORKFLOW_ACTION.KIND, WORKFLOW_ACTION.ACTOR_ID)
                .from(WORKFLOW_ACTION)
                .where(WORKFLOW_ACTION.INSTANCE_ID.eq(instanceId))
                .orderBy(WORKFLOW_ACTION.SEQ)
                .fetch { it.value1()!! to it.value2()!! }
        }

    private fun <T> act(
        user: UUID,
        block: () -> T,
    ): T = ActorContext.call(ActorScope(Actor.User(user), null)) { transactions.inTenant(tenant, block) }
}
