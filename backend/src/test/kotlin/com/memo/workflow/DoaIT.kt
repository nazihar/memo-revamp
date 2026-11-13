package com.memo.workflow

import com.memo.common.context.Actor
import com.memo.common.context.ActorContext
import com.memo.common.context.ActorScope
import com.memo.common.tenant.TenantDsl
import com.memo.common.tenant.TenantId
import com.memo.common.tenant.TenantTransactions
import com.memo.common.web.RefusedException
import com.memo.jooq.tables.references.WORKFLOW_DOA_VERSION
import com.memo.org.OrgDirectory
import com.memo.org.OrgSeeding
import com.memo.tenancy.TenantRegistry
import com.memo.testsupport.Audit
import com.memo.testsupport.IntegrationTest
import com.memo.testsupport.TestWorkflowSubject
import com.memo.testsupport.aTenant
import com.memo.workflow.internal.DefinitionLifecycle
import com.memo.workflow.internal.DoaInvalidException
import com.memo.workflow.internal.DoaLifecycle
import com.memo.workflow.internal.DoaMatcher
import com.memo.workflow.internal.DoaMember
import com.memo.workflow.internal.DoaRule
import com.memo.workflow.internal.DoaRuleGroup
import com.memo.workflow.internal.LoadedDoa
import com.memo.workflow.internal.VersionState
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import tools.jackson.databind.json.JsonMapper
import java.time.LocalDate
import java.util.UUID

/**
 * The delegation of authority (design E04 §3, §4.3, test plan T6): versions through maker-checker with an effective
 * date; the first matching rule's groups approve in order, each with its count; an instance keeps the version in force
 * when it started; the simulator answers who would approve. Audited.
 */
class DoaIT : IntegrationTest() {
    @Autowired lateinit var workflows: Workflows

    @Autowired lateinit var queries: WorkflowQueries

    @Autowired lateinit var doa: DoaLifecycle

    @Autowired lateinit var matcher: DoaMatcher

    @Autowired lateinit var definitions: DefinitionLifecycle

    @Autowired lateinit var seeding: OrgSeeding

    @Autowired lateinit var directory: OrgDirectory

    @Autowired lateinit var transactions: TenantTransactions

    @Autowired lateinit var registry: TenantRegistry

    @Autowired lateinit var tenantDsl: TenantDsl

    @Autowired lateinit var subject: TestWorkflowSubject

    @Autowired lateinit var json: JsonMapper

    private var tenant = TenantId(UUID(0, 0))
    private val start = LocalDate.of(2026, 1, 1)
    private val entity = UUID.randomUUID()
    private val unit = UUID.randomUUID()
    private val dmdPosition = UUID.randomUUID()
    private val amdPosition = UUID.randomUUID()
    private val ceoPosition = UUID.randomUUID()
    private val dmd = UUID.randomUUID()
    private val amd = UUID.randomUUID()
    private val ceo = UUID.randomUUID()
    private val initiator = UUID.randomUUID()
    private val maker = UUID.randomUUID()
    private val checker = UUID.randomUUID()

    @BeforeEach
    fun organisation() {
        tenant = registry.aTenant("doa")
        act(maker) {
            seeding.legalEntity(entity, "Bank", start)
            seeding.unit(unit, entity, null, "CR", "Credit", start)
            seeding.position(dmdPosition, unit, "DMD", head = false, start)
            seeding.position(amdPosition, unit, "AMD", head = false, start)
            seeding.position(ceoPosition, unit, "CEO", head = true, start)
            seeding.assignment(dmdPosition, dmd, start)
            seeding.assignment(amdPosition, amd, start)
            seeding.assignment(ceoPosition, ceo, start)
        }
        val body = """{"schemaVersion":2,"start":"doa","steps":[{"id":"doa","type":"doa","name":"Authority","next":"end"}]}"""
        val draft = act(maker) { definitions.create("doa", "DoA", null, false, json.readTree(body)) }
        act(maker) { definitions.submit("doa", 1, draft.version.revision) }
        act(checker) { definitions.approve("doa", 1, null) }
    }

    @Test
    fun `a published version routes items through its groups in order, and is maker-checked`() {
        val today = act(maker) { directory.today() }
        val draft = act(maker) { doa.create(today, listOf(large(), anything())) }
        Audit.assertAudited(tenant, "workflow.doa.created", draft.version.id.toString(), maker.toString())
        val changed = act(maker) { doa.replace(1, draft.version.revision, today, listOf(large(), anything())) }
        Audit.assertAudited(tenant, "workflow.doa.draft_updated", draft.version.id.toString(), maker.toString())
        act(maker) { doa.submit(1, changed.version.revision) }
        assertThatThrownBy { act(maker) { doa.approve(1, null) } }
            .isInstanceOfSatisfying(RefusedException::class.java) { assertThat(it.reason).isEqualTo("SAME_PERSON") }
        act(checker) { doa.sendBack(1, "Check the CEO group") }
        Audit.assertAudited(tenant, "workflow.doa.sent_back", draft.version.id.toString(), checker.toString())
        publish(1)

        val item = startWith(mapOf("amount" to 5_000_000))
        val first = view(item)
        assertThat(first.currentAssignees).containsExactlyInAnyOrder(dmd, amd)
        act(dmd) { workflows.approve(taskOf(item, dmd)) }
        assertThat(view(item).currentAssignees).containsExactly(amd)
        act(amd) { workflows.approve(taskOf(item, amd)) }
        assertThat(view(item).currentAssignees).containsExactly(ceo)
        act(ceo) { workflows.approve(taskOf(item, ceo)) }
        assertThat(view(item).status).isEqualTo(InstanceStatus.APPROVED)

        // A small item matches the second rule: the CEO alone.
        assertThat(view(startWith(mapOf("amount" to 10))).currentAssignees).containsExactly(ceo)
    }

    @Test
    fun `an instance keeps the version in force when it started`() {
        val today = act(maker) { directory.today() }
        act(maker) { doa.create(today, listOf(large())) }.also { submitAndPublish(it) }
        val before = startWith(mapOf("amount" to 5_000_000))

        // Version 1 took effect earlier; version 2, needing the CEO only, takes effect today.
        act(maker) {
            tenantDsl
                .current()
                .update(WORKFLOW_DOA_VERSION)
                .set(WORKFLOW_DOA_VERSION.EFFECTIVE_FROM, today.minusDays(30))
                .where(WORKFLOW_DOA_VERSION.NUMBER.eq(1))
                .execute()
        }
        act(maker) { doa.create(today, listOf(anything())) }.also { submitAndPublish(it) }
        val after = startWith(mapOf("amount" to 5_000_000))

        assertThat(view(before).currentAssignees).containsExactlyInAnyOrder(dmd, amd)
        assertThat(view(after).currentAssignees).containsExactly(ceo)

        val simulated = act(checker) { matcher.simulate(null, TestWorkflowSubject.TYPE, mapOf("amount" to 1), initiator) }
        assertThat(simulated.version).isEqualTo(2)
        assertThat(simulated.groups).containsExactly(DoaPreviewGroup(1, listOf(ceo)))
        val old = act(checker) { matcher.simulate(1, TestWorkflowSubject.TYPE, mapOf("amount" to 5_000_000), initiator) }
        assertThat(old.ruleName).isEqualTo("Large credit")
        assertThat(old.groups.map { it.needed }).containsExactly(2, 1)
    }

    @Test
    fun `a version that names unknown positions or a bad condition is refused, and an item without a DoA waits`() {
        val bad =
            DoaRule(
                name = "Broken",
                subjectType = TestWorkflowSubject.TYPE,
                condition = "item.amount >",
                groups = listOf(DoaRuleGroup(1, listOf(DoaMember("position", positionId = UUID.randomUUID())))),
            )
        assertThatThrownBy { act(maker) { doa.create(null, listOf(bad)) } }
            .isInstanceOfSatisfying(DoaInvalidException::class.java) { e ->
                assertThat(e.problems.map { it.code }).contains("CONDITION_INVALID", "UNKNOWN_POSITION")
            }

        val waiting = view(startWith(emptyMap()))
        assertThat(waiting.openTaskBlocked).isEqualTo(BlockedReason.UNRESOLVED)
    }

    private fun large() =
        DoaRule(
            name = "Large credit",
            subjectType = TestWorkflowSubject.TYPE,
            condition = "item.amount > 1000000",
            groups =
                listOf(
                    DoaRuleGroup(
                        2,
                        listOf(DoaMember("position", positionId = dmdPosition), DoaMember("position", positionId = amdPosition)),
                    ),
                    DoaRuleGroup(1, listOf(DoaMember("position", positionId = ceoPosition))),
                ),
        )

    private fun anything() =
        DoaRule(
            name = "Anything else",
            subjectType = TestWorkflowSubject.TYPE,
            groups = listOf(DoaRuleGroup(1, listOf(DoaMember("position", positionId = ceoPosition)))),
        )

    private fun submitAndPublish(draft: LoadedDoa) {
        act(maker) { doa.submit(draft.version.number, draft.version.revision) }
        publish(draft.version.number)
    }

    private fun publish(number: Int) {
        if (act(maker) { doa.get(number) }.version.state == VersionState.DRAFT) {
            act(maker) { doa.submit(number, doa.get(number).version.revision) }
        }
        val published = act(checker) { doa.approve(number, null) }
        Audit.assertAudited(tenant, "workflow.doa.published", published.version.id.toString(), checker.toString())
        Audit.assertAudited(tenant, "workflow.doa.submitted", published.version.id.toString(), maker.toString())
    }

    private fun startWith(values: Map<String, Any?>): UUID {
        val item = UUID.randomUUID()
        subject.values[item] = values
        act(initiator) { workflows.start(TestWorkflowSubject.TYPE, item, emptyList(), definitionKey = "doa") }
        return item
    }

    private fun view(item: UUID): WorkflowView = act(initiator) { checkNotNull(queries.viewOf(TestWorkflowSubject.TYPE, item)) }

    private fun taskOf(
        item: UUID,
        person: UUID,
    ): UUID = act(person) { checkNotNull(queries.myTask(TestWorkflowSubject.TYPE, item, person)).id }

    private fun <T> act(
        user: UUID,
        block: () -> T,
    ): T = ActorContext.call(ActorScope(Actor.User(user), null)) { transactions.inTenant(tenant, block) }
}
