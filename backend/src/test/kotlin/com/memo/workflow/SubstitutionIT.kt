package com.memo.workflow

import com.memo.common.context.Actor
import com.memo.common.context.ActorContext
import com.memo.common.context.ActorScope
import com.memo.common.tenant.TenantDsl
import com.memo.common.tenant.TenantId
import com.memo.common.tenant.TenantTransactions
import com.memo.common.web.RefusedException
import com.memo.jooq.tables.references.WORKFLOW_ACTION
import com.memo.org.OrgDirectory
import com.memo.org.OrgSeeding
import com.memo.tenancy.TenantRegistry
import com.memo.testsupport.Audit
import com.memo.testsupport.IntegrationTest
import com.memo.testsupport.TestWorkflowSubject
import com.memo.testsupport.aTenant
import com.memo.workflow.internal.DefinitionLifecycle
import com.memo.workflow.internal.Substitutions
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import tools.jackson.databind.json.JsonMapper
import java.time.LocalDate
import java.util.UUID

/**
 * Substitutions (design E04 §4.5, test plan T5): while one is in force, new tasks for the principal go to the
 * substitute, whose actions record acting for the principal; never for an item the substitute initiated (SOD-3); one
 * per principal at a time; and an administrator moves the principal's open tasks to the substitute.
 */
class SubstitutionIT : IntegrationTest() {
    @Autowired lateinit var workflows: Workflows

    @Autowired lateinit var queries: WorkflowQueries

    @Autowired lateinit var substitutions: Substitutions

    @Autowired lateinit var lifecycle: DefinitionLifecycle

    @Autowired lateinit var seeding: OrgSeeding

    @Autowired lateinit var directory: OrgDirectory

    @Autowired lateinit var transactions: TenantTransactions

    @Autowired lateinit var registry: TenantRegistry

    @Autowired lateinit var tenantDsl: TenantDsl

    @Autowired lateinit var json: JsonMapper

    private var tenant = TenantId(UUID(0, 0))
    private val start = LocalDate.of(2026, 1, 1)
    private val entity = UUID.randomUUID()
    private val unit = UUID.randomUUID()
    private val gm = UUID.randomUUID()
    private val bob = UUID.randomUUID()
    private val carol = UUID.randomUUID()
    private val initiator = UUID.randomUUID()
    private val admin = UUID.randomUUID()

    @BeforeEach
    fun organisation() {
        tenant = registry.aTenant("substitutions")
        act(admin) {
            seeding.legalEntity(entity, "Bank", start)
            seeding.unit(unit, entity, null, "CR", "Credit", start)
            seeding.position(gm, unit, "GM Credit", head = true, start)
            seeding.assignment(gm, bob, start)
        }
        val body =
            """{"schemaVersion":2,"start":"gm","steps":[
              {"id":"gm","type":"approve","name":"GM","assignee":{"kind":"position","positionId":"$gm"},"next":"end"}]}"""
        val draft = act(admin) { lifecycle.create("gm", "GM", null, false, json.readTree(body)) }
        act(admin) { lifecycle.submit("gm", 1, draft.version.revision) }
        act(UUID.randomUUID()) { lifecycle.approve("gm", 1, null) }
    }

    @Test
    fun `while a substitution is in force the substitute acts for the principal, but never on their own item`() {
        val today = act(admin) { directory.today() }
        val substitution = act(admin) { substitutions.create(bob, carol, today.minusDays(1), today.plusDays(7), "Annual leave") }
        Audit.assertAudited(tenant, "workflow.substitution.created", substitution.id.toString(), admin.toString())

        val started = startBy(initiator)
        assertThat(started.currentAssignees).containsExactly(carol)
        act(carol) { workflows.approve(started.openTaskId!!) }
        assertThat(actingFor(started.instanceId)).containsExactly(bob)

        val own = startBy(carol)
        assertThat(own.currentAssignees).containsExactly(bob)

        assertThatThrownBy { act(admin) { substitutions.create(bob, initiator, today, null, null) } }
            .isInstanceOfSatisfying(RefusedException::class.java) { assertThat(it.reason).isEqualTo("OVERLAP") }
        act(admin) { substitutions.end(substitution.id) }
        Audit.assertAudited(tenant, "workflow.substitution.ended", substitution.id.toString(), admin.toString())
        assertThat(startBy(initiator).currentAssignees).containsExactly(bob)
    }

    @Test
    fun `an administrator moves the principal's open tasks to the substitute`() {
        val started = startBy(initiator)
        assertThat(started.currentAssignees).containsExactly(bob)
        val today = act(admin) { directory.today() }
        val substitution = act(admin) { substitutions.create(bob, carol, today, null, "Sick leave") }
        assertThat(act(admin) { substitutions.apply(substitution.id) }).isEqualTo(1)
        Audit.assertAudited(tenant, "workflow.substitution.applied", substitution.id.toString(), admin.toString())

        val view = act(initiator) { checkNotNull(queries.viewOf(TestWorkflowSubject.TYPE, started.subjectId)) }
        assertThat(view.currentAssignees).containsExactly(carol)
        act(carol) { workflows.approve(view.openTaskId!!) }
        assertThat(actingFor(started.instanceId)).containsExactly(bob)
    }

    private fun startBy(person: UUID): WorkflowView =
        act(person) { workflows.start(TestWorkflowSubject.TYPE, UUID.randomUUID(), emptyList(), definitionKey = "gm") }

    private fun actingFor(instanceId: UUID): List<UUID> =
        act(admin) {
            tenantDsl
                .current()
                .select(WORKFLOW_ACTION.ACTING_FOR_ID)
                .from(WORKFLOW_ACTION)
                .where(WORKFLOW_ACTION.INSTANCE_ID.eq(instanceId), WORKFLOW_ACTION.KIND.eq(ActionKind.APPROVE.name))
                .fetch(WORKFLOW_ACTION.ACTING_FOR_ID)
                .filterNotNull()
        }

    private fun <T> act(
        user: UUID,
        block: () -> T,
    ): T = ActorContext.call(ActorScope(Actor.User(user), null)) { transactions.inTenant(tenant, block) }
}
