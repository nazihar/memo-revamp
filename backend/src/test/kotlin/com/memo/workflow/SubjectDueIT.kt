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
import com.memo.jooq.tables.references.WORKFLOW_STEP
import com.memo.jooq.tables.references.WORKFLOW_TASK
import com.memo.jooq.tables.references.WORKFLOW_TIMER
import com.memo.tenancy.TenantRegistry
import com.memo.testsupport.IntegrationTest
import com.memo.testsupport.MutableClock
import com.memo.testsupport.TestSlotSubject
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
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Due times set by the subject (ADR-0068, E07 test plan T1 `SubjectDueIT`), with timers fired on a hand-moved clock:
 * the task is due when the item says, reminders and the escalation (to a slot) count from it; `redue` re-plans later,
 * earlier (a past reminder skipped, a past due time firing at once) and cleared; a send-back reads the due time again;
 * a closed instance ignores `redue`; "5 working days before" counts working days.
 */
class SubjectDueIT : IntegrationTest() {
    @Autowired lateinit var workflows: Workflows

    @Autowired lateinit var handOns: WorkflowHandOns

    @Autowired lateinit var lifecycle: DefinitionLifecycle

    @Autowired lateinit var transactions: TenantTransactions

    @Autowired lateinit var registry: TenantRegistry

    @Autowired lateinit var tenantDsl: TenantDsl

    @Autowired lateinit var json: JsonMapper

    @Autowired lateinit var ids: IdGenerator

    @Autowired lateinit var calendars: Calendars

    @Autowired lateinit var events: ApplicationEventPublisher

    @Autowired lateinit var recorder: WorkflowEventRecorder

    @Autowired lateinit var subject: TestSlotSubject

    @Autowired
    @Qualifier("workflowTimerTask")
    lateinit var task: OneTimeTask<TimerFire>

    @Autowired lateinit var scheduler: ObjectProvider<SchedulerClient>

    @Autowired lateinit var escalations: ObjectProvider<TimerEscalations>

    private var tenant = TenantId(UUID(0, 0))
    private val clock = MutableClock()
    private val timers by lazy { WorkflowTimers(tenantDsl, transactions, ids, calendars, events, clock, task, scheduler, escalations) }
    private val secretary = UUID.randomUUID()
    private val owner = UUID.randomUUID()
    private val verifier = UUID.randomUUID()
    private val chair = UUID.randomUUID()
    private val admin = UUID.randomUUID()

    @BeforeEach
    fun tenant() {
        tenant = registry.aTenant("subject-due")
    }

    @Test
    fun `the task is due when the item says, and redue re-plans it later, earlier and not at all`() {
        val key = publish(""""workingTime":false,""")
        val now = Instant.now().truncatedTo(ChronoUnit.SECONDS)
        val item = item(now.plus(Duration.ofDays(30)))
        val started = act(secretary) { workflows.start(TestSlotSubject.TYPE, item, emptyList(), definitionKey = key) }
        val due = now.plus(Duration.ofDays(30))
        assertThat(dueAt(started.instanceId)).isEqualTo(due)
        assertThat(pending(started.instanceId)).containsExactlyInAnyOrder(
            "REMIND_BEFORE" to due.minus(Duration.ofDays(5)),
            "DUE" to due,
            "REMIND_AFTER" to due.plus(Duration.ofDays(5)),
            "ESCALATE" to due.plus(Duration.ofDays(10)),
        )

        // Two weeks later: the pending reminder is cancelled and a new one planned.
        val later = due.plus(Duration.ofDays(14))
        subject.dues[item to "owner"] = later
        assertThat(act(admin) { workflows.redue(TestSlotSubject.TYPE, item) }).isEqualTo(1)
        assertThat(dueAt(started.instanceId)).isEqualTo(later)
        assertThat(pending(started.instanceId)).contains("REMIND_BEFORE" to later.minus(Duration.ofDays(5)), "DUE" to later)
        assertThat(states(started.instanceId).count { it == "CANCELLED" }).isEqualTo(4)

        // Back to six days ago: the reminders already past are skipped, the due time fires at once, the escalation waits.
        val earlier = now.minus(Duration.ofDays(6))
        subject.dues[item to "owner"] = earlier
        act(admin) { workflows.redue(TestSlotSubject.TYPE, item) }
        assertThat(pending(started.instanceId) + fired(started.instanceId)).containsExactlyInAnyOrder(
            "DUE" to earlier,
            "ESCALATE" to earlier.plus(Duration.ofDays(10)),
        )
        clock.now = now.plusSeconds(1)
        run()
        assertThat(timerEvents(item).map { it.kind }).containsExactly(TimerKind.DUE)

        // The escalation goes to the chair named by the item.
        clock.now = earlier.plus(Duration.ofDays(10)).plusSeconds(1)
        run()
        assertThat(recorder.of(item).filterIsInstance<WorkflowEscalated>().map { it.recipientId }).containsExactly(chair)

        // No due time: no timers.
        subject.dues.remove(item to "owner")
        act(admin) { workflows.redue(TestSlotSubject.TYPE, item) }
        assertThat(dueAt(started.instanceId)).isNull()
        assertThat(pending(started.instanceId)).isEmpty()
    }

    @Test
    fun `a step that runs again after a send-back reads the item's due time again, and a closed instance ignores redue`() {
        val key = publish(""""workingTime":false,""")
        val first = Instant.now().plus(Duration.ofDays(20)).truncatedTo(ChronoUnit.SECONDS)
        val item = item(first)
        val started = act(secretary) { workflows.start(TestSlotSubject.TYPE, item, emptyList(), definitionKey = key) }
        val verify = act(owner) { workflows.approve(checkNotNull(started.openTaskId)) }
        assertThat(verify.currentAssignees).containsExactly(verifier)

        val second = first.plus(Duration.ofDays(7))
        subject.dues[item to "owner"] = second
        val back = act(verifier) { handOns.sendBack(checkNotNull(verify.openTaskId), ownerStep(started.instanceId), "Attach the report") }
        assertThat(back.currentAssignees).containsExactly(owner)
        assertThat(dueAt(started.instanceId)).isEqualTo(second)

        val done = act(owner) { workflows.approve(checkNotNull(back.openTaskId)) }
        act(verifier) { workflows.approve(checkNotNull(done.openTaskId)) }
        assertThat(act(admin) { workflows.redue(TestSlotSubject.TYPE, item) }).isZero()
        assertThat(act(admin) { workflows.redue(TestSlotSubject.TYPE, UUID.randomUUID()) }).isZero()
    }

    @Test
    fun `a reminder before a due time in working time comes that many working days before, at the same time of day`() {
        val key = publish("")
        val calendar = act(admin) { calendars.calendar() }
        val due = Instant.now().plus(Duration.ofDays(40)).truncatedTo(ChronoUnit.SECONDS)
        val item = item(due)
        val started = act(secretary) { workflows.start(TestSlotSubject.TYPE, item, emptyList(), definitionKey = key) }
        val before = pending(started.instanceId).single { it.first == "REMIND_BEFORE" }.second
        val (from, to) = before.atZone(calendar.zone) to due.atZone(calendar.zone)
        assertThat(from.toLocalTime()).isEqualTo(to.toLocalTime())
        val days = generateSequence(from.toLocalDate()) { it.plusDays(1) }.takeWhile { it.isBefore(to.toLocalDate()) }
        assertThat(days.count(calendar::isWorkingDay)).isEqualTo(5)
        assertThat(calendar.isWorkingDay(from.toLocalDate())).isTrue()
    }

    private fun item(due: Instant): UUID {
        val item = UUID.randomUUID()
        subject.name(item, TestSlotSubject.OWNER, SlotHolder.Person(owner))
        subject.name(item, TestSlotSubject.VERIFIER, SlotHolder.Person(verifier))
        subject.name(item, TestSlotSubject.CHAIR, SlotHolder.Person(chair))
        subject.dues[item to "owner"] = due
        return item
    }

    private fun publish(workingTime: String): String {
        val key = "due-" + UUID.randomUUID().toString().take(8)
        val body =
            """{"schemaVersion":2,"subject":"${TestSlotSubject.TYPE}","start":"owner","steps":[
              {"id":"owner","type":"review","name":"Owner","assignee":{"kind":"subject","slot":"owner"},"actions":["RECOMMEND"],
               "sla":{"from":"subject",$workingTime"remind":{"before":"P5D","after":["P5D"]},
                      "escalate":{"after":"P10D","action":"notify","to":{"kind":"subject","slot":"chair"}}},"next":"verify"},
              {"id":"verify","type":"approve","name":"Verification","assignee":{"kind":"subject","slot":"verifier"},
               "actions":["APPROVE","SEND_BACK"],"remarks":["SEND_BACK"],"next":"end"}]}"""
        val draft = act(admin) { lifecycle.create(key, "Due", null, false, json.readTree(body)) }
        act(admin) { lifecycle.submit(key, 1, draft.version.revision) }
        act(UUID.randomUUID()) { lifecycle.approve(key, 1, null) }
        return key
    }

    private fun run(): Int = TenantContext.call(tenant) { ActorContext.asSystem("test") { timers.runDue() } }

    private fun timerEvents(item: UUID) = recorder.of(item).filterIsInstance<WorkflowTimerFired>()

    private fun dueAt(instanceId: UUID): Instant? =
        act(admin) {
            tenantDsl
                .current()
                .select(WORKFLOW_TASK.DUE_AT)
                .from(WORKFLOW_TASK)
                .where(WORKFLOW_TASK.INSTANCE_ID.eq(instanceId), WORKFLOW_TASK.STATE.eq("OPEN"))
                .fetchSingle(WORKFLOW_TASK.DUE_AT)
                ?.toInstant()
        }

    private fun ownerStep(instanceId: UUID): UUID =
        act(admin) {
            tenantDsl
                .current()
                .select(WORKFLOW_STEP.ID)
                .from(WORKFLOW_STEP)
                .where(WORKFLOW_STEP.INSTANCE_ID.eq(instanceId), WORKFLOW_STEP.NODE_ID.eq("owner"))
                .fetchSingle(WORKFLOW_STEP.ID)!!
        }

    private fun pending(instanceId: UUID): List<Pair<String, Instant>> = timers(instanceId, "PENDING")

    private fun fired(instanceId: UUID): List<Pair<String, Instant>> = timers(instanceId, "FIRED")

    private fun timers(
        instanceId: UUID,
        state: String,
    ): List<Pair<String, Instant>> =
        act(admin) {
            tenantDsl
                .current()
                .select(WORKFLOW_TIMER.KIND, WORKFLOW_TIMER.FIRES_AT)
                .from(WORKFLOW_TIMER)
                .where(WORKFLOW_TIMER.INSTANCE_ID.eq(instanceId), WORKFLOW_TIMER.STATE.eq(state))
                .fetch { it.value1()!! to it.value2()!!.toInstant() }
        }

    private fun states(instanceId: UUID): List<String> =
        act(admin) {
            tenantDsl
                .current()
                .select(WORKFLOW_TIMER.STATE)
                .from(WORKFLOW_TIMER)
                .where(WORKFLOW_TIMER.INSTANCE_ID.eq(instanceId))
                .fetch(WORKFLOW_TIMER.STATE)
                .filterNotNull()
        }

    private fun <T> act(
        user: UUID,
        block: () -> T,
    ): T = ActorContext.call(ActorScope(Actor.User(user), null)) { TenantContext.call(tenant) { transactions.inTenant(tenant, block) } }
}
