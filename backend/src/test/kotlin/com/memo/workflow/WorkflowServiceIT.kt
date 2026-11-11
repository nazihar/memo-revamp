package com.memo.workflow

import com.memo.common.context.Actor
import com.memo.common.context.ActorContext
import com.memo.common.context.ActorScope
import com.memo.common.tenant.TenantId
import com.memo.common.tenant.TenantTransactions
import com.memo.common.web.RefusedException
import com.memo.tenancy.TenantRegistry
import com.memo.testsupport.AppRoleJdbc
import com.memo.testsupport.AppRoleJdbc.long
import com.memo.testsupport.AppRoleJdbc.update
import com.memo.testsupport.Audit
import com.memo.testsupport.IntegrationTest
import com.memo.testsupport.TestWorkflowSubject
import com.memo.testsupport.WorkflowEventRecorder
import com.memo.testsupport.aTenant
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.sql.SQLException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The workflow service against PostgreSQL (test plan E01, T2): writes, guarded transitions, the append-only numbered
 * history, audit in the same transaction, events, the subject's veto, serialised concurrent actions, and tenant isolation.
 */
class WorkflowServiceIT : IntegrationTest() {
    @Autowired lateinit var workflows: Workflows

    @Autowired lateinit var transactions: TenantTransactions

    @Autowired lateinit var registry: TenantRegistry

    @Autowired lateinit var subject: TestWorkflowSubject

    @Autowired lateinit var recorder: WorkflowEventRecorder

    private var tenant = TenantId(UUID(0, 0))
    private val initiator = UUID.randomUUID()
    private val first = UUID.randomUUID()
    private val second = UUID.randomUUID()
    private val third = UUID.randomUUID()
    private val admin = UUID.randomUUID()

    @BeforeEach
    fun tenant() {
        tenant = registry.aTenant("wf")
    }

    @AfterEach
    fun resetSubject() {
        subject.ineligible.clear()
        subject.vetoed.clear()
        subject.onAction = {}
    }

    @Test
    fun `submit, return, resubmit and approve make rounds, a gapless history, audit entries and events`() {
        val item = UUID.randomUUID()
        val started = act(initiator) { workflows.start(TestWorkflowSubject.TYPE, item, listOf(first, second), "please review") }
        assertThat(started.status).isEqualTo(InstanceStatus.RUNNING)
        assertThat(started.currentAssignees).containsExactly(first)

        val afterFirst = act(first) { workflows.approve(started.openTaskId!!) }
        val returned = act(second) { workflows.returnToInitiator(afterFirst.openTaskId!!, "missing annex") }
        assertThat(returned.status).isEqualTo(InstanceStatus.RETURNED)

        subject.documentVersion[item] = 2
        val round2 = act(initiator) { workflows.resubmit(started.instanceId, listOf(first, second), "annex added") }
        assertThat(round2.round).isEqualTo(2)
        assertThat(round2.currentAssignees).containsExactly(first)
        val round2Second = act(first) { workflows.approve(round2.openTaskId!!) }
        assertThat(round2Second.status).isEqualTo(InstanceStatus.RUNNING)
        val approved = act(second) { workflows.approve(round2Second.openTaskId!!, "fine") }
        assertThat(approved.status).isEqualTo(InstanceStatus.APPROVED)
        assertThat(approved.currentAssignees).isEmpty()

        AppRoleJdbc.inTransaction(tenant) { c ->
            val instance = started.instanceId
            assertThat(c.long("select count(*) from workflow_action where instance_id = ?", instance)).isEqualTo(6)
            assertThat(c.long("select max(seq) from workflow_action where instance_id = ?", instance)).isEqualTo(6)
            assertThat(c.long("select count(distinct seq) from workflow_action where instance_id = ?", instance)).isEqualTo(6)
            assertThat(c.long("select count(*) from workflow_round where instance_id = ? and outcome = 'OPEN'", instance)).isZero()
            assertThat(c.long("select count(*) from workflow_task where instance_id = ? and state = 'OPEN'", instance)).isZero()
            assertThat(
                c.long(
                    "select count(*) from workflow_action where instance_id = ? and round = 2 " +
                        "and subject_snapshot->>'documentVersion' = '2'",
                    instance,
                ),
            ).describedAs("round 2 actions bind document version 2").isEqualTo(3)
            assertThat(
                c.long("select count(*) from workflow_instance where id = ? and status = 'APPROVED' and current_step_id is null", instance),
            ).isEqualTo(1)
        }

        Audit.assertAudited(tenant, "workflow.instance.started", started.instanceId.toString(), initiator.toString())
        Audit.assertAudited(tenant, "workflow.task.approved", started.openTaskId.toString(), first.toString())
        Audit.assertAudited(tenant, "workflow.task.returned", afterFirst.openTaskId.toString(), second.toString())
        Audit.assertAudited(tenant, "workflow.instance.resubmitted", started.instanceId.toString(), initiator.toString())
        Audit.assertAudited(tenant, "workflow.instance.ended", started.instanceId.toString())
        Audit.assertChainValid(tenant)

        val kinds = recorder.of(item).filterIsInstance<WorkflowActionTaken>().map { it.kind }
        assertThat(kinds).containsExactly(
            ActionKind.SUBMIT,
            ActionKind.APPROVE,
            ActionKind.RETURN,
            ActionKind.RESUBMIT,
            ActionKind.APPROVE,
            ActionKind.APPROVE,
        )
        assertThat(recorder.of(item).filterIsInstance<WorkflowTaskCreated>().map { it.assigneeId })
            .containsExactly(first, second, first, second)
    }

    @Test
    fun `reject and withdraw end the instance and are audited`() {
        val rejectedItem = UUID.randomUUID()
        val started = act(initiator) { workflows.start(TestWorkflowSubject.TYPE, rejectedItem, listOf(first)) }
        val rejected = act(first) { workflows.reject(started.openTaskId!!, "out of policy") }
        assertThat(rejected.status).isEqualTo(InstanceStatus.REJECTED)
        Audit.assertAudited(tenant, "workflow.task.rejected", started.openTaskId.toString(), first.toString())

        val withdrawnItem = UUID.randomUUID()
        val other = act(initiator) { workflows.start(TestWorkflowSubject.TYPE, withdrawnItem, listOf(first, second)) }
        val withdrawn = act(initiator) { workflows.withdraw(other.instanceId, "no longer needed") }
        assertThat(withdrawn.status).isEqualTo(InstanceStatus.WITHDRAWN)
        Audit.assertAudited(tenant, "workflow.instance.withdrawn", other.instanceId.toString(), initiator.toString())
        assertThat(
            recorder
                .of(withdrawnItem)
                .filterIsInstance<WorkflowTaskClosed>()
                .single()
                .closure,
        ).isEqualTo(TaskClosure.CANCELLED)
    }

    @Test
    fun `a task for an ineligible approver is created blocked and moves on only by reassignment`() {
        subject.ineligible[second] = BlockedReason.INACTIVE
        val started = act(initiator) { workflows.start(TestWorkflowSubject.TYPE, UUID.randomUUID(), listOf(first, second)) }
        val blocked = act(first) { workflows.approve(started.openTaskId!!) }

        Audit.assertAudited(tenant, "workflow.task.blocked", blocked.openTaskId.toString())
        assertThatThrownBy { act(second) { workflows.approve(blocked.openTaskId!!) } }
            .isInstanceOf(WorkflowRefusedException::class.java)
            .extracting("reason")
            .isEqualTo("TASK_BLOCKED")

        val reassigned = act(admin) { workflows.reassign(blocked.openTaskId!!, third, "on leave") }
        assertThat(reassigned.currentAssignees).containsExactly(third)
        Audit.assertAudited(tenant, "workflow.task.reassigned", blocked.openTaskId.toString(), admin.toString())
        assertThat(act(third) { workflows.approve(reassigned.openTaskId!!) }.status).isEqualTo(InstanceStatus.APPROVED)
    }

    @Test
    fun `a veto by the subject rolls the whole action back`() {
        val item = UUID.randomUUID()
        subject.vetoed += item

        assertThatThrownBy { act(initiator) { workflows.start(TestWorkflowSubject.TYPE, item, listOf(first)) } }
            .isInstanceOf(IllegalStateException::class.java)

        AppRoleJdbc.inTransaction(tenant) { c ->
            assertThat(c.long("select count(*) from workflow_instance where subject_id = ?", item)).isZero()
        }
        assertThat(Audit.entries(tenant).filter { it.action.startsWith("workflow.") }).isEmpty()
    }

    @Test
    fun `the history cannot be changed or deleted, even by the application role`() {
        val started = act(initiator) { workflows.start(TestWorkflowSubject.TYPE, UUID.randomUUID(), listOf(first)) }

        AppRoleJdbc.inTransaction(tenant) { c ->
            assertThatThrownBy { c.update("update workflow_action set remark = 'changed' where instance_id = ?", started.instanceId) }
                .isInstanceOf(SQLException::class.java)
        }
        AppRoleJdbc.inTransaction(tenant) { c ->
            assertThatThrownBy { c.update("delete from workflow_action where instance_id = ?", started.instanceId) }
                .isInstanceOf(SQLException::class.java)
        }
    }

    @Test
    fun `of two simultaneous approvals of one task one wins, the other is refused, and one action is written`() {
        val started = act(initiator) { workflows.start(TestWorkflowSubject.TYPE, UUID.randomUUID(), listOf(first, second)) }
        val task = started.openTaskId!!
        val firstInside = CountDownLatch(1)
        subject.onAction = { _ ->
            if (firstInside.count > 0) {
                firstInside.countDown()
                Thread.sleep(HOLD_MILLIS)
            }
        }
        val pool = Executors.newFixedThreadPool(2)
        try {
            val a = pool.submit<Result<WorkflowView>> { runCatching { act(first) { workflows.approve(task) } } }
            assertThat(firstInside.await(10, TimeUnit.SECONDS)).isTrue()
            val b = pool.submit<Result<WorkflowView>> { runCatching { act(first) { workflows.approve(task) } } }
            val results = listOf(a.get(30, TimeUnit.SECONDS), b.get(30, TimeUnit.SECONDS))

            assertThat(results.count { it.isSuccess }).isEqualTo(1)
            val failure = results.single { it.isFailure }.exceptionOrNull()
            assertThat(failure).isInstanceOfAny(WorkflowRefusedException::class.java, RefusedException::class.java)
        } finally {
            pool.shutdownNow()
        }
        AppRoleJdbc.inTransaction(tenant) { c ->
            assertThat(c.long("select count(*) from workflow_action where task_id = ? and kind = 'APPROVE'", task)).isEqualTo(1)
        }
    }

    @Test
    fun `a subject is started once, and another tenant cannot see or act on the instance`() {
        val item = UUID.randomUUID()
        val started = act(initiator) { workflows.start(TestWorkflowSubject.TYPE, item, listOf(first)) }
        assertThatThrownBy { act(initiator) { workflows.start(TestWorkflowSubject.TYPE, item, listOf(first)) } }
            .isInstanceOf(WorkflowRefusedException::class.java)
            .extracting("reason")
            .isEqualTo("ALREADY_STARTED")

        val other = registry.aTenant("wf-other")
        assertThatThrownBy {
            ActorContext.call(
                ActorScope(Actor.User(first), null),
            ) { transactions.inTenant(other) { workflows.approve(started.openTaskId!!) } }
        }.isInstanceOf(WorkflowRefusedException::class.java)
            .extracting("reason")
            .isEqualTo("TASK_NOT_FOUND")
        AppRoleJdbc.inTransaction(other) { c ->
            assertThat(c.long("select count(*) from workflow_instance")).isZero()
            assertThat(c.long("select count(*) from workflow_definition where key = 'sequential-approval'")).isEqualTo(1)
        }
    }

    private fun <T> act(
        user: UUID,
        block: () -> T,
    ): T = ActorContext.call(ActorScope(Actor.User(user), null)) { transactions.inTenant(tenant, block) }

    private companion object {
        const val HOLD_MILLIS = 500L
    }
}
