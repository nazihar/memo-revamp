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
import com.memo.org.OrgSeeding
import com.memo.tenancy.TenantRegistry
import com.memo.testsupport.Audit
import com.memo.testsupport.IntegrationTest
import com.memo.testsupport.MutableClock
import com.memo.testsupport.TestWorkflowSubject
import com.memo.testsupport.WorkflowEventRecorder
import com.memo.testsupport.aTenant
import com.memo.workflow.internal.Calendars
import com.memo.workflow.internal.DefinitionLifecycle
import com.memo.workflow.internal.TimerEscalations
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
import java.time.LocalDate
import java.util.UUID

/**
 * Escalation of overdue tasks (design E05 §4.3, test plan T5): to the holder's next level by the reporting line, to a
 * named person to be told, or to nobody (the initiator is never a target) and so to a blocked task; recorded once as
 * done by the workflow, even when the timer runs twice; audited.
 */
class EscalationIT : IntegrationTest() {
    @Autowired lateinit var workflows: Workflows

    @Autowired lateinit var queries: WorkflowQueries

    @Autowired lateinit var lifecycle: DefinitionLifecycle

    @Autowired lateinit var seeding: OrgSeeding

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

    @Autowired lateinit var escalations: ObjectProvider<TimerEscalations>

    private var tenant = TenantId(UUID(0, 0))
    private val clock = MutableClock()
    private val timers by lazy { WorkflowTimers(tenantDsl, transactions, ids, calendars, events, clock, task, scheduler, escalations) }
    private val start = LocalDate.of(2026, 1, 1)
    private val author = UUID.randomUUID()
    private val bob = UUID.randomUUID()
    private val carol = UUID.randomUUID()
    private val dave = UUID.randomUUID()
    private val admin = UUID.randomUUID()

    @BeforeEach
    fun organisation() {
        tenant = registry.aTenant("escalation")
        val entity = UUID.randomUUID()
        val unit = UUID.randomUUID()
        val officer = UUID.randomUUID()
        val head = UUID.randomUUID()
        act(admin) {
            seeding.legalEntity(entity, "Bank", start)
            seeding.unit(unit, entity, null, "CR", "Credit", start)
            seeding.position(head, unit, "Head of Credit", head = true, start)
            seeding.position(officer, unit, "Credit Officer", head = false, start)
            seeding.reportingLine(officer, head, start)
            seeding.assignment(officer, bob, start)
            seeding.assignment(head, carol, start)
        }
    }

    @Test
    fun `an overdue task goes to the holder's next level, once, as the workflow`() {
        val item = start("""{"after":"PT1H","action":"nextLevel"}""")
        val view = view(item)
        assertThat(view.currentAssignees).containsExactly(bob)
        val first = checkNotNull(view.openTaskId)

        clock.now = Instant.now().plus(Duration.ofHours(2).plusMinutes(5))
        assertThat(run()).isEqualTo(2)
        assertThat(run()).isZero()
        assertThat(view(item).currentAssignees).containsExactly(carol)
        val escalated = history(view.instanceId).filter { it.kind == ActionKind.ESCALATE }
        assertThat(escalated).hasSize(1)
        assertThat(escalated.single().actorId).isEqualTo(TimerEscalations.WORKFLOW_ACTOR)
        Audit.assertAudited(tenant, "workflow.task.escalated", first.toString())
    }

    @Test
    fun `a notify escalation tells the named person and leaves the task where it is`() {
        val item = start("""{"after":"PT1H","action":"notify","to":{"kind":"person","userId":"$dave"}}""")
        clock.now = Instant.now().plus(Duration.ofHours(2).plusMinutes(5))
        run()
        assertThat(view(item).currentAssignees).containsExactly(bob)
        val told = recorder.of(item).filterIsInstance<WorkflowEscalated>()
        assertThat(told.map { it.recipientId }).containsExactly(dave)
    }

    @Test
    fun `nobody left to escalate to means a blocked task, never the initiator`() {
        val item = start("""{"after":"PT1H","action":"reassign","to":{"kind":"person","userId":"$author"}}""")
        clock.now = Instant.now().plus(Duration.ofHours(2).plusMinutes(5))
        run()
        val view = view(item)
        assertThat(view.currentAssignees).isEmpty()
        assertThat(view.openTaskBlocked).isEqualTo(BlockedReason.UNRESOLVED)
    }

    private fun start(escalate: String): UUID {
        val key = "esc" + UUID.randomUUID().toString().take(8)
        val body =
            """{"schemaVersion":2,"start":"review","steps":[
              {"id":"review","type":"approve","name":"Review","assignee":{"kind":"person","userId":"$bob"},
               "sla":{"due":"PT1H","workingTime":false,"escalate":$escalate},"next":"end"}]}"""
        val draft = act(admin) { lifecycle.create(key, "Escalating", null, false, json.readTree(body)) }
        act(admin) { lifecycle.submit(key, 1, draft.version.revision) }
        act(UUID.randomUUID()) { lifecycle.approve(key, 1, null) }
        val item = UUID.randomUUID()
        act(author) { workflows.start(TestWorkflowSubject.TYPE, item, emptyList(), definitionKey = key) }
        return item
    }

    private fun view(item: UUID): WorkflowView = act(admin) { checkNotNull(queries.viewOf(TestWorkflowSubject.TYPE, item)) }

    private fun history(instanceId: UUID): List<ActionEntry> = act(admin) { queries.history(instanceId) }

    private fun run(): Int = TenantContext.call(tenant) { ActorContext.asSystem("test") { timers.runDue() } }

    private fun <T> act(
        user: UUID,
        block: () -> T,
    ): T = ActorContext.call(ActorScope(Actor.User(user), null)) { TenantContext.call(tenant) { transactions.inTenant(tenant, block) } }
}
