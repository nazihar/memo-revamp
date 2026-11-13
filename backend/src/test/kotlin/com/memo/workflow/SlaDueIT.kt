package com.memo.workflow

import com.memo.common.context.Actor
import com.memo.common.context.ActorContext
import com.memo.common.context.ActorScope
import com.memo.common.tenant.TenantDsl
import com.memo.common.tenant.TenantId
import com.memo.common.tenant.TenantTransactions
import com.memo.jooq.tables.references.WORKFLOW_TASK
import com.memo.tenancy.TenantRegistry
import com.memo.testsupport.IntegrationTest
import com.memo.testsupport.TestWorkflowSubject
import com.memo.testsupport.aTenant
import com.memo.workflow.calendar.SlaDuration
import com.memo.workflow.internal.Calendars
import com.memo.workflow.internal.DefinitionLifecycle
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Due times from step SLAs (design E05 §4.2, test plan T2): set when a decision task is created, in working or calendar
 * time; kept when the task moves to someone else; new when the step runs again after a send-back; shown as the
 * subject's current due time.
 */
class SlaDueIT : IntegrationTest() {
    @Autowired lateinit var workflows: Workflows

    @Autowired lateinit var handOns: WorkflowHandOns

    @Autowired lateinit var queries: WorkflowQueries

    @Autowired lateinit var lifecycle: DefinitionLifecycle

    @Autowired lateinit var calendars: Calendars

    @Autowired lateinit var transactions: TenantTransactions

    @Autowired lateinit var registry: TenantRegistry

    @Autowired lateinit var tenantDsl: TenantDsl

    @Autowired lateinit var json: JsonMapper

    private var tenant = TenantId(UUID(0, 0))
    private val author = UUID.randomUUID()
    private val bob = UUID.randomUUID()
    private val carol = UUID.randomUUID()
    private val dave = UUID.randomUUID()
    private val admin = UUID.randomUUID()

    @BeforeEach
    fun definition() {
        tenant = registry.aTenant("sla")
        val body =
            """{"schemaVersion":2,"start":"review","steps":[
              {"id":"review","type":"approve","name":"Review","assignee":{"kind":"person","userId":"$bob"},
               "sla":{"due":"P1D"},"next":"decide"},
              {"id":"decide","type":"approve","name":"Decide","assignee":{"kind":"person","userId":"$carol"},
               "actions":["APPROVE","RETURN","REJECT","SEND_BACK"],"sla":{"due":"PT2H","workingTime":false},"next":"end"}]}"""
        val draft = act(admin) { lifecycle.create("sla", "SLA", null, false, json.readTree(body)) }
        act(admin) { lifecycle.submit("sla", 1, draft.version.revision) }
        act(UUID.randomUUID()) { lifecycle.approve("sla", 1, null) }
    }

    @Test
    fun `due times follow the SLA, stay with the step when the task moves, and renew when the step runs again`() {
        val item = UUID.randomUUID()
        val started = act(author) { workflows.start(TestWorkflowSubject.TYPE, item, emptyList(), definitionKey = "sla") }
        val (review, reviewDue) = openTask(started.instanceId)
        val created = createdAt(review)
        val expected = act(admin) { calendars.calendar().due(created, SlaDuration.ofDays(1), workingTime = true) }
        assertThat(reviewDue).isEqualTo(expected)
        assertThat(act(author) { queries.dueOf(TestWorkflowSubject.TYPE, item) }).isEqualTo(expected)

        act(admin) { workflows.reassign(review, dave, "Bob is away") }
        val (moved, movedDue) = openTask(started.instanceId)
        assertThat(moved).isNotEqualTo(review)
        assertThat(movedDue).isEqualTo(reviewDue)

        act(dave) { workflows.approve(moved) }
        val (decide, decideDue) = openTask(started.instanceId)
        assertThat(decideDue).isEqualTo(createdAt(decide).plus(Duration.ofHours(2)))

        val target = checkNotNull(act(carol) { queries.task(decide) }).sendBackTargets.single()
        act(carol) { handOns.sendBack(decide, target.stepId, "Check the figures") }
        val (again, againDue) = openTask(started.instanceId)
        assertThat(againDue).isEqualTo(act(admin) { calendars.calendar().due(createdAt(again), SlaDuration.ofDays(1), workingTime = true) })
    }

    @Test
    fun `a step without an SLA has no due time`() {
        val body =
            """{"schemaVersion":2,"start":"a","steps":[
              {"id":"a","type":"approve","assignee":{"kind":"person","userId":"$bob"},"next":"end"}]}"""
        val draft = act(admin) { lifecycle.create("plain", "Plain", null, false, json.readTree(body)) }
        act(admin) { lifecycle.submit("plain", 1, draft.version.revision) }
        act(UUID.randomUUID()) { lifecycle.approve("plain", 1, null) }
        val started = act(author) { workflows.start(TestWorkflowSubject.TYPE, UUID.randomUUID(), emptyList(), definitionKey = "plain") }
        assertThat(openTask(started.instanceId).second).isNull()
    }

    private fun openTask(instanceId: UUID): Pair<UUID, Instant?> =
        act(admin) {
            tenantDsl
                .current()
                .select(WORKFLOW_TASK.ID, WORKFLOW_TASK.DUE_AT)
                .from(WORKFLOW_TASK)
                .where(WORKFLOW_TASK.INSTANCE_ID.eq(instanceId), WORKFLOW_TASK.STATE.eq("OPEN"), WORKFLOW_TASK.KIND.eq("DECIDE"))
                .fetchSingle { it.value1()!! to it.value2()?.toInstant() }
        }

    private fun createdAt(taskId: UUID): Instant =
        act(admin) {
            tenantDsl
                .current()
                .select(WORKFLOW_TASK.CREATED_AT)
                .from(WORKFLOW_TASK)
                .where(WORKFLOW_TASK.ID.eq(taskId))
                .fetchSingle { it.value1()!!.toInstant() }
        }

    private fun <T> act(
        user: UUID,
        block: () -> T,
    ): T = ActorContext.call(ActorScope(Actor.User(user), null)) { transactions.inTenant(tenant, block) }
}
