package com.memo.workflow

import com.memo.common.context.Actor
import com.memo.common.context.ActorContext
import com.memo.common.context.ActorScope
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantDsl
import com.memo.jooq.tables.references.WORKFLOW_ACTION
import com.memo.jooq.tables.references.WORKFLOW_TASK
import com.memo.org.OrgDirectory
import com.memo.org.OrgSeeding
import com.memo.org.internal.Assignments
import com.memo.org.internal.Period
import com.memo.testsupport.MemoIntegrationTest
import com.memo.testsupport.TestSlotSubject
import com.memo.testsupport.TestWorkflowSubject
import com.memo.testsupport.WorkflowEventRecorder
import com.memo.testsupport.aPassword
import com.memo.testsupport.anActiveUser
import com.memo.workflow.internal.DefinitionInvalidException
import com.memo.workflow.internal.DefinitionLifecycle
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Steps on people the item names (ADR-0068, E07 test plan T1 `SubjectSlotIT`), on the test subject `test.slots`: a
 * person; a position resolved to its holder and re-resolved when the holder changes, keeping the due time; a pool with
 * claim and release; SOD-2 in the pool; SOD-4; the initiator taking part or not; the fallback when a slot is empty; a
 * bound definition refused for another subject type; and `WorkflowTaskCreated.nodeId`.
 */
class SubjectSlotIT : MemoIntegrationTest() {
    @Autowired lateinit var workflows: Workflows

    @Autowired lateinit var handOns: WorkflowHandOns

    @Autowired lateinit var queries: WorkflowQueries

    @Autowired lateinit var lifecycle: DefinitionLifecycle

    @Autowired lateinit var seeding: OrgSeeding

    @Autowired lateinit var assignments: Assignments

    @Autowired lateinit var directory: OrgDirectory

    @Autowired lateinit var tenantDsl: TenantDsl

    @Autowired lateinit var recorder: WorkflowEventRecorder

    @Autowired lateinit var subject: TestSlotSubject

    private val start = LocalDate.of(2026, 1, 1)
    private val entity = UUID.randomUUID()
    private val unit = UUID.randomUUID()
    private val treasury = UUID.randomUUID()
    private val secretary = UUID.randomUUID()
    private val alice = UUID.randomUUID()
    private val bob = UUID.randomUUID()
    private val carol = UUID.randomUUID()
    private val dave = UUID.randomUUID()

    @AfterEach
    fun consent() {
        subject.initiatorTakesPart = true
    }

    @Test
    fun `a slot naming a person gives them the task, and the event names its step`() {
        val key = publish()
        val item = item(owner = listOf(SlotHolder.Person(alice)))
        val started = act(secretary) { workflows.start(TestSlotSubject.TYPE, item, emptyList(), definitionKey = key) }
        assertThat(started.currentAssignees).containsExactly(alice)
        val created = recorder.of(item).filterIsInstance<WorkflowTaskCreated>().single()
        assertThat(created.nodeId).isEqualTo("owner")
        assertThat(created.assigneeId).isEqualTo(alice)
    }

    @Test
    fun `a slot naming a position follows its holder, with the same due time`() {
        val holder = person()
        val next = person()
        organisation(holder.id)
        val key = publish(sla = ""","sla":{"from":"subject","workingTime":false}""")
        val item = item(owner = listOf(SlotHolder.Position(treasury)))
        val due = Instant.now().plus(30, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS)
        subject.dues[item to "owner"] = due
        val started = act(secretary) { workflows.start(TestSlotSubject.TYPE, item, emptyList(), definitionKey = key) }
        assertThat(started.currentAssignees).containsExactly(holder.id)
        assertThat(dueOf(started.instanceId)).containsExactly(due)

        asUser(admin) {
            val current = assignments.get(UUID.nameUUIDFromBytes("assignment:$treasury".toByteArray()))
            assignments.cancel(current.id, current.version)
        }
        assertThat(view(item).openTaskBlocked).isEqualTo(BlockedReason.UNRESOLVED)
        asUser(admin) { assignments.assign(treasury, next.id, Period(directory.today(), null)) }
        assertThat(view(item).currentAssignees).containsExactly(next.id)
        assertThat(dueOf(started.instanceId)).containsExactly(due)
        assertThat(reasons(started.instanceId)).containsExactly("HOLDER_CHANGED", "HOLDER_CHANGED")
    }

    @Test
    fun `a slot naming several people is a pool, where one claims, releases, and another decides`() {
        val key = publish()
        val item = item(owner = listOf(SlotHolder.Person(alice), SlotHolder.Person(bob), SlotHolder.Person(carol)))
        val started = act(secretary) { workflows.start(TestSlotSubject.TYPE, item, emptyList(), definitionKey = key) }
        assertThat(started.currentAssignees).containsExactly(alice, bob, carol)

        val claimed = act(bob) { handOns.claim(taskOf(started.instanceId, bob)) }
        assertThat(claimed.currentAssignees).containsExactly(bob)
        assertThatThrownBy { act(alice) { workflows.approve(taskOf(started.instanceId, alice)) } }
            .isInstanceOf(WorkflowRefusedException::class.java)
            .hasMessageContaining("CLAIMED_BY_OTHER")
        act(bob) { handOns.release(taskOf(started.instanceId, bob)) }
        val decided = act(carol) { workflows.approve(taskOf(started.instanceId, carol)) }
        assertThat(decided.currentAssignees).containsExactly(dave)
    }

    @Test
    fun `whoever recommended is left out of the next pool (SOD-2), and so is anyone with an interest (SOD-4)`() {
        val key = publish()
        val item =
            item(
                owner = listOf(SlotHolder.Person(alice)),
                verifier = listOf(SlotHolder.Person(alice), SlotHolder.Person(bob), SlotHolder.Person(carol)),
            )
        subject.interests[item] = setOf(carol)
        val started = act(secretary) { workflows.start(TestSlotSubject.TYPE, item, emptyList(), definitionKey = key) }
        val verify = act(alice) { workflows.approve(checkNotNull(started.openTaskId)) }
        assertThat(verify.currentAssignees).containsExactly(bob)
    }

    @Test
    fun `the initiator takes part when the subject allows it, and is left out when it does not`() {
        val key = publish()
        val mine =
            item(owner = listOf(SlotHolder.Person(secretary)), verifier = listOf(SlotHolder.Person(secretary), SlotHolder.Person(bob)))
        val started = act(secretary) { workflows.start(TestSlotSubject.TYPE, mine, emptyList(), definitionKey = key) }
        assertThat(started.currentAssignees).containsExactly(secretary)
        // The initiator owned it; SOD-2 keeps them off its verification.
        assertThat(act(secretary) { workflows.approve(checkNotNull(started.openTaskId)) }.currentAssignees).containsExactly(bob)

        subject.initiatorTakesPart = false
        val other = item(owner = listOf(SlotHolder.Person(secretary), SlotHolder.Person(alice)))
        val refused = act(secretary) { workflows.start(TestSlotSubject.TYPE, other, emptyList(), definitionKey = key) }
        assertThat(refused.currentAssignees).containsExactly(alice)
    }

    @Test
    fun `an empty slot goes to the step's fallback`() {
        val key = publish(fallback = ""","fallback":{"kind":"person","userId":"$carol"}""")
        val item = item(owner = emptyList())
        val started = act(secretary) { workflows.start(TestSlotSubject.TYPE, item, emptyList(), definitionKey = key) }
        assertThat(started.currentAssignees).containsExactly(carol)
    }

    @Test
    fun `a bound definition starts only for its subject type, and the subject's own rules refuse a bad one`() {
        val key = publish()
        assertThatThrownBy {
            act(
                secretary,
            ) { workflows.start(TestWorkflowSubject.TYPE, UUID.randomUUID(), emptyList(), definitionKey = key) }
        }.isInstanceOf(WorkflowRefusedException::class.java)
            .hasMessageContaining("WRONG_SUBJECT")

        val rejecting =
            body().replace(""""actions":["APPROVE","SEND_BACK"]""", """"actions":["APPROVE","SEND_BACK","REJECT"]""")
        val draft = asUser(admin) { lifecycle.create("rejecting", "Rejecting", null, false, json.readTree(rejecting)) }
        assertThatThrownBy { asUser(admin) { lifecycle.submit("rejecting", 1, draft.version.revision) } }
            .isInstanceOfSatisfying(DefinitionInvalidException::class.java) { ex ->
                assertThat(ex.problems.map { it.code }).containsExactly(TestSlotSubject.NO_REJECT)
            }
    }

    private fun item(
        owner: List<SlotHolder>,
        verifier: List<SlotHolder> = listOf(SlotHolder.Person(dave)),
    ): UUID {
        val item = UUID.randomUUID()
        subject.holders[item to TestSlotSubject.OWNER] = owner
        subject.holders[item to TestSlotSubject.VERIFIER] = verifier
        return item
    }

    private fun body(
        sla: String = "",
        fallback: String = "",
    ): String =
        """{"schemaVersion":2,"subject":"${TestSlotSubject.TYPE}","start":"owner","steps":[
          {"id":"owner","type":"review","name":"Owner","assignee":{"kind":"subject","slot":"owner"}$fallback,
           "actions":["RECOMMEND"]$sla,"next":"verify"},
          {"id":"verify","type":"approve","name":"Verification","assignee":{"kind":"subject","slot":"verifier"},
           "actions":["APPROVE","SEND_BACK"],"remarks":["SEND_BACK"],"next":"end"}]}"""

    private fun publish(
        sla: String = "",
        fallback: String = "",
    ): String {
        val key = "slots-" + UUID.randomUUID().toString().take(8)
        val draft = asUser(admin) { lifecycle.create(key, "Slots", null, false, json.readTree(body(sla, fallback))) }
        asUser(admin) { lifecycle.submit(key, 1, draft.version.revision) }
        val checker = accounts.anActiveUser(transactions, tenant, aPassword(), tenantAdmin = true)
        asUser(checker) { lifecycle.approve(key, 1, null) }
        return key
    }

    private fun organisation(holder: UUID) {
        asUser(admin) {
            seeding.legalEntity(entity, "Bank", start)
            seeding.unit(unit, entity, null, "TR", "Treasury", start)
            seeding.position(treasury, unit, "Head of Treasury", head = true, start)
            seeding.assignment(treasury, holder, start)
        }
    }

    private fun view(item: UUID): WorkflowView = asUser(admin) { checkNotNull(queries.viewOf(TestSlotSubject.TYPE, item)) }

    private fun taskOf(
        instanceId: UUID,
        person: UUID,
    ): UUID =
        asUser(admin) {
            tenantDsl
                .current()
                .select(WORKFLOW_TASK.ID)
                .from(WORKFLOW_TASK)
                .where(WORKFLOW_TASK.INSTANCE_ID.eq(instanceId), WORKFLOW_TASK.ASSIGNEE_ID.eq(person), WORKFLOW_TASK.STATE.eq("OPEN"))
                .fetchSingle(WORKFLOW_TASK.ID)!!
        }

    private fun dueOf(instanceId: UUID): List<Instant> =
        asUser(admin) {
            tenantDsl
                .current()
                .select(WORKFLOW_TASK.DUE_AT)
                .from(WORKFLOW_TASK)
                .where(WORKFLOW_TASK.INSTANCE_ID.eq(instanceId), WORKFLOW_TASK.STATE.eq("OPEN"))
                .fetch(WORKFLOW_TASK.DUE_AT)
                .map { it!!.toInstant() }
        }

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
