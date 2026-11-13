package com.memo.memo

import com.github.kagkarlsson.scheduler.SchedulerClient
import com.github.kagkarlsson.scheduler.task.helper.OneTimeTask
import com.memo.common.context.ActorContext
import com.memo.common.crypto.DataKeys
import com.memo.common.id.IdGenerator
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantDsl
import com.memo.memo.internal.MemoTypeFields
import com.memo.memo.internal.MemoTypes
import com.memo.memo.internal.RoutePolicy
import com.memo.testsupport.AppRoleJdbc
import com.memo.testsupport.MailOutbox
import com.memo.testsupport.MemoIntegrationTest
import com.memo.testsupport.MutableClock
import com.memo.testsupport.Person
import com.memo.testsupport.aPassword
import com.memo.testsupport.anActiveUser
import com.memo.workflow.internal.Calendars
import com.memo.workflow.internal.DefinitionLifecycle
import com.memo.workflow.internal.TimerEscalations
import com.memo.workflow.internal.TimerFire
import com.memo.workflow.internal.WorkflowTimers
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.ApplicationEventPublisher
import java.time.Duration
import java.time.Instant

/**
 * SLA reminders and overdue notices on memos (design E05 §4.3, test plan T4): the approver is reminded, and when the
 * step is overdue the approver and the initiator are told, in-app and by email with the reference only.
 */
class MemoReminderIT : MemoIntegrationTest() {
    @Autowired lateinit var lifecycle: DefinitionLifecycle

    @Autowired lateinit var types: MemoTypes

    @Autowired lateinit var tenantDsl: TenantDsl

    @Autowired lateinit var ids: IdGenerator

    @Autowired lateinit var calendars: Calendars

    @Autowired lateinit var events: ApplicationEventPublisher

    @Autowired lateinit var keys: DataKeys

    @Autowired
    @Qualifier("workflowTimerTask")
    lateinit var task: OneTimeTask<TimerFire>

    @Autowired lateinit var scheduler: ObjectProvider<SchedulerClient>

    @Autowired lateinit var escalations: ObjectProvider<TimerEscalations>

    private val clock = MutableClock()
    private val timers by lazy { WorkflowTimers(tenantDsl, transactions, ids, calendars, events, clock, task, scheduler, escalations) }
    private val outbox by lazy { MailOutbox(keys, json) }

    @Test
    fun `the approver is reminded, then the approver and the initiator hear it is overdue`() {
        val body =
            """{"schemaVersion":2,"start":"route","steps":[
              {"id":"route","type":"route","sla":{"due":"PT4H","workingTime":false,"remind":{"before":"PT1H"}},"next":"end"}]}"""
        val draft = asUser(admin) { lifecycle.create("timed", "Timed", null, false, json.readTree(body)) }
        asUser(admin) { lifecycle.submit("timed", 1, draft.version.revision) }
        val checker = accounts.anActiveUser(transactions, tenant, aPassword(), tenantAdmin = true)
        asUser(checker) { lifecycle.approve("timed", 1, null) }
        val type = asUser(admin) { types.create(MemoTypeFields("Timed", null, false, true, "timed", RoutePolicy.INITIATOR_DEFINED)) }

        val alice = person()
        val bob = person()
        val level = levels()[0].id
        val id = create(alice.session, """{"subject":"Loan","typeId":"${type.id}","classificationId":"$level"}""").json()["id"].asString()
        uploadDocument(id, alice.session)
        route(id, alice, bob)
        assertThat(post("/api/v1/memos/$id/submit", alice.session)).hasStatusOk()
        assertThat(get(id, alice.session).json()["dueAt"].isNull).isFalse()

        clock.now = Instant.now().plus(Duration.ofHours(3).plusMinutes(10))
        assertThat(run()).isEqualTo(1)
        assertThat(kinds(bob)).containsExactly("TASK_ASSIGNED", "TASK_REMINDER")
        clock.now = clock.now.plus(Duration.ofHours(1))
        assertThat(run()).isEqualTo(1)
        assertThat(kinds(bob)).containsExactly("TASK_ASSIGNED", "TASK_REMINDER", "TASK_OVERDUE")
        assertThat(kinds(alice)).containsExactly("TASK_OVERDUE")

        val reference = get(id, alice.session).json()["reference"].asString()
        val mails = outbox.all(tenant).filter { it.template == "TASK_REMINDER" || it.template == "TASK_OVERDUE" }
        assertThat(mails).hasSize(3)
        assertThat(mails).allSatisfy { mail ->
            assertThat(mail.params["reference"]).isEqualTo(reference)
            assertThat(mail.params.values.joinToString()).doesNotContain("Loan")
        }
    }

    private fun run(): Int = TenantContext.call(tenant.id) { ActorContext.asSystem("test") { timers.runDue() } }

    private fun kinds(user: Person): List<String> =
        AppRoleJdbc.inTransaction(tenant.id) { c ->
            c.prepareStatement("select kind from notification_item where user_id = ? order by created_at, id").use {
                it.setObject(1, user.id)
                it.executeQuery().use { rs -> generateSequence { if (rs.next()) rs.getString(1) else null }.toList() }
            }
        }
}
