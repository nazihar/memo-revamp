package com.memo.workflow

import com.memo.authz.internal.RoleStore
import com.memo.common.authz.AccessAdministration
import com.memo.common.authz.BuiltInRole
import com.memo.common.context.Actor
import com.memo.common.context.ActorContext
import com.memo.common.context.ActorScope
import com.memo.common.tenant.TenantDsl
import com.memo.common.tenant.TenantId
import com.memo.common.tenant.TenantTransactions
import com.memo.jooq.tables.references.WORKFLOW_ACTION
import com.memo.jooq.tables.references.WORKFLOW_STEP
import com.memo.jooq.tables.references.WORKFLOW_TASK
import com.memo.org.OrgSeeding
import com.memo.tenancy.TenantRegistry
import com.memo.tenancy.TenantSettings
import com.memo.testsupport.Audit
import com.memo.testsupport.IntegrationTest
import com.memo.testsupport.TestWorkflowSubject
import com.memo.testsupport.aTenant
import com.memo.workflow.internal.DefinitionLifecycle
import com.memo.workflow.internal.WorkflowSettings
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import tools.jackson.databind.json.JsonMapper
import java.time.LocalDate
import java.util.UUID

/**
 * Separation of duties at resolution (design E04 §4.4, test plan T4): nobody approves twice in a round unless the tenant
 * allows it (SOD-2), people with a declared interest are never candidates (SOD-4), a holder who stands aside is replaced,
 * and a step that runs again after a send-back may go back to the person who decided it before.
 */
class SeparationOfDutiesIT : IntegrationTest() {
    @Autowired lateinit var workflows: Workflows

    @Autowired lateinit var handOns: WorkflowHandOns

    @Autowired lateinit var lifecycle: DefinitionLifecycle

    @Autowired lateinit var seeding: OrgSeeding

    @Autowired lateinit var access: AccessAdministration

    @Autowired lateinit var roles: RoleStore

    @Autowired lateinit var settings: TenantSettings

    @Autowired lateinit var subject: TestWorkflowSubject

    @Autowired lateinit var transactions: TenantTransactions

    @Autowired lateinit var registry: TenantRegistry

    @Autowired lateinit var tenantDsl: TenantDsl

    @Autowired lateinit var json: JsonMapper

    private var tenant = TenantId(UUID(0, 0))
    private val start = LocalDate.of(2026, 1, 1)
    private val entity = UUID.randomUUID()
    private val unit = UUID.randomUUID()
    private val gm = UUID.randomUUID()
    private val alice = UUID.randomUUID()
    private val bob = UUID.randomUUID()
    private val carol = UUID.randomUUID()
    private val initiator = UUID.randomUUID()
    private val admin = UUID.randomUUID()
    private var role = UUID(0, 0)

    @BeforeEach
    fun organisation() {
        tenant = registry.aTenant("sod")
        act(admin) {
            seeding.legalEntity(entity, "Bank", start)
            seeding.unit(unit, entity, null, "CR", "Credit", start)
            seeding.position(gm, unit, "GM Credit", head = true, start)
            seeding.assignment(gm, bob, start)
            role = checkNotNull(roles.builtInRoleId(BuiltInRole.ORG_ADMIN))
            access.grantBuiltIn(alice, BuiltInRole.ORG_ADMIN, unit)
            access.grantBuiltIn(bob, BuiltInRole.ORG_ADMIN, unit)
        }
        publish(
            "twice",
            """{"schemaVersion":2,"start":"gm","steps":[
              {"id":"gm","type":"approve","name":"GM","assignee":{"kind":"position","positionId":"$gm"},"next":"board"},
              {"id":"board","type":"approve","name":"Board","assignee":{"kind":"role","roleId":"$role","unit":"$unit"},"next":"end"}]}""",
        )
        publish(
            "single",
            """{"schemaVersion":2,"start":"gm","steps":[
              {"id":"gm","type":"approve","name":"GM","assignee":{"kind":"position","positionId":"$gm"},
               "fallback":{"kind":"person","userId":"$carol"},"next":"end"}]}""",
        )
    }

    @AfterEach
    fun reset() {
        subject.interests.clear()
    }

    @Test
    fun `nobody approves twice in a round, unless the tenant allows it`() {
        val started = startOn("twice")
        val second = act(bob) { workflows.approve(started.openTaskId!!) }
        assertThat(second.currentAssignees).containsExactly(alice)
        assertThat(excluded(started.instanceId, "board")).containsExactly(bob.toString() to "SOD-2")

        act(admin) { settings.put(WorkflowSettings.ONCE_PER_ROUND, "false") }
        val other = startOn("twice")
        val both = act(bob) { workflows.approve(other.openTaskId!!) }
        assertThat(both.currentAssignees).containsExactlyInAnyOrder(alice, bob)
    }

    @Test
    fun `a declared interest keeps a person off the item, and the fallback takes the step`() {
        val item = UUID.randomUUID()
        subject.interests[item] = setOf(bob)
        val started = act(initiator) { workflows.start(TestWorkflowSubject.TYPE, item, emptyList(), definitionKey = "single") }
        assertThat(started.currentAssignees).containsExactly(carol)
        assertThat(excluded(started.instanceId, "gm")).containsExactly(bob.toString() to "SOD-4")
    }

    @Test
    fun `a holder who stands aside is replaced, gives a reason, and is never asked again`() {
        val started = startOn("single")
        assertThatThrownBy { act(bob) { handOns.recuse(started.openTaskId!!, " ") } }
            .isInstanceOfSatisfying(WorkflowRefusedException::class.java) { assertThat(it.reason).isEqualTo("REMARK_REQUIRED") }
        val recused = act(bob) { handOns.recuse(started.openTaskId!!, "My brother runs the borrower") }
        assertThat(recused.currentAssignees).containsExactly(carol)
        assertThat(actions(started.instanceId)).contains("RECUSE")
        Audit.assertAudited(tenant, "workflow.task.recused", started.openTaskId.toString(), bob.toString())

        // On a shared step, the other candidates carry on without them.
        val shared = startOn("twice").let { act(bob) { workflows.approve(it.openTaskId!!) } }
        act(admin) { settings.put(WorkflowSettings.ONCE_PER_ROUND, "false") }
        val again = startOn("twice").let { act(bob) { workflows.approve(it.openTaskId!!) } }
        assertThat(again.currentAssignees).containsExactlyInAnyOrder(alice, bob)
        val left = act(bob) { handOns.recuse(taskOf(again.instanceId, bob), "Related party") }
        assertThat(left.currentAssignees).containsExactly(alice)
        assertThat(shared.currentAssignees).containsExactly(alice)
    }

    @Test
    fun `a step that runs again after a send-back goes back to the person who decided it`() {
        publish(
            "loop",
            """{"schemaVersion":2,"start":"a","steps":[
              {"id":"a","type":"approve","assignee":{"kind":"person","userId":"$alice"},"next":"b"},
              {"id":"b","type":"approve","assignee":{"kind":"position","positionId":"$gm"},"next":"c"},
              {"id":"c","type":"approve","assignee":{"kind":"person","userId":"$carol"},
               "actions":["APPROVE","RETURN","REJECT","SEND_BACK"],"next":"end"}]}""",
        )
        val started = startOn("loop")
        val atB = act(alice) { workflows.approve(started.openTaskId!!) }
        val atC = act(bob) { workflows.approve(atB.openTaskId!!) }
        val firstStep = stepIdOf(started.instanceId, "a")
        val back = act(carol) { handOns.sendBack(atC.openTaskId!!, firstStep, "Check the amount") }
        val atBAgain = act(alice) { workflows.approve(back.openTaskId!!) }
        assertThat(atBAgain.currentAssignees).containsExactly(bob)
    }

    private fun publish(
        key: String,
        body: String,
    ) {
        val draft = act(admin) { lifecycle.create(key, key, null, false, json.readTree(body)) }
        act(admin) { lifecycle.submit(key, 1, draft.version.revision) }
        act(UUID.randomUUID()) { lifecycle.approve(key, 1, null) }
    }

    private fun startOn(key: String) =
        act(initiator) { workflows.start(TestWorkflowSubject.TYPE, UUID.randomUUID(), emptyList(), definitionKey = key) }

    @Suppress("UNCHECKED_CAST")
    private fun excluded(
        instanceId: UUID,
        node: String,
    ): List<Pair<String, String>> =
        act(admin) {
            val resolution =
                tenantDsl
                    .current()
                    .select(WORKFLOW_STEP.RESOLUTION)
                    .from(WORKFLOW_STEP)
                    .where(WORKFLOW_STEP.INSTANCE_ID.eq(instanceId), WORKFLOW_STEP.NODE_ID.eq(node))
                    .fetchSingle()
                    .value1()!!
            val map = json.readValue(resolution.data(), Map::class.java) as Map<String, Any?>
            (map["excluded"] as List<Map<String, String>>).map { it.getValue("person") to it.getValue("rule") }
        }

    private fun stepIdOf(
        instanceId: UUID,
        node: String,
    ): UUID =
        act(admin) {
            tenantDsl
                .current()
                .select(WORKFLOW_STEP.ID)
                .from(WORKFLOW_STEP)
                .where(WORKFLOW_STEP.INSTANCE_ID.eq(instanceId), WORKFLOW_STEP.NODE_ID.eq(node))
                .fetchSingle(WORKFLOW_STEP.ID)!!
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

    private fun actions(instanceId: UUID): List<String> =
        act(admin) {
            tenantDsl
                .current()
                .select(WORKFLOW_ACTION.KIND)
                .from(WORKFLOW_ACTION)
                .where(WORKFLOW_ACTION.INSTANCE_ID.eq(instanceId))
                .fetch(WORKFLOW_ACTION.KIND)
                .filterNotNull()
        }

    private fun <T> act(
        user: UUID,
        block: () -> T,
    ): T = ActorContext.call(ActorScope(Actor.User(user), null)) { transactions.inTenant(tenant, block) }
}
