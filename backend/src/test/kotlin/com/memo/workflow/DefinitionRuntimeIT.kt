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
import com.memo.jooq.tables.references.WORKFLOW_INSTANCE
import com.memo.tenancy.TenantRegistry
import com.memo.testsupport.Audit
import com.memo.testsupport.IntegrationTest
import com.memo.testsupport.TestWorkflowSubject
import com.memo.testsupport.WorkflowEventRecorder
import com.memo.testsupport.aTenant
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.jooq.JSONB
import org.jooq.impl.DSL
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import tools.jackson.databind.json.JsonMapper
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Instances run their pinned definition (design E03 §4.2, test plan T3): review, branch, approve, route, notify and
 * escalate steps; the branch taken is recorded with its inputs; a definition without a route step takes no route.
 */
class DefinitionRuntimeIT : IntegrationTest() {
    @Autowired lateinit var workflows: Workflows

    @Autowired lateinit var transactions: TenantTransactions

    @Autowired lateinit var registry: TenantRegistry

    @Autowired lateinit var tenantDsl: TenantDsl

    @Autowired lateinit var subject: TestWorkflowSubject

    @Autowired lateinit var recorder: WorkflowEventRecorder

    @Autowired lateinit var json: JsonMapper

    private var tenant = TenantId(UUID(0, 0))
    private val initiator = UUID.randomUUID()
    private val reviewer = UUID.randomUUID()
    private val big = UUID.randomUUID()
    private val small = UUID.randomUUID()
    private val first = UUID.randomUUID()
    private val second = UUID.randomUUID()

    @BeforeEach
    fun tenant() {
        tenant = registry.aTenant("def")
    }

    @AfterEach
    fun reset() {
        subject.values.clear()
    }

    private fun branching(otherwise: String = "small") =
        """
        {"schemaVersion":2,"attributes":[{"name":"amount","type":"int"}],"start":"review","steps":[
          {"id":"review","type":"review","name":"Department review","assignee":{"kind":"person","userId":"$reviewer"},"next":"size"},
          {"id":"size","type":"branch","branches":[{"when":"item.amount > 1000","next":"big"}],"otherwise":"$otherwise"},
          {"id":"big","type":"approve","name":"Director","assignee":{"kind":"person","userId":"$big"},"next":"tell"},
          {"id":"small","type":"approve","name":"Manager","assignee":{"kind":"person","userId":"$small"},"next":"tell"},
          {"id":"tell","type":"notify","recipients":[{"kind":"initiator"}],"message":"approved","next":"board"},
          {"id":"board","type":"escalate-to-forum","forum":"board","next":"end"}
        ]}
        """

    @Test
    fun `a review, a branch on the item's amount and an approval, then a notification and an escalation`() {
        publish("capex", branching())
        val item = UUID.randomUUID()
        subject.values[item] = mapOf("amount" to 5000)
        val started = act(initiator) { workflows.start(TestWorkflowSubject.TYPE, item, emptyList(), definitionKey = "capex") }
        assertThat(started.currentAssignees).containsExactly(reviewer)
        assertThat(stepName(started.instanceId)).isEqualTo("Department review")

        val reviewed = act(reviewer) { workflows.approve(started.openTaskId!!) }
        assertThat(reviewed.currentAssignees).containsExactly(big)
        Audit.assertAudited(tenant, "workflow.task.recommended", started.openTaskId.toString(), reviewer.toString())
        assertThat(stepName(started.instanceId)).isEqualTo("Director")

        val done = act(big) { workflows.approve(reviewed.openTaskId!!) }
        assertThat(done.status).isEqualTo(InstanceStatus.APPROVED)

        val history = history(started.instanceId)
        assertThat(history.map { it.first })
            .containsExactly("SUBMIT", "RECOMMEND", "ROUTE", "APPROVE", "NOTIFY", "ESCALATE_TO_FORUM")
        val route = history[2].second
        assertThat(route["step"].asString()).isEqualTo("size")
        assertThat(route["taken"].asInt()).isZero()
        assertThat(route["inputs"]["amount"].asInt()).isEqualTo(5000)
        val events = recorder.of(item)
        assertThat(events.filterIsInstance<WorkflowNotifyRequested>().single().recipients).containsExactly(initiator)
        assertThat(events.filterIsInstance<WorkflowEscalatedToForum>().single().forum).isEqualTo("board")
    }

    @Test
    fun `a condition that cannot be evaluated takes the branch's otherwise, and the history says so`() {
        publish("capex", branching())
        val item = UUID.randomUUID()
        val started = act(initiator) { workflows.start(TestWorkflowSubject.TYPE, item, emptyList(), definitionKey = "capex") }
        val reviewed = act(reviewer) { workflows.approve(started.openTaskId!!) }
        assertThat(reviewed.currentAssignees).containsExactly(small)
        val route = history(started.instanceId).single { it.first == "ROUTE" }.second
        assertThat(route["taken"].asString()).isEqualTo("otherwise")
        assertThat(route["failed"].toList().map { it.asInt() }).containsExactly(0)
    }

    @Test
    fun `a route step in the middle runs the initiator's approvers in order between fixed steps`() {
        publish(
            "routed",
            """
            {"schemaVersion":2,"start":"review","steps":[
              {"id":"review","type":"review","assignee":{"kind":"person","userId":"$reviewer"},"next":"route"},
              {"id":"route","type":"route","name":"Approvers","next":"end"}
            ]}
            """,
        )
        val item = UUID.randomUUID()
        val started = act(initiator) { workflows.start(TestWorkflowSubject.TYPE, item, listOf(first, second), definitionKey = "routed") }
        val afterReview = act(reviewer) { workflows.approve(started.openTaskId!!) }
        assertThat(afterReview.currentAssignees).containsExactly(first)
        assertThat(stepName(started.instanceId)).isEqualTo("Approvers")
        val afterFirst = act(first) { workflows.approve(afterReview.openTaskId!!) }
        assertThat(afterFirst.currentAssignees).containsExactly(second)
        assertThat(act(second) { workflows.approve(afterFirst.openTaskId!!) }.status).isEqualTo(InstanceStatus.APPROVED)
    }

    @Test
    fun `a definition without a route step refuses a route, and one with a route step refuses none`() {
        publish("capex", branching())
        val refusedRoute = {
            act(initiator) { workflows.start(TestWorkflowSubject.TYPE, UUID.randomUUID(), listOf(first), definitionKey = "capex") }
        }
        assertThatThrownBy { refusedRoute() }.isInstanceOfSatisfying(WorkflowRefusedException::class.java) {
            assertThat(it.reason).isEqualTo("ROUTE_NOT_USED")
        }
        assertThatThrownBy { act(initiator) { workflows.start(TestWorkflowSubject.TYPE, UUID.randomUUID(), emptyList()) } }
            .isInstanceOfSatisfying(WorkflowRefusedException::class.java) { assertThat(it.reason).isEqualTo("ROUTE_EMPTY") }
    }

    @Test
    fun `an instance keeps the version it started on when a newer one is published`() {
        publish("capex", branching(otherwise = "small"))
        val item = UUID.randomUUID()
        val started = act(initiator) { workflows.start(TestWorkflowSubject.TYPE, item, emptyList(), definitionKey = "capex") }
        // Version 2 sends small amounts to the director instead.
        publish("capex", branching(otherwise = "big"))
        val reviewed = act(reviewer) { workflows.approve(started.openTaskId!!) }
        assertThat(reviewed.currentAssignees).containsExactly(small)
        val newer = act(initiator) { workflows.start(TestWorkflowSubject.TYPE, UUID.randomUUID(), emptyList(), definitionKey = "capex") }
        val newerReviewed = act(reviewer) { workflows.approve(newer.openTaskId!!) }
        assertThat(newerReviewed.currentAssignees).containsExactly(big)
    }

    /** Publishes [body] as the next version of [key] (the lifecycle API arrives in T7), retiring the previous one. */
    private fun publish(
        key: String,
        body: String,
    ) = act(initiator) {
        val dsl = tenantDsl.current()
        val now = OffsetDateTime.now()
        val definitionId =
            dsl
                .select(
                    WORKFLOW_DEFINITION.ID,
                ).from(WORKFLOW_DEFINITION)
                .where(WORKFLOW_DEFINITION.KEY.eq(key))
                .fetchOne(WORKFLOW_DEFINITION.ID)
                ?: UUID.randomUUID().also { id ->
                    dsl
                        .insertInto(WORKFLOW_DEFINITION)
                        .set(WORKFLOW_DEFINITION.ID, id)
                        .set(WORKFLOW_DEFINITION.TENANT_ID, tenant.value)
                        .set(WORKFLOW_DEFINITION.KEY, key)
                        .set(WORKFLOW_DEFINITION.NAME, key)
                        .set(WORKFLOW_DEFINITION.BUILT_IN, false)
                        .set(WORKFLOW_DEFINITION.CREATED_AT, now)
                        .execute()
                }
        val number =
            (
                dsl
                    .select(DSL.max(WORKFLOW_DEFINITION_VERSION.NUMBER))
                    .from(WORKFLOW_DEFINITION_VERSION)
                    .where(WORKFLOW_DEFINITION_VERSION.DEFINITION_ID.eq(definitionId))
                    .fetchOne(0, Int::class.java) ?: 0
            ) + 1
        dsl
            .update(WORKFLOW_DEFINITION_VERSION)
            .set(WORKFLOW_DEFINITION_VERSION.STATE, "RETIRED")
            .where(WORKFLOW_DEFINITION_VERSION.DEFINITION_ID.eq(definitionId), WORKFLOW_DEFINITION_VERSION.STATE.eq("PUBLISHED"))
            .execute()
        val compact = JSONB.valueOf(json.writeValueAsString(json.readTree(body)))
        dsl
            .insertInto(WORKFLOW_DEFINITION_VERSION)
            .set(WORKFLOW_DEFINITION_VERSION.ID, UUID.randomUUID())
            .set(WORKFLOW_DEFINITION_VERSION.TENANT_ID, tenant.value)
            .set(WORKFLOW_DEFINITION_VERSION.DEFINITION_ID, definitionId)
            .set(WORKFLOW_DEFINITION_VERSION.NUMBER, number)
            .set(WORKFLOW_DEFINITION_VERSION.BODY, compact)
            .set(WORKFLOW_DEFINITION_VERSION.COMPILED, compact)
            .set(WORKFLOW_DEFINITION_VERSION.SCHEMA_VERSION, 2)
            .set(WORKFLOW_DEFINITION_VERSION.STATE, "PUBLISHED")
            .set(WORKFLOW_DEFINITION_VERSION.CREATED_AT, now)
            .set(WORKFLOW_DEFINITION_VERSION.PUBLISHED_AT, now)
            .execute()
    }

    private fun history(instanceId: UUID) =
        act(initiator) {
            tenantDsl
                .current()
                .select(WORKFLOW_ACTION.KIND, WORKFLOW_ACTION.DETAILS)
                .from(WORKFLOW_ACTION)
                .where(WORKFLOW_ACTION.INSTANCE_ID.eq(instanceId))
                .orderBy(WORKFLOW_ACTION.SEQ)
                .fetch { it.value1()!! to json.readTree(it.value2()!!.data()) }
        }

    private fun stepName(instanceId: UUID): String? =
        act(initiator) {
            tenantDsl
                .current()
                .select(WORKFLOW_INSTANCE.CURRENT_STEP_NAME)
                .from(WORKFLOW_INSTANCE)
                .where(WORKFLOW_INSTANCE.ID.eq(instanceId))
                .fetchOne(WORKFLOW_INSTANCE.CURRENT_STEP_NAME)
        }

    private fun <T> act(
        user: UUID,
        block: () -> T,
    ): T = ActorContext.call(ActorScope(Actor.User(user), null)) { transactions.inTenant(tenant, block) }
}
