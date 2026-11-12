package com.memo.workflow

import com.memo.common.concurrency.ConcurrentModificationException
import com.memo.common.context.Actor
import com.memo.common.context.ActorContext
import com.memo.common.context.ActorScope
import com.memo.common.tenant.TenantDsl
import com.memo.common.tenant.TenantId
import com.memo.common.tenant.TenantTransactions
import com.memo.jooq.tables.references.WORKFLOW_TASK
import com.memo.org.OrgSeeding
import com.memo.tenancy.TenantRegistry
import com.memo.testsupport.Audit
import com.memo.testsupport.IntegrationTest
import com.memo.testsupport.TestWorkflowSubject
import com.memo.testsupport.aTenant
import com.memo.workflow.internal.DefinitionInvalidException
import com.memo.workflow.internal.DefinitionLifecycle
import com.memo.workflow.internal.QueueMember
import com.memo.workflow.internal.Queues
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import tools.jackson.databind.json.JsonMapper
import java.time.LocalDate
import java.util.UUID

/**
 * Queues, claim and release (design E04 §4.2, test plan T3): a queue of a person and a position offers the step to both;
 * a claim keeps the other from deciding until it is released by the claimer or an administrator; the first decision
 * completes the step. Queue changes are versioned and audited; unknown queues are refused in definitions.
 */
class QueueClaimIT : IntegrationTest() {
    @Autowired lateinit var workflows: Workflows

    @Autowired lateinit var handOns: WorkflowHandOns

    @Autowired lateinit var queues: Queues

    @Autowired lateinit var lifecycle: DefinitionLifecycle

    @Autowired lateinit var seeding: OrgSeeding

    @Autowired lateinit var transactions: TenantTransactions

    @Autowired lateinit var registry: TenantRegistry

    @Autowired lateinit var tenantDsl: TenantDsl

    @Autowired lateinit var json: JsonMapper

    private var tenant = TenantId(UUID(0, 0))
    private val start = LocalDate.of(2026, 1, 1)
    private val entity = UUID.randomUUID()
    private val unit = UUID.randomUUID()
    private val desk = UUID.randomUUID()
    private val alice = UUID.randomUUID()
    private val bob = UUID.randomUUID()
    private val initiator = UUID.randomUUID()
    private val admin = UUID.randomUUID()

    @BeforeEach
    fun queue() {
        tenant = registry.aTenant("queues")
        act(admin) {
            seeding.legalEntity(entity, "Bank", start)
            seeding.unit(unit, entity, null, "SEC", "Secretariat", start)
            seeding.position(desk, unit, "Board desk", head = false, start)
            seeding.assignment(desk, bob, start)
            queues.create("secretariat", "Board secretariat", listOf(QueueMember(userId = alice), QueueMember(positionId = desk)))
        }
        val body =
            """{"schemaVersion":2,"start":"desk","steps":[
              {"id":"desk","type":"approve","name":"Secretariat","assignee":{"kind":"queue","queue":"secretariat"},"next":"end"}]}"""
        val draft = act(admin) { lifecycle.create("board", "Board", null, false, json.readTree(body)) }
        act(admin) { lifecycle.submit("board", 1, draft.version.revision) }
        act(UUID.randomUUID()) { lifecycle.approve("board", 1, null) }
    }

    @Test
    fun `a claim keeps the other candidate from deciding until it is released, and the first decision completes the step`() {
        val started = act(initiator) { workflows.start(TestWorkflowSubject.TYPE, UUID.randomUUID(), emptyList(), definitionKey = "board") }
        assertThat(started.currentAssignees).containsExactlyInAnyOrder(alice, bob)
        val alicesTask = taskOf(started.instanceId, alice)
        val bobsTask = taskOf(started.instanceId, bob)

        val claimed = act(alice) { handOns.claim(alicesTask) }
        assertThat(claimed.currentAssignees).containsExactly(alice)
        refused("CLAIMED_BY_OTHER") { act(bob) { workflows.approve(bobsTask) } }
        refused("CLAIMED_BY_OTHER") { act(bob) { handOns.claim(bobsTask) } }
        refused("ALREADY_CLAIMED") { act(alice) { handOns.claim(alicesTask) } }
        Audit.assertAudited(tenant, "workflow.task.claimed", alicesTask.toString(), alice.toString())

        val released = act(alice) { handOns.release(alicesTask) }
        assertThat(released.currentAssignees).containsExactlyInAnyOrder(alice, bob)
        refused("NOT_CLAIMED") { act(alice) { handOns.release(alicesTask) } }

        act(bob) { handOns.claim(bobsTask) }
        val byAdmin = act(admin) { handOns.release(bobsTask, byAdministrator = true) }
        assertThat(byAdmin.currentAssignees).containsExactlyInAnyOrder(alice, bob)
        Audit.assertAudited(tenant, "workflow.task.released", bobsTask.toString(), admin.toString())

        val done = act(bob) { workflows.approve(bobsTask) }
        assertThat(done.status).isEqualTo(InstanceStatus.APPROVED)
        refused("WRONG_STATE") { act(alice) { workflows.approve(alicesTask) } }
    }

    @Test
    fun `a step for one person cannot be claimed`() {
        act(admin) { queues.update("secretariat", 0, "Board secretariat", listOf(QueueMember(userId = alice))) }
        val started = act(initiator) { workflows.start(TestWorkflowSubject.TYPE, UUID.randomUUID(), emptyList(), definitionKey = "board") }
        assertThat(started.currentAssignees).containsExactly(alice)
        refused("NOT_SHARED") { act(alice) { handOns.claim(started.openTaskId!!) } }
    }

    @Test
    fun `queues are versioned and audited, and definitions may only name queues that exist`() {
        val updated = act(admin) { queues.update("secretariat", 0, "Secretariat", listOf(QueueMember(userId = bob))) }
        assertThat(updated.version).isEqualTo(1)
        assertThat(act(admin) { queues.people("secretariat") }).containsExactly(bob)
        assertThatThrownBy { act(admin) { queues.update("secretariat", 0, "Stale", emptyList()) } }
            .isInstanceOf(ConcurrentModificationException::class.java)
        Audit.assertAudited(tenant, "workflow.queue.created", updated.id.toString(), admin.toString())
        Audit.assertAudited(tenant, "workflow.queue.updated", updated.id.toString(), admin.toString())

        val body =
            """{"schemaVersion":2,"start":"a","steps":[{"id":"a","type":"approve","assignee":{"kind":"queue","queue":"nowhere"},"next":"end"}]}"""
        val draft = act(admin) { lifecycle.create("nowhere", "Nowhere", null, false, json.readTree(body)) }
        assertThatThrownBy { act(admin) { lifecycle.submit("nowhere", 1, draft.version.revision) } }
            .isInstanceOfSatisfying(DefinitionInvalidException::class.java) {
                assertThat(
                    it.problems.map { p ->
                        p.code
                    },
                ).containsExactly("UNKNOWN_QUEUE")
            }
    }

    private fun taskOf(
        instanceId: UUID,
        person: UUID,
    ): UUID =
        act(person) {
            tenantDsl
                .current()
                .select(WORKFLOW_TASK.ID)
                .from(WORKFLOW_TASK)
                .where(WORKFLOW_TASK.INSTANCE_ID.eq(instanceId), WORKFLOW_TASK.ASSIGNEE_ID.eq(person), WORKFLOW_TASK.STATE.eq("OPEN"))
                .fetchSingle(WORKFLOW_TASK.ID)!!
        }

    private fun refused(
        reason: String,
        block: () -> Any,
    ) {
        assertThatThrownBy { block() }.isInstanceOfSatisfying(WorkflowRefusedException::class.java) {
            assertThat(it.reason).isEqualTo(reason)
        }
    }

    private fun <T> act(
        user: UUID,
        block: () -> T,
    ): T = ActorContext.call(ActorScope(Actor.User(user), null)) { transactions.inTenant(tenant, block) }
}
