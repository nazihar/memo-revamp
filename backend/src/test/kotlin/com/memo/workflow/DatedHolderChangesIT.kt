package com.memo.workflow

import com.memo.common.context.Actor
import com.memo.common.context.ActorContext
import com.memo.common.context.ActorScope
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantDsl
import com.memo.jooq.tables.references.WORKFLOW_ACTION
import com.memo.jooq.tables.references.WORKFLOW_TASK
import com.memo.org.OrgDirectory
import com.memo.org.OrgHolderChanges
import com.memo.org.OrgSeeding
import com.memo.org.internal.Assignments
import com.memo.org.internal.OrgCalendar
import com.memo.org.internal.Period
import com.memo.testsupport.AppRoleJdbc
import com.memo.testsupport.AppRoleJdbc.long
import com.memo.testsupport.AppRoleJdbc.update
import com.memo.testsupport.Audit
import com.memo.testsupport.MemoIntegrationTest
import com.memo.testsupport.MutableClock
import com.memo.testsupport.TestSlotSubject
import com.memo.testsupport.TestWorkflowSubject
import com.memo.testsupport.aPassword
import com.memo.testsupport.aTestTenant
import com.memo.testsupport.anActiveUser
import com.memo.testsupport.query
import com.memo.workflow.internal.DatedHolderChanges
import com.memo.workflow.internal.DefinitionLifecycle
import com.memo.workflow.internal.HolderSyncStore
import com.memo.workflow.internal.WorkflowRunner
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

/**
 * Position steps follow holder changes that take effect on a date (ADR-0068 amendment, 2026-11-28), in a tenant on
 * Asia/Dhaka, with the day's run on a pinned clock: a holder change recorded for tomorrow reaches the open task at the run
 * just after midnight in Dhaka (still the same day in UTC), in the new holder's inbox and out of the former holder's
 * hands; a term that ends with nobody after it leaves the step to an administrator or to its fallback until a successor
 * starts; a pool keeps the claim of a member still in it; a long gap is caught up; a second run the same day changes
 * nothing; and another tenant cannot read or write a tenant's sync day.
 */
class DatedHolderChangesIT : MemoIntegrationTest() {
    @Autowired lateinit var workflows: Workflows

    @Autowired lateinit var handOns: WorkflowHandOns

    @Autowired lateinit var queries: WorkflowQueries

    @Autowired lateinit var lifecycle: DefinitionLifecycle

    @Autowired lateinit var seeding: OrgSeeding

    @Autowired lateinit var assignments: Assignments

    @Autowired lateinit var directory: OrgDirectory

    @Autowired lateinit var tenantDsl: TenantDsl

    @Autowired lateinit var slots: TestSlotSubject

    @Autowired lateinit var syncStore: HolderSyncStore

    @Autowired lateinit var holderChanges: OrgHolderChanges

    @Autowired lateinit var subjects: List<WorkflowSubject>

    @Autowired lateinit var runner: WorkflowRunner

    private val clock = MutableClock()
    private val start = LocalDate.of(2026, 1, 1)
    private val entity = UUID.randomUUID()
    private val unit = UUID.randomUUID()
    private val gm = UUID.randomUUID()
    private lateinit var today: LocalDate

    @BeforeEach
    fun dhaka() {
        transactions.inTenant(tenant.id) { settings.put(OrgCalendar.TIME_ZONE, DHAKA.id) }
        today = asUser(admin) { directory.today() }
    }

    @Test
    fun `a holder change recorded for tomorrow reaches the open task at the run just after midnight in Dhaka`() {
        val bob = person()
        val dave = person()
        organisation(bob.id)
        val key = publish(""""assignee":{"kind":"position","positionId":"$gm"}""")
        val item = UUID.randomUUID()
        val started = act(UUID.randomUUID()) { workflows.start(TestWorkflowSubject.TYPE, item, emptyList(), definitionKey = key) }
        val bobsTask = checkNotNull(started.openTaskId)
        assertThat(started.currentAssignees).containsExactly(bob.id)

        // The administrator records today that Dave takes over tomorrow: nothing changes today.
        handOver(to = dave.id, on = today.plusDays(1))
        assertThat(view(item).currentAssignees).containsExactly(bob.id)

        // The last second of today in Dhaka: Bob still holds the position.
        clock.now = today.atTime(LocalTime.MAX).atZone(DHAKA).toInstant()
        assertThat(daily()).isZero()
        assertThat(view(item).currentAssignees).containsExactly(bob.id)

        // Just after midnight in Dhaka, while it is still the same day in UTC: the task is Dave's.
        clock.now = midnight(today.plusDays(1)).plusSeconds(30)
        assertThat(clock.now.atZone(ZoneOffset.UTC).toLocalDate()).isEqualTo(today)
        assertThat(daily()).isEqualTo(1)
        assertThat(view(item).currentAssignees).containsExactly(dave.id)
        assertThat(inbox(dave.id).map { it.subject.id }).containsExactly(item)
        assertThat(inbox(bob.id)).isEmpty()
        assertThatThrownBy { act(bob.id) { workflows.approve(bobsTask) } }.isInstanceOf(WorkflowRefusedException::class.java)
        assertThat(reasons(started.instanceId)).containsExactly("HOLDER_CHANGED")
        val audited = Audit.entries(tenant.id).last { it.action == "workflow.task.reassigned" }
        assertThat(audited.canonical).contains("\"cause\":\"HOLDER_CHANGED\"", dave.id.toString())
        assertThat(audited.actorId).isEqualTo("workflow-position-holders")

        // A later run the same day changes nothing.
        clock.advance(Duration.ofHours(1))
        assertThat(daily()).isZero()
        assertThat(reasons(started.instanceId)).hasSize(1)

        // Dave decides it.
        val daves = checkNotNull(inbox(dave.id).single().taskId)
        assertThat(act(dave.id) { workflows.approve(daves) }.status).isEqualTo(InstanceStatus.APPROVED)
    }

    @Test
    fun `a term that ends with nobody after it leaves the step to an administrator or to its fallback, until a successor starts`() {
        val bob = person()
        val carol = person()
        val dave = person()
        organisation(bob.id)
        val plain = publish(""""assignee":{"kind":"position","positionId":"$gm"}""")
        val backed = publish(""""assignee":{"kind":"position","positionId":"$gm"},"fallback":{"kind":"person","userId":"${carol.id}"}""")
        val first = UUID.randomUUID()
        val second = UUID.randomUUID()
        act(UUID.randomUUID()) { workflows.start(TestWorkflowSubject.TYPE, first, emptyList(), definitionKey = plain) }
        act(UUID.randomUUID()) { workflows.start(TestWorkflowSubject.TYPE, second, emptyList(), definitionKey = backed) }
        // Bob's term ends tonight; Dave starts the day after tomorrow.
        asUser(admin) {
            val bobs = assignments.get(seeded())
            assignments.changeDates(bobs.id, bobs.version, Period(bobs.period.from, today.plusDays(1)))
            assignments.assign(gm, dave.id, Period(today.plusDays(2), null))
        }
        assertThat(view(first).currentAssignees).containsExactly(bob.id)
        clock.now = today.atTime(LocalTime.NOON).atZone(DHAKA).toInstant()
        assertThat(daily()).isZero()

        // Tomorrow the position is vacant: an administrator assigns the first, the fallback holds the second.
        clock.now = midnight(today.plusDays(1)).plusSeconds(60)
        assertThat(daily()).isEqualTo(2)
        assertThat(view(first).openTaskBlocked).isEqualTo(BlockedReason.UNRESOLVED)
        assertThat(view(first).currentAssignees).isEmpty()
        assertThat(view(second).currentAssignees).containsExactly(carol.id)
        assertThat(inbox(bob.id)).isEmpty()
        assertThat(Audit.entries(tenant.id).map { it.action }).contains("workflow.task.blocked")

        // The day after, Dave's term starts: both steps are his.
        clock.now = midnight(today.plusDays(2)).plusSeconds(60)
        assertThat(daily()).isEqualTo(2)
        assertThat(view(first).currentAssignees).containsExactly(dave.id)
        assertThat(view(second).currentAssignees).containsExactly(dave.id)
        assertThat(inbox(carol.id)).isEmpty()
    }

    @Test
    fun `a pool drawn again keeps the claim of a member still in it, and the former holder leaves it`() {
        val hana = person()
        val omar = person()
        val alice = UUID.randomUUID()
        organisation(hana.id)
        val key = publishSlots()
        val item = UUID.randomUUID()
        slots.holders[item to TestSlotSubject.OWNER] = listOf(SlotHolder.Position(gm), SlotHolder.Person(alice))
        slots.holders[item to TestSlotSubject.VERIFIER] = listOf(SlotHolder.Person(UUID.randomUUID()))
        val started = act(UUID.randomUUID()) { workflows.start(TestSlotSubject.TYPE, item, emptyList(), definitionKey = key) }
        assertThat(started.currentAssignees).containsExactlyInAnyOrder(hana.id, alice)
        act(alice) { handOns.claim(openTask(started.instanceId, alice)) }
        handOver(to = omar.id, on = today.plusDays(1))

        clock.now = midnight(today.plusDays(1)).plusSeconds(30)
        assertThat(daily()).isEqualTo(1)
        val after = asUser(admin) { checkNotNull(queries.viewOf(TestSlotSubject.TYPE, item)) }
        assertThat(after.currentAssignees).containsExactly(alice)
        assertThat(openTasks(started.instanceId)).containsExactlyInAnyOrder(omar.id, alice)
        act(alice) { handOns.release(openTask(started.instanceId, alice)) }
        assertThat(asUser(admin) { checkNotNull(queries.viewOf(TestSlotSubject.TYPE, item)) }.currentAssignees)
            .containsExactlyInAnyOrder(omar.id, alice)
    }

    @Test
    fun `a run after a long gap checks every open position step`() {
        val bob = person()
        val dave = person()
        organisation(bob.id)
        val key = publish(""""assignee":{"kind":"position","positionId":"$gm"}""")
        val item = UUID.randomUUID()
        act(UUID.randomUUID()) { workflows.start(TestWorkflowSubject.TYPE, item, emptyList(), definitionKey = key) }
        handOver(to = dave.id, on = today.plusDays(35))
        clock.now = today.atTime(LocalTime.NOON).atZone(DHAKA).toInstant()
        assertThat(daily()).isZero()

        clock.now = midnight(today.plusDays(40)).plusSeconds(30)
        assertThat(daily()).isEqualTo(1)
        assertThat(view(item).currentAssignees).containsExactly(dave.id)
        assertThat(syncedOn()).isEqualTo(today.plusDays(40))
    }

    @Test
    fun `another tenant neither sees nor writes a tenant's sync day`() {
        clock.now = today.atTime(LocalTime.NOON).atZone(DHAKA).toInstant()
        daily()
        assertThat(AppRoleJdbc.inTransaction(tenant.id) { c -> c.long("select count(*) from workflow_holder_sync") }).isEqualTo(1)
        val other = registry.aTestTenant(transactions, settings, "holdersother", mfaRequired = false)
        AppRoleJdbc.inTransaction(other.id) { c ->
            assertThat(c.long("select count(*) from workflow_holder_sync where tenant_id = ?", tenant.id.value)).isZero()
            assertThat(c.update("update workflow_holder_sync set synced_on = null, synced_at = null where tenant_id = ?", tenant.id.value))
                .isZero()
        }
        val written =
            runCatching {
                AppRoleJdbc.inTransaction(
                    other.id,
                ) { c -> c.update("insert into workflow_holder_sync (tenant_id) values (?)", UUID.randomUUID()) }
            }
        assertThat(written.isFailure).isTrue()
        assertThat(syncedOn()).isEqualTo(today)
    }

    /** The day's run on the test clock, as the system task: how many steps resolved again. */
    private fun daily(): Int =
        ActorContext.asSystem("workflow-position-holders") {
            TenantContext.call(tenant.id) {
                DatedHolderChanges(tenantDsl, transactions, syncStore, directory, holderChanges, subjects, runner, json, clock).daily()
            }
        }

    private fun midnight(day: LocalDate) = day.atStartOfDay(DHAKA).toInstant()

    /** Bob's seeded assignment to the general manager's position ends on [on], and [to] holds it from then. */
    private fun handOver(
        to: UUID,
        on: LocalDate,
    ) = asUser(admin) {
        val current = assignments.get(seeded())
        assignments.changeDates(current.id, current.version, Period(current.period.from, on))
        assignments.assign(gm, to, Period(on, null))
    }

    private fun seeded() = UUID.nameUUIDFromBytes("assignment:$gm".toByteArray())

    private fun organisation(holder: UUID) {
        asUser(admin) {
            seeding.legalEntity(entity, "Bank", start)
            seeding.unit(unit, entity, null, "CR", "Credit", start)
            seeding.position(gm, unit, "GM Credit", head = true, start)
            seeding.assignment(gm, holder, start)
        }
    }

    private fun publish(assignee: String): String {
        val key = "gm-" + UUID.randomUUID().toString().take(8)
        approve(key, """{"schemaVersion":2,"start":"gm","steps":[{"id":"gm","type":"approve","name":"GM",$assignee,"next":"end"}]}""")
        return key
    }

    private fun publishSlots(): String {
        val key = "slots-" + UUID.randomUUID().toString().take(8)
        val body =
            """{"schemaVersion":2,"subject":"${TestSlotSubject.TYPE}","start":"owner","steps":[
              {"id":"owner","type":"review","name":"Owner","assignee":{"kind":"subject","slot":"owner"},
               "actions":["RECOMMEND"],"next":"verify"},
              {"id":"verify","type":"approve","name":"Verification","assignee":{"kind":"subject","slot":"verifier"},
               "actions":["APPROVE","SEND_BACK"],"remarks":["SEND_BACK"],"next":"end"}]}"""
        approve(key, body)
        return key
    }

    private fun approve(
        key: String,
        body: String,
    ) {
        val draft = asUser(admin) { lifecycle.create(key, "Dated holders", null, false, json.readTree(body)) }
        asUser(admin) { lifecycle.submit(key, 1, draft.version.revision) }
        val checker = accounts.anActiveUser(transactions, tenant, aPassword(), tenantAdmin = true)
        asUser(checker) { lifecycle.approve(key, 1, null) }
    }

    private fun view(item: UUID): WorkflowView = asUser(admin) { checkNotNull(queries.viewOf(TestWorkflowSubject.TYPE, item)) }

    private fun inbox(user: UUID): List<TaskItem> = act(user) { queries.myTasks(null, LIMIT).items }

    private fun openTasks(instanceId: UUID): List<UUID?> =
        asUser(admin) {
            tenantDsl
                .current()
                .select(WORKFLOW_TASK.ASSIGNEE_ID)
                .from(WORKFLOW_TASK)
                .where(WORKFLOW_TASK.INSTANCE_ID.eq(instanceId), WORKFLOW_TASK.STATE.eq("OPEN"))
                .fetch(WORKFLOW_TASK.ASSIGNEE_ID)
        }

    private fun openTask(
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

    private fun syncedOn(): LocalDate? =
        AppRoleJdbc.inTransaction(tenant.id) { c ->
            c.query("select synced_on from workflow_holder_sync") { it.getObject(1, LocalDate::class.java) }.singleOrNull()
        }

    private fun <T> act(
        user: UUID,
        block: () -> T,
    ): T =
        ActorContext.call(ActorScope(Actor.User(user), null)) { TenantContext.call(tenant.id) { transactions.inTenant(tenant.id, block) } }

    private companion object {
        val DHAKA: ZoneId = ZoneId.of("Asia/Dhaka")
        const val LIMIT = 20
    }
}
