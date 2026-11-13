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
import com.memo.jooq.tables.references.WORKFLOW_STEP
import com.memo.jooq.tables.references.WORKFLOW_TASK
import com.memo.org.OrgSeeding
import com.memo.tenancy.TenantRegistry
import com.memo.testsupport.IntegrationTest
import com.memo.testsupport.TestWorkflowSubject
import com.memo.testsupport.WorkflowEventRecorder
import com.memo.testsupport.aTenant
import com.memo.workflow.internal.DefinitionInvalidException
import com.memo.workflow.internal.DefinitionLifecycle
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import tools.jackson.databind.json.JsonMapper
import java.time.LocalDate
import java.util.UUID

/**
 * Assignee resolvers over the organisation (design E04 §4.1, test plan T2): a position's holder, the initiator's
 * supervisor and unit head, a role's holders in the initiator's unit; a vacancy holds the step for an administrator; the
 * resolution is recorded on the step; unknown references are refused when the definition is written.
 */
class ResolversIT : IntegrationTest() {
    @Autowired lateinit var workflows: Workflows

    @Autowired lateinit var lifecycle: DefinitionLifecycle

    @Autowired lateinit var seeding: OrgSeeding

    @Autowired lateinit var access: AccessAdministration

    @Autowired lateinit var roles: RoleStore

    @Autowired lateinit var transactions: TenantTransactions

    @Autowired lateinit var registry: TenantRegistry

    @Autowired lateinit var tenantDsl: TenantDsl

    @Autowired lateinit var recorder: WorkflowEventRecorder

    @Autowired lateinit var json: JsonMapper

    private var tenant = TenantId(UUID(0, 0))
    private val start = LocalDate.of(2026, 1, 1)
    private val entity = UUID.randomUUID()
    private val head = UUID.randomUUID()
    private val credit = UUID.randomUUID()
    private val branch = UUID.randomUUID()
    private val md = UUID.randomUUID()
    private val gm = UUID.randomUUID()
    private val manager = UUID.randomUUID()
    private val officer = UUID.randomUUID()
    private val vacant = UUID.randomUUID()
    private val alice = UUID.randomUUID()
    private val bob = UUID.randomUUID()
    private val carol = UUID.randomUUID()
    private val initiator = UUID.randomUUID()
    private val maker = UUID.randomUUID()
    private val checker = UUID.randomUUID()
    private val admin = UUID.randomUUID()
    private var orgAdminRole = UUID(0, 0)

    @BeforeEach
    fun organisation() {
        tenant = registry.aTenant("resolvers")
        system {
            seeding.legalEntity(entity, "Bank", start)
            seeding.unit(head, entity, null, "HO", "Head office", start)
            seeding.unit(credit, entity, head, "CR", "Credit", start)
            seeding.unit(branch, entity, credit, "BR", "Branch", start)
            seeding.position(md, head, "Managing Director", head = true, start)
            seeding.position(gm, credit, "GM Credit", head = true, start)
            seeding.position(manager, branch, "Branch Manager", head = true, start)
            seeding.position(officer, branch, "Credit Officer", head = false, start)
            seeding.position(vacant, credit, "Deputy GM", head = false, start)
            seeding.assignment(md, alice, start)
            seeding.assignment(gm, bob, start)
            seeding.assignment(manager, carol, start)
            seeding.assignment(officer, initiator, start)
            seeding.reportingLine(officer, manager, start)
            seeding.reportingLine(manager, gm, start)
            seeding.reportingLine(gm, md, start)
            orgAdminRole = checkNotNull(roles.builtInRoleId(BuiltInRole.ORG_ADMIN))
            access.grantBuiltIn(alice, BuiltInRole.ORG_ADMIN, head)
            access.grantBuiltIn(carol, BuiltInRole.ORG_ADMIN, branch)
        }
    }

    @Test
    fun `a position, the supervisor one line up and the unit head two units above resolve to their holders`() {
        publish(
            "chain",
            steps(
                """{"kind":"position","positionId":"$gm"}""",
                """{"kind":"supervisor","levels":1}""",
                """{"kind":"unitHead","unit":"initiator","up":2}""",
            ),
        )
        val started = start("chain")
        assertThat(started.currentAssignees).containsExactly(bob)
        assertThat(resolution(started.instanceId).single()).containsEntry("people", listOf(bob.toString()))

        val second = act(bob) { workflows.approve(started.openTaskId!!) }
        assertThat(second.currentAssignees).containsExactly(carol)
        val third = act(carol) { workflows.approve(second.openTaskId!!) }
        assertThat(third.currentAssignees).containsExactly(alice)
        assertThat(act(alice) { workflows.approve(third.openTaskId!!) }.status).isEqualTo(InstanceStatus.APPROVED)
    }

    @Test
    fun `a role in the initiator's unit offers the step to every holder, and the first decides`() {
        publish("role", steps("""{"kind":"role","roleId":"$orgAdminRole","unit":"initiator"}"""))
        val started = start("role")
        assertThat(started.currentAssignees).containsExactlyInAnyOrder(alice, carol)
        val done = act(carol) { workflows.approve(taskOf(started.instanceId, carol)) }
        assertThat(done.status).isEqualTo(InstanceStatus.APPROVED)
    }

    @Test
    fun `a vacant position holds the step for an administrator, who assigns it`() {
        publish("vacant", steps("""{"kind":"position","positionId":"$vacant"}"""))
        val item = UUID.randomUUID()
        val started = act(initiator) { workflows.start(TestWorkflowSubject.TYPE, item, emptyList(), definitionKey = "vacant") }
        assertThat(started.currentAssignees).isEmpty()
        assertThat(started.openTaskBlocked).isEqualTo(BlockedReason.UNRESOLVED)
        assertThat(resolution(started.instanceId).single()["resolved"].toString()).contains("VACANT")
        val created = recorder.of(item).filterIsInstance<WorkflowTaskCreated>().single()
        assertThat(created.assigneeId).isNull()
        assertThat(created.blocked).isEqualTo(BlockedReason.UNRESOLVED)

        val assigned = act(admin) { workflows.reassign(started.openTaskId!!, carol, "acting deputy") }
        assertThat(assigned.currentAssignees).containsExactly(carol)
        assertThat(act(carol) { workflows.approve(assigned.openTaskId!!) }.status).isEqualTo(InstanceStatus.APPROVED)
    }

    @Test
    fun `positions, roles and units a definition names must exist`() {
        val body =
            """{"schemaVersion":2,"start":"a","steps":[
              {"id":"a","type":"approve","assignee":{"kind":"position","positionId":"${UUID.randomUUID()}"},"next":"b"},
              {"id":"b","type":"approve","assignee":{"kind":"role","roleId":"${UUID.randomUUID()}","unit":"${UUID.randomUUID()}"},"next":"end"}]}"""
        val draft = act(maker) { lifecycle.create("unknown", "Unknown", null, false, json.readTree(body)) }
        assertThat(draft.version.state.name).isEqualTo("DRAFT")
        assertThatThrownBy { act(maker) { lifecycle.submit("unknown", 1, draft.version.revision) } }
            .isInstanceOfSatisfying(DefinitionInvalidException::class.java) { invalid ->
                assertThat(invalid.problems.map { it.code }).containsExactlyInAnyOrder("UNKNOWN_POSITION", "UNKNOWN_ROLE", "UNKNOWN_UNIT")
            }
    }

    private fun steps(vararg assignees: String): String {
        val ids = assignees.indices.map { "s$it" }
        val body =
            assignees.mapIndexed { i, assignee ->
                """{"id":"${ids[i]}","type":"approve","assignee":$assignee,"next":"${ids.getOrNull(i + 1) ?: "end"}"}"""
            }
        return """{"schemaVersion":2,"start":"s0","steps":[${body.joinToString(",")}]}"""
    }

    private fun publish(
        key: String,
        body: String,
    ) {
        val draft = act(maker) { lifecycle.create(key, key, null, false, json.readTree(body)) }
        act(maker) { lifecycle.submit(key, 1, draft.version.revision) }
        act(checker) { lifecycle.approve(key, 1, null) }
    }

    private fun start(key: String) =
        act(initiator) { workflows.start(TestWorkflowSubject.TYPE, UUID.randomUUID(), emptyList(), definitionKey = key) }

    private fun taskOf(
        instanceId: UUID,
        person: UUID,
    ): UUID =
        act(person) {
            tenantDsl
                .current()
                .select(WORKFLOW_TASK.ID)
                .from(WORKFLOW_TASK)
                .where(
                    WORKFLOW_TASK.INSTANCE_ID
                        .eq(instanceId),
                    WORKFLOW_TASK.ASSIGNEE_ID
                        .eq(person),
                    WORKFLOW_TASK.STATE
                        .eq("OPEN"),
                ).fetchSingle(WORKFLOW_TASK.ID)!!
        }

    @Suppress("UNCHECKED_CAST")
    private fun resolution(instanceId: UUID): List<Map<String, Any?>> =
        act(initiator) {
            tenantDsl
                .current()
                .select(WORKFLOW_STEP.RESOLUTION)
                .from(WORKFLOW_STEP)
                .where(WORKFLOW_STEP.INSTANCE_ID.eq(instanceId))
                .orderBy(WORKFLOW_STEP.ORDINAL)
                .fetch { json.readValue(it.value1()!!.data(), Map::class.java) as Map<String, Any?> }
        }

    private fun <T> system(block: () -> T): T = ActorContext.asSystem("resolvers-test") { transactions.inTenant(tenant, block) }

    private fun <T> act(
        user: UUID,
        block: () -> T,
    ): T = ActorContext.call(ActorScope(Actor.User(user), null)) { transactions.inTenant(tenant, block) }
}
