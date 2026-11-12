package com.memo.workflow

import com.memo.common.context.Actor
import com.memo.common.context.ActorContext
import com.memo.common.context.ActorScope
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantDsl
import com.memo.identity.UserLifecycle
import com.memo.jooq.tables.references.WORKFLOW_ACTION
import com.memo.org.OrgDirectory
import com.memo.org.OrgSeeding
import com.memo.org.internal.Assignments
import com.memo.org.internal.Period
import com.memo.testsupport.Audit
import com.memo.testsupport.MemoIntegrationTest
import com.memo.testsupport.TestWorkflowSubject
import com.memo.testsupport.WorkflowEventRecorder
import com.memo.testsupport.aPassword
import com.memo.testsupport.anActiveUser
import com.memo.workflow.internal.DefinitionLifecycle
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.LocalDate
import java.util.UUID

/**
 * Staff changes while items are in flight (design E04 §4.6, test plan T5): when a position's holder changes, the step
 * resolved from it moves to the new holder (vacant in between); when a person is deactivated, their open tasks wait for
 * reassignment and the administrators are told. Recorded as reassignments by the administrator who made the change.
 */
class StaffChangeIT : MemoIntegrationTest() {
    @Autowired lateinit var workflows: Workflows

    @Autowired lateinit var queries: WorkflowQueries

    @Autowired lateinit var lifecycle: DefinitionLifecycle

    @Autowired lateinit var seeding: OrgSeeding

    @Autowired lateinit var assignments: Assignments

    @Autowired lateinit var directory: OrgDirectory

    @Autowired lateinit var userLifecycle: UserLifecycle

    @Autowired lateinit var tenantDsl: TenantDsl

    @Autowired lateinit var recorder: WorkflowEventRecorder

    private val start = LocalDate.of(2026, 1, 1)
    private val entity = UUID.randomUUID()
    private val unit = UUID.randomUUID()
    private val gm = UUID.randomUUID()

    @Test
    fun `a step resolved from a position follows its holder`() {
        val bob = person()
        val dave = person()
        organisation(bob.id)
        publish(""""assignee":{"kind":"position","positionId":"$gm"}""")
        val item = UUID.randomUUID()
        val started = act(UUID.randomUUID()) { workflows.start(TestWorkflowSubject.TYPE, item, emptyList(), definitionKey = "gm") }
        assertThat(started.currentAssignees).containsExactly(bob.id)

        asUser(admin) {
            val bobs = assignments.get(UUID.nameUUIDFromBytes("assignment:$gm".toByteArray()))
            assignments.cancel(bobs.id, bobs.version)
        }
        assertThat(view(item).openTaskBlocked).isEqualTo(BlockedReason.UNRESOLVED)
        asUser(admin) { assignments.assign(gm, dave.id, Period(directory.today(), null)) }
        assertThat(view(item).currentAssignees).containsExactly(dave.id)
        assertThat(reasons(started.instanceId)).containsExactly("HOLDER_CHANGED", "HOLDER_CHANGED")
    }

    @Test
    fun `a deactivated holder's task waits for reassignment, and the administrators are told`() {
        val carol = person()
        organisation(null)
        publish(""""assignee":{"kind":"person","userId":"${carol.id}"}""")
        val item = UUID.randomUUID()
        act(UUID.randomUUID()) { workflows.start(TestWorkflowSubject.TYPE, item, emptyList(), definitionKey = "gm") }

        asUser(admin) { userLifecycle.deactivate(carol.account.id, carol.account.version, admin.id) }
        val blocked = view(item)
        assertThat(blocked.openTaskBlocked).isEqualTo(BlockedReason.INACTIVE)
        val created = recorder.of(item).filterIsInstance<WorkflowTaskCreated>()
        assertThat(created.last().blocked).isEqualTo(BlockedReason.INACTIVE)
        assertThat(reasons(blocked.instanceId)).containsExactly("INACTIVE")
        val audited = Audit.entries(tenant.id).filter { it.action == "workflow.task.reassigned" }
        assertThat(audited.last().canonical).contains("\"cause\":\"INACTIVE\"")
        assertThat(audited.last().actorId).isEqualTo(admin.id.value.toString())
    }

    private fun organisation(holder: UUID?) {
        asUser(admin) {
            seeding.legalEntity(entity, "Bank", start)
            seeding.unit(unit, entity, null, "CR", "Credit", start)
            seeding.position(gm, unit, "GM Credit", head = true, start)
            holder?.let { seeding.assignment(gm, it, start) }
        }
    }

    private fun publish(assignee: String) {
        val body = """{"schemaVersion":2,"start":"gm","steps":[{"id":"gm","type":"approve","name":"GM",$assignee,"next":"end"}]}"""
        val draft = asUser(admin) { lifecycle.create("gm", "GM", null, false, json.readTree(body)) }
        asUser(admin) { lifecycle.submit("gm", 1, draft.version.revision) }
        val checker = accounts.anActiveUser(transactions, tenant, aPassword(), tenantAdmin = true)
        asUser(checker) { lifecycle.approve("gm", 1, null) }
    }

    private fun view(item: UUID): WorkflowView = asUser(admin) { checkNotNull(queries.viewOf(TestWorkflowSubject.TYPE, item)) }

    private fun reasons(instanceId: UUID): List<String> =
        asUser(admin) {
            tenantDsl
                .current()
                .select(WORKFLOW_ACTION.DETAILS)
                .from(WORKFLOW_ACTION)
                .where(WORKFLOW_ACTION.INSTANCE_ID.eq(instanceId), WORKFLOW_ACTION.KIND.eq(ActionKind.REASSIGN.name))
                .orderBy(WORKFLOW_ACTION.SEQ)
                .fetch { json.readTree(it.value1()!!.data())["reason"].asString() }
        }

    private fun <T> act(
        user: UUID,
        block: () -> T,
    ): T =
        ActorContext.call(ActorScope(Actor.User(user), null)) { TenantContext.call(tenant.id) { transactions.inTenant(tenant.id, block) } }
}
