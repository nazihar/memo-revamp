package com.memo.workflow

import com.github.kagkarlsson.scheduler.SchedulerClient
import com.github.kagkarlsson.scheduler.task.helper.OneTimeTask
import com.memo.common.context.Actor
import com.memo.common.context.ActorContext
import com.memo.common.context.ActorScope
import com.memo.common.id.IdGenerator
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantDsl
import com.memo.common.tenant.TenantId
import com.memo.common.tenant.TenantTransactions
import com.memo.jooq.tables.references.WORKFLOW_TIMER
import com.memo.tenancy.TenantRegistry
import com.memo.testsupport.IntegrationTest
import com.memo.testsupport.MutableClock
import com.memo.testsupport.TestWorkflowSubject
import com.memo.testsupport.WorkflowEventRecorder
import com.memo.testsupport.aTenant
import com.memo.workflow.internal.Calendars
import com.memo.workflow.internal.DefinitionLifecycle
import com.memo.workflow.internal.TimerFire
import com.memo.workflow.internal.WorkflowTimers
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.ApplicationEventPublisher
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Timers of SLA steps (design E05 §4.2, §4.3, test plan T3), fired with a hand-moved clock: created with the task and
 * cancelled when it closes, passed on when it moves, and doing nothing once stale; firing announces reminders and the
 * due time once each.
 */
class TimersIT : IntegrationTest() {
    @Autowired lateinit var workflows: Workflows

    @Autowired lateinit var lifecycle: DefinitionLifecycle

    @Autowired lateinit var transactions: TenantTransactions

    @Autowired lateinit var registry: TenantRegistry

    @Autowired lateinit var tenantDsl: TenantDsl

    @Autowired lateinit var json: JsonMapper

    @Autowired lateinit var ids: IdGenerator

    @Autowired lateinit var calendars: Calendars

    @Autowired lateinit var events: ApplicationEventPublisher

    @Autowired lateinit var recorder: WorkflowEventRecorder

    @Autowired
    @Qualifier("workflowTimerTask")
    lateinit var task: OneTimeTask<TimerFire>

    @Autowired lateinit var scheduler: ObjectProvider<SchedulerClient>

    private var tenant = TenantId(UUID(0, 0))
    private val clock = MutableClock()
    private val author = UUID.randomUUID()
    private val bob = UUID.randomUUID()
    private val dave = UUID.randomUUID()
    private val admin = UUID.randomUUID()

    /** The timers with a clock the test moves; the application's own run on real time. */
    private val timers by lazy { WorkflowTimers(tenantDsl, transactions, ids, calendars, events, clock, task, scheduler) }

    @BeforeEach
    fun definition() {
        tenant = registry.aTenant("timers")
        val body =
            """{"schemaVersion":2,"start":"review","steps":[
              {"id":"review","type":"approve","name":"Review","assignee":{"kind":"person","userId":"$bob"},
               "sla":{"due":"PT4H","workingTime":false,"remind":{"before":"PT1H","after":["PT1H"]},
                      "escalate":{"after":"PT2H","action":"notify"}},"next":"end"}]}"""
        val draft = act(admin) { lifecycle.create("timed", "Timed", null, false, json.readTree(body)) }
        act(admin) { lifecycle.submit("timed", 1, draft.version.revision) }
        act(UUID.randomUUID()) { lifecycle.approve("timed", 1, null) }
    }

    @Test
    fun `a task's timers fire in order, once each, and only while it is open`() {
        val item = UUID.randomUUID()
        val started = act(author) { workflows.start(TestWorkflowSubject.TYPE, item, emptyList(), definitionKey = "timed") }
        assertThat(states(started.instanceId)).containsExactlyInAnyOrder("PENDING", "PENDING", "PENDING", "PENDING")

        clock.now = Instant.now().plus(Duration.ofHours(3).plusMinutes(5))
        assertThat(run()).isEqualTo(1)
        clock.now = clock.now.plus(Duration.ofHours(2))
        assertThat(run()).isEqualTo(2)
        assertThat(run()).isZero()
        val fired = recorder.of(item).filterIsInstance<WorkflowTimerFired>().map { it.kind.name to it.ordinal }
        assertThat(fired).containsExactly("REMIND_BEFORE" to 0, "DUE" to 0, "REMIND_AFTER" to 1)
        assertThat(fired.size).isEqualTo(3)

        act(bob) { workflows.approve(checkNotNull(started.openTaskId)) }
        assertThat(states(started.instanceId)).containsExactlyInAnyOrder("FIRED", "FIRED", "FIRED", "CANCELLED")
        clock.now = clock.now.plus(Duration.ofDays(1))
        assertThat(run()).isZero()
    }

    @Test
    fun `a reassigned task keeps its timers, and a timer whose task closed meanwhile does nothing`() {
        val item = UUID.randomUUID()
        val started = act(author) { workflows.start(TestWorkflowSubject.TYPE, item, emptyList(), definitionKey = "timed") }
        val moved = act(admin) { workflows.reassign(checkNotNull(started.openTaskId), dave, "Bob is away") }
        val newTask = checkNotNull(moved.openTaskId)
        assertThat(taskIds(started.instanceId)).containsOnly(newTask)
        assertThat(states(started.instanceId)).allMatch { it == "PENDING" }

        // The task closes while its timers are still pending in the scheduler: they fire into nothing.
        val pending = timerIds(started.instanceId)
        act(dave) { workflows.approve(newTask) }
        pending.forEach { id -> TenantContext.call(tenant) { ActorContext.asSystem("test") { timers.fire(id) } } }
        assertThat(recorder.of(item).filterIsInstance<WorkflowTimerFired>()).isEmpty()
        assertThat(states(started.instanceId)).allMatch { it == "CANCELLED" }
    }

    private fun run(): Int = TenantContext.call(tenant) { ActorContext.asSystem("test") { timers.runDue() } }

    private fun states(instanceId: UUID): List<String> = rows(instanceId).map { it.second }

    private fun taskIds(instanceId: UUID): Set<UUID> = rows(instanceId).map { it.first }.toSet()

    private fun timerIds(instanceId: UUID): List<UUID> =
        act(admin) {
            tenantDsl
                .current()
                .select(WORKFLOW_TIMER.ID)
                .from(WORKFLOW_TIMER)
                .where(WORKFLOW_TIMER.INSTANCE_ID.eq(instanceId))
                .fetch(WORKFLOW_TIMER.ID)
                .filterNotNull()
        }

    private fun rows(instanceId: UUID): List<Pair<UUID, String>> =
        act(admin) {
            tenantDsl
                .current()
                .select(WORKFLOW_TIMER.TASK_ID, WORKFLOW_TIMER.STATE)
                .from(WORKFLOW_TIMER)
                .where(WORKFLOW_TIMER.INSTANCE_ID.eq(instanceId))
                .fetch { it.value1()!! to it.value2()!! }
        }

    private fun <T> act(
        user: UUID,
        block: () -> T,
    ): T = ActorContext.call(ActorScope(Actor.User(user), null)) { TenantContext.call(tenant) { transactions.inTenant(tenant, block) } }
}
