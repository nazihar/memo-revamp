package com.memo.workflow

import com.memo.common.context.Actor
import com.memo.common.context.ActorContext
import com.memo.common.context.ActorScope
import com.memo.common.tenant.TenantId
import com.memo.common.tenant.TenantTransactions
import com.memo.tenancy.TenantRegistry
import com.memo.testsupport.IntegrationTest
import com.memo.testsupport.TestWorkflowSubject
import com.memo.testsupport.WorkflowEventRecorder
import com.memo.testsupport.aTenant
import com.memo.workflow.internal.DefinitionLifecycle
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/**
 * Events and history (design E03 §1, §3; test plan T9): every completed step and the end of an instance are published
 * once, with the tenant; the history names each action's step and keeps what automatic actions were based on.
 */
class WorkflowEventsIT : IntegrationTest() {
    @Autowired lateinit var workflows: Workflows

    @Autowired lateinit var queries: WorkflowQueries

    @Autowired lateinit var lifecycle: DefinitionLifecycle

    @Autowired lateinit var transactions: TenantTransactions

    @Autowired lateinit var registry: TenantRegistry

    @Autowired lateinit var recorder: WorkflowEventRecorder

    @Autowired lateinit var json: JsonMapper

    private var tenant = TenantId(UUID(0, 0))
    private val maker = UUID.randomUUID()
    private val checker = UUID.randomUUID()
    private val initiator = UUID.randomUUID()
    private val reviewer = UUID.randomUUID()
    private val approver = UUID.randomUUID()

    @BeforeEach
    fun setUp() {
        tenant = registry.aTenant("events")
        val draft = act(maker) { lifecycle.create(KEY, "Events", null, false, json.readTree(definition())) }
        act(maker) { lifecycle.submit(KEY, 1, draft.version.revision) }
        act(checker) { lifecycle.approve(KEY, 1, null) }
    }

    @Test
    fun `each completed step and the end are published once with the tenant, and the history names the steps`() {
        val item = UUID.randomUUID()
        val started = act(initiator) { workflows.start(TestWorkflowSubject.TYPE, item, listOf(approver), definitionKey = KEY) }
        val routed = act(reviewer) { workflows.approve(started.openTaskId!!) }
        val done = act(approver) { workflows.approve(routed.openTaskId!!) }
        assertThat(done.status).isEqualTo(InstanceStatus.APPROVED)

        val events = recorder.of(item)
        val completed = events.filterIsInstance<WorkflowStepCompleted>()
        assertThat(completed.map { it.nodeId }).containsExactly("review", "route")
        assertThat(completed).allSatisfy {
            assertThat(it.tenantId).isEqualTo(tenant.value)
            assertThat(it.round).isEqualTo(1)
        }
        val ended = events.filterIsInstance<WorkflowInstanceEnded>()
        assertThat(ended).hasSize(1)
        assertThat(ended.single().status).isEqualTo(InstanceStatus.APPROVED)
        assertThat(ended.single().tenantId).isEqualTo(tenant.value)
        assertThat(events.last()).isInstanceOf(WorkflowInstanceEnded::class.java)
        assertThat(events.filterIsInstance<WorkflowNotifyRequested>().map { it.recipientId }).containsExactly(initiator)

        val history = act(initiator) { queries.history(started.instanceId) }
        assertThat(history.map { it.kind to it.stepName }).containsExactly(
            ActionKind.SUBMIT to null,
            ActionKind.RECOMMEND to "Department review",
            ActionKind.APPROVE to "Approvers",
            ActionKind.NOTIFY to "Tell the initiator",
        )
        assertThat(history.last().details).containsEntry("message", "approvedByBoard")
    }

    @Test
    fun `a cancelled instance ends once, and no step completes`() {
        val item = UUID.randomUUID()
        val started = act(initiator) { workflows.start(TestWorkflowSubject.TYPE, item, listOf(approver), definitionKey = KEY) }
        act(UUID.randomUUID()) { workflows.cancel(started.instanceId, "duplicate") }
        val events = recorder.of(item)
        assertThat(events.filterIsInstance<WorkflowStepCompleted>()).isEmpty()
        assertThat(events.filterIsInstance<WorkflowInstanceEnded>().map { it.status }).containsExactly(InstanceStatus.CANCELLED)
    }

    private fun definition() =
        """{"schemaVersion":2,"start":"review","steps":[
          {"id":"review","type":"review","name":"Department review","assignee":{"kind":"person","userId":"$reviewer"},"next":"route"},
          {"id":"route","type":"route","name":"Approvers","next":"tell"},
          {"id":"tell","type":"notify","name":"Tell the initiator","recipients":[{"kind":"initiator"}],"message":"approvedByBoard","next":"end"}]}"""

    private fun <T> act(
        user: UUID,
        block: () -> T,
    ): T = ActorContext.call(ActorScope(Actor.User(user), null)) { transactions.inTenant(tenant, block) }

    private companion object {
        const val KEY = "events"
    }
}
