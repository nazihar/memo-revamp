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
import com.memo.jooq.tables.references.WORKFLOW_STEP
import com.memo.jooq.tables.references.WORKFLOW_TASK
import com.memo.tenancy.TenantRegistry
import com.memo.testsupport.Audit
import com.memo.testsupport.IntegrationTest
import com.memo.testsupport.TestWorkflowSubject
import com.memo.testsupport.aTenant
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.jooq.JSONB
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.OffsetDateTime
import java.util.UUID

/**
 * What a holder can do besides deciding (design E03 §4.4, test plan T5): send back, forward, delegate, ask and answer,
 * refer and opinion; the actions a step offers and the remarks they need; and who holds the item meanwhile.
 */
class TaskActionsIT : IntegrationTest() {
    @Autowired lateinit var workflows: Workflows

    @Autowired lateinit var handOns: WorkflowHandOns

    @Autowired lateinit var transactions: TenantTransactions

    @Autowired lateinit var registry: TenantRegistry

    @Autowired lateinit var tenantDsl: TenantDsl

    private var tenant = TenantId(UUID(0, 0))
    private val initiator = UUID.randomUUID()
    private val reviewer = UUID.randomUUID()
    private val first = UUID.randomUUID()
    private val second = UUID.randomUUID()
    private val other = UUID.randomUUID()
    private val expert = UUID.randomUUID()

    @BeforeEach
    fun setUp() {
        tenant = registry.aTenant("act")
        val all = """["APPROVE","RETURN","REJECT","SEND_BACK","FORWARD","DELEGATE","ASK","REFER"]"""
        publish(
            """
            {"schemaVersion":2,"start":"a","steps":[
              {"id":"a","type":"review","name":"Review","assignee":{"kind":"person","userId":"$reviewer"},"next":"b"},
              {"id":"b","type":"approve","name":"First","assignee":{"kind":"person","userId":"$first"},"actions":$all,"next":"c"},
              {"id":"c","type":"approve","name":"Second","assignee":{"kind":"person","userId":"$second"},"actions":$all,"next":"end"}
            ]}
            """,
        )
    }

    private fun started(): WorkflowView {
        val view = act(initiator) { workflows.start(TestWorkflowSubject.TYPE, UUID.randomUUID(), emptyList(), definitionKey = "acts") }
        val afterReview = act(reviewer) { workflows.approve(view.openTaskId!!) }
        return afterReview
    }

    @Test
    fun `a send-back runs the earlier step again, and the steps after it once more`() {
        val atFirst = started()
        val atSecond = act(first) { workflows.approve(atFirst.openTaskId!!) }
        val review = stepOf(atSecond.instanceId, "a")
        refused("NOT_AN_EARLIER_STEP") { act(second) { handOns.sendBack(atSecond.openTaskId!!, stepOf(atSecond.instanceId, "c"), "x") } }
        refused("REMARK_REQUIRED") { act(second) { handOns.sendBack(atSecond.openTaskId!!, review, null) } }

        val back = act(second) { handOns.sendBack(atSecond.openTaskId!!, review, "please check the annex") }
        assertThat(back.status).isEqualTo(InstanceStatus.RUNNING)
        assertThat(back.currentAssignees).containsExactly(reviewer)
        val again = act(reviewer) { workflows.approve(back.openTaskId!!) }
        assertThat(again.currentAssignees).containsExactly(first)
        val thenSecond = act(first) { workflows.approve(again.openTaskId!!) }
        assertThat(act(second) { workflows.approve(thenSecond.openTaskId!!) }.status).isEqualTo(InstanceStatus.APPROVED)
        assertThat(kinds(back.instanceId)).containsSubsequence("SEND_BACK", "RECOMMEND", "APPROVE", "APPROVE")
        Audit.assertAudited(tenant, "workflow.task.sent_back", atSecond.openTaskId.toString(), second.toString())
    }

    @Test
    fun `forward moves the step to someone else, never to the initiator`() {
        val atFirst = started()
        refused("INITIATOR_AS_APPROVER") { act(first) { handOns.forward(atFirst.openTaskId!!, initiator, "you decide") } }
        refused("REMARK_REQUIRED") { act(first) { handOns.forward(atFirst.openTaskId!!, other, "") } }
        val forwarded = act(first) { handOns.forward(atFirst.openTaskId!!, other, "on leave") }
        assertThat(forwarded.currentAssignees).containsExactly(other)
        refused("NOT_ASSIGNEE") { act(first) { workflows.approve(forwarded.openTaskId!!) } }
        assertThat(act(other) { workflows.approve(forwarded.openTaskId!!) }.currentAssignees).containsExactly(second)
        Audit.assertAudited(tenant, "workflow.task.forwarded", atFirst.openTaskId.toString(), first.toString())
    }

    @Test
    fun `a delegate acts for the holder and cannot hand the task on again`() {
        val atFirst = started()
        val delegated = act(first) { handOns.delegate(atFirst.openTaskId!!, other, null) }
        assertThat(delegated.currentAssignees).containsExactly(other)
        refused("ACTION_NOT_ALLOWED") { act(other) { handOns.forward(delegated.openTaskId!!, expert, "busy") } }
        act(other) { workflows.approve(delegated.openTaskId!!) }
        val approval = actions(delegated.instanceId).last { it.first == "APPROVE" }
        assertThat(approval.second).isEqualTo(other)
        assertThat(approval.third).describedAs("acting for").isEqualTo(first)
        Audit.assertAudited(tenant, "workflow.task.delegated", atFirst.openTaskId.toString(), first.toString())
    }

    @Test
    fun `a question puts the item with the person asked until they answer`() {
        val atFirst = started()
        val asked = act(first) { handOns.ask(atFirst.openTaskId!!, initiator, "Which budget line?") }
        assertThat(asked.currentAssignees).containsExactly(initiator)
        refused("WAITING_FOR_ANSWER") { act(first) { workflows.approve(atFirst.openTaskId!!) } }
        val question = openTask(asked.instanceId, initiator)
        refused("REMARK_REQUIRED") { act(initiator) { handOns.answer(question, null) } }
        val answered = act(initiator) { handOns.answer(question, "Line 4.2") }
        assertThat(answered.currentAssignees).containsExactly(first)
        assertThat(act(first) { workflows.approve(atFirst.openTaskId!!) }.currentAssignees).containsExactly(second)
        Audit.assertAudited(tenant, "workflow.task.asked", atFirst.openTaskId.toString(), first.toString())
        Audit.assertAudited(tenant, "workflow.task.answered", question.toString(), initiator.toString())
    }

    @Test
    fun `opinions are asked for and given while the holder keeps the task, and open ones end with the step`() {
        val atFirst = started()
        refused("SAME_ASSIGNEE") { act(first) { handOns.refer(atFirst.openTaskId!!, listOf(first), "views?") } }
        val referred = act(first) { handOns.refer(atFirst.openTaskId!!, listOf(other, expert), "Is the cost estimate sound?") }
        assertThat(referred.currentAssignees).describedAs("people consulted do not hold the item").containsExactly(first)
        val opinion = openTask(referred.instanceId, expert)
        refused("REMARK_REQUIRED") { act(expert) { handOns.giveOpinion(opinion, " ") } }
        act(expert) { handOns.giveOpinion(opinion, "Yes, within 5%") }
        val unanswered = openTask(referred.instanceId, other)
        act(first) { workflows.approve(atFirst.openTaskId!!) }
        assertThat(taskState(unanswered)).isEqualTo("CANCELLED")
        Audit.assertAudited(tenant, "workflow.task.referred", atFirst.openTaskId.toString(), first.toString())
        Audit.assertAudited(tenant, "workflow.task.opinion_given", opinion.toString(), expert.toString())
    }

    @Test
    fun `a step offers only the actions its definition lists`() {
        val view = act(initiator) { workflows.start(TestWorkflowSubject.TYPE, UUID.randomUUID(), emptyList(), definitionKey = "acts") }
        // The review step lists no actions: Recommend and Return only.
        refused("ACTION_NOT_ALLOWED") { act(reviewer) { workflows.reject(view.openTaskId!!, "no") } }
        refused("ACTION_NOT_ALLOWED") { act(reviewer) { handOns.forward(view.openTaskId!!, other, "busy") } }
    }

    private fun refused(
        reason: String,
        block: () -> Any,
    ) {
        assertThatThrownBy { block() }.isInstanceOfSatisfying(WorkflowRefusedException::class.java) {
            assertThat(it.reason).isEqualTo(reason)
        }
    }

    private fun publish(body: String) =
        act(initiator) {
            val dsl = tenantDsl.current()
            val now = OffsetDateTime.now()
            val id = UUID.randomUUID()
            dsl
                .insertInto(WORKFLOW_DEFINITION)
                .set(WORKFLOW_DEFINITION.ID, id)
                .set(WORKFLOW_DEFINITION.TENANT_ID, tenant.value)
                .set(WORKFLOW_DEFINITION.KEY, "acts")
                .set(WORKFLOW_DEFINITION.NAME, "acts")
                .set(WORKFLOW_DEFINITION.BUILT_IN, false)
                .set(WORKFLOW_DEFINITION.CREATED_AT, now)
                .execute()
            val compact = JSONB.valueOf(body.lines().joinToString("") { it.trim() })
            dsl
                .insertInto(WORKFLOW_DEFINITION_VERSION)
                .set(WORKFLOW_DEFINITION_VERSION.ID, UUID.randomUUID())
                .set(WORKFLOW_DEFINITION_VERSION.TENANT_ID, tenant.value)
                .set(WORKFLOW_DEFINITION_VERSION.DEFINITION_ID, id)
                .set(WORKFLOW_DEFINITION_VERSION.NUMBER, 1)
                .set(WORKFLOW_DEFINITION_VERSION.BODY, compact)
                .set(WORKFLOW_DEFINITION_VERSION.COMPILED, compact)
                .set(WORKFLOW_DEFINITION_VERSION.SCHEMA_VERSION, 2)
                .set(WORKFLOW_DEFINITION_VERSION.STATE, "PUBLISHED")
                .set(WORKFLOW_DEFINITION_VERSION.CREATED_AT, now)
                .execute()
        }

    private fun stepOf(
        instanceId: UUID,
        node: String,
    ): UUID =
        act(initiator) {
            tenantDsl
                .current()
                .select(WORKFLOW_STEP.ID)
                .from(WORKFLOW_STEP)
                .where(WORKFLOW_STEP.INSTANCE_ID.eq(instanceId), WORKFLOW_STEP.NODE_ID.eq(node))
                .orderBy(WORKFLOW_STEP.ORDINAL.desc())
                .limit(1)
                .fetchSingle(WORKFLOW_STEP.ID)!!
        }

    private fun openTask(
        instanceId: UUID,
        person: UUID,
    ): UUID =
        act(initiator) {
            tenantDsl
                .current()
                .select(WORKFLOW_TASK.ID)
                .from(WORKFLOW_TASK)
                .where(WORKFLOW_TASK.INSTANCE_ID.eq(instanceId), WORKFLOW_TASK.ASSIGNEE_ID.eq(person), WORKFLOW_TASK.STATE.eq("OPEN"))
                .fetchSingle(WORKFLOW_TASK.ID)!!
        }

    private fun taskState(taskId: UUID): String =
        act(initiator) {
            tenantDsl
                .current()
                .select(WORKFLOW_TASK.STATE)
                .from(WORKFLOW_TASK)
                .where(WORKFLOW_TASK.ID.eq(taskId))
                .fetchSingle(WORKFLOW_TASK.STATE)!!
        }

    private fun actions(instanceId: UUID): List<Triple<String, UUID, UUID?>> =
        act(initiator) {
            tenantDsl
                .current()
                .select(WORKFLOW_ACTION.KIND, WORKFLOW_ACTION.ACTOR_ID, WORKFLOW_ACTION.ACTING_FOR_ID)
                .from(WORKFLOW_ACTION)
                .where(WORKFLOW_ACTION.INSTANCE_ID.eq(instanceId))
                .orderBy(WORKFLOW_ACTION.SEQ)
                .fetch { Triple(it.value1()!!, it.value2()!!, it.value3()) }
        }

    private fun kinds(instanceId: UUID): List<String> = actions(instanceId).map { it.first }

    private fun <T> act(
        user: UUID,
        block: () -> T,
    ): T = ActorContext.call(ActorScope(Actor.User(user), null)) { transactions.inTenant(tenant, block) }
}
