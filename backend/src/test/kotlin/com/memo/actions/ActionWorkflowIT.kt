package com.memo.actions

import com.github.kagkarlsson.scheduler.SchedulerClient
import com.github.kagkarlsson.scheduler.task.helper.OneTimeTask
import com.memo.actions.internal.ActionReferences
import com.memo.actions.internal.ActionStatus
import com.memo.actions.internal.RagWord
import com.memo.common.context.ActorContext
import com.memo.common.id.IdGenerator
import com.memo.common.tenant.TenantContext
import com.memo.forum.ForumRole
import com.memo.testsupport.Audit
import com.memo.testsupport.MailOutbox
import com.memo.testsupport.MutableClock
import com.memo.testsupport.aPassword
import com.memo.testsupport.anActiveUser
import com.memo.workflow.internal.Calendars
import com.memo.workflow.internal.TimerEscalations
import com.memo.workflow.internal.TimerFire
import com.memo.workflow.internal.WorkflowTimers
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.ApplicationEventPublisher
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/**
 * Reminders and escalation on the engine's timers (test plan E07 T5 `ActionWorkflowIT`; design E07 §1.8), with the
 * built-in definition and the timers fired on a hand-moved clock: 5 working days before the date the holder is reminded
 * in the app; at its end the holder and the secretaries hear it is overdue; 5 working days after, the holder again; after
 * 10, the holder's supervisor by the reporting line gets the escalation by email at once (the secretaries told in the
 * app), and nobody when there is no supervisor (the secretaries still told); the verifier is reminded and the chair told;
 * a substitute receives and acts; nothing fires once the action is closed or cancelled. Emails never carry the title.
 */
class ActionWorkflowIT : ActionFixture() {
    override fun label() = "actflow"

    @Autowired lateinit var ids: IdGenerator

    @Autowired lateinit var calendars: Calendars

    @Autowired lateinit var events: ApplicationEventPublisher

    @Autowired
    @Qualifier("workflowTimerTask")
    lateinit var timerTask: OneTimeTask<TimerFire>

    @Autowired lateinit var schedulerClient: ObjectProvider<SchedulerClient>

    @Autowired lateinit var escalations: ObjectProvider<TimerEscalations>

    private val clock = MutableClock()
    private val timers by lazy {
        WorkflowTimers(actionsDsl, transactions, ids, calendars, events, clock, timerTask, schedulerClient, escalations)
    }
    private val outbox by lazy { MailOutbox(keys, json) }

    /** Fires [instanceId]'s next pending timer of [kind] (moving the clock to it); how many timers fired. */
    private fun fire(
        instanceId: UUID,
        kind: String,
    ): Int {
        clock.now = pendingTimers(instanceId).first { it.first == kind }.second
        return TenantContext.call(tenant.id) { ActorContext.asSystem("test") { timers.runDue() } }
    }

    /** "Chief Financial Officer", held by a new person, to whom "Head of Treasury" reports. */
    private fun supervisor(): com.memo.identity.UserAccount {
        val cfo = accounts.anActiveUser(transactions, tenant, aPassword())
        val position = UUID.randomUUID()
        val entity = UUID.randomUUID()
        val unit = UUID.randomUUID()
        val start = LocalDate.now(ZoneOffset.UTC).minusYears(1)
        asUser(admin) {
            orgSeeding.legalEntity(entity, "Bank holding", start)
            orgSeeding.unit(unit, entity, null, "FIN", "Finance", start)
            orgSeeding.position(position, unit, "Chief Financial Officer", head = true, start)
            orgSeeding.assignment(position, cfo.id.value, start)
            orgSeeding.reportingLine(headOfTreasury, position, start)
        }
        return cfo
    }

    @Test
    fun `the holder is reminded, hears it is overdue with the secretaries, and the supervisor gets the escalation by email`() {
        val cfo = supervisor()
        val action = issued(owner = position())
        val id = action.id
        val instance = checkNotNull(action.workflowInstanceId)
        val reference = checkNotNull(action.reference)
        val due = checkNotNull(action.dueDate)
        assertThat(pendingTimers(instance).map { it.first }).containsExactly("REMIND_BEFORE", "DUE", "REMIND_AFTER", "ESCALATE")
        assertThat(pendingTimers(instance).single { it.first == "DUE" }.second).isEqualTo(ActionReferences.dueAt(due, ZoneOffset.UTC))

        // 5 working days before: the holder is reminded in the app, with the reference only; no email yet.
        assertThat(fire(instance, "REMIND_BEFORE")).isEqualTo(1)
        assertThat(items(treasurer, "ACTION_DUE_SOON")).singleElement().satisfies({
            assertThat(it).contains(reference, due.toString()).doesNotContain(action.title)
        })
        assertThat(items(secretary, "ACTION_DUE_SOON")).isEmpty()

        // At the end of the date: the holder and the secretaries hear it is overdue, in the app.
        assertThat(fire(instance, "DUE")).isEqualTo(1)
        assertThat(items(treasurer, "ACTION_OVERDUE")).singleElement().satisfies({ assertThat(it).contains("\"DUE\"") })
        assertThat(items(secretary, "ACTION_OVERDUE")).singleElement().satisfies({ assertThat(it).contains(reference, "\"DUE\"") })
        assertThat(items(chair, "ACTION_OVERDUE")).isEmpty()

        // 5 working days after: the holder again.
        assertThat(fire(instance, "REMIND_AFTER")).isEqualTo(1)
        assertThat(items(treasurer, "ACTION_OVERDUE")).hasSize(2).anySatisfy { assertThat(it).contains("\"REMINDER\"") }

        // 10 working days after: the supervisor by the reporting line, by email at once; the holder keeps the action.
        assertThat(fire(instance, "ESCALATE")).isEqualTo(1)
        assertThat(items(cfo, "ACTION_ESCALATED")).singleElement().satisfies({
            assertThat(it).contains(reference, "Head of Treasury").doesNotContain(action.title)
        })
        val mail = outbox.all(tenant).single { it.template == "ACTION_ESCALATED" }
        assertThat(mail.to).isEqualTo(cfo.email)
        assertThat(mail.params["reference"]).isEqualTo(reference)
        assertThat(mail.params["link"]).endsWith("/inbox")
        assertThat(mail.params.values.joinToString()).doesNotContain(action.title).doesNotContain("Details")
        assertThat(items(secretary, "ACTION_OVERDUE")).hasSize(2).anySatisfy { assertThat(it).contains("\"ESCALATED\"") }
        assertThat(row(id).holderUserId).isEqualTo(treasurer.id.value)
        Audit.assertAudited(tenant.id, "workflow.task.escalated", checkNotNull(taskOf(id, treasurer)).id.toString())
        // The reminders' emails wait for the digest: none was sent at once.
        assertThat(outbox.all(tenant).map { it.template }).doesNotContain("ACTION_DUE_SOON", "ACTION_OVERDUE")

        // Past its date the action is red, "Overdue" (as the clock of the engine had it).
        alter(id, "due_date = ?", LocalDate.now(ZoneOffset.UTC).minusDays(1))
        assertThat(checkNotNull(seen(id, treasurer)).rag.word).isEqualTo(RagWord.OVERDUE)
    }

    @Test
    fun `without a supervisor nobody is escalated to and the secretaries are still told`() {
        val action = issued()
        val instance = checkNotNull(action.workflowInstanceId)
        fire(instance, "ESCALATE")
        assertThat(outbox.all(tenant).filter { it.template == "ACTION_ESCALATED" }).isEmpty()
        assertThat(items(secretary, "ACTION_OVERDUE")).anySatisfy { assertThat(it).contains("\"ESCALATED\"") }
        assertThat(items(director2, "ACTION_ESCALATED")).isEmpty()
    }

    @Test
    fun `the verifier is reminded and the chair told, and nothing fires once the action is closed or cancelled`() {
        val action = issued()
        val id = action.id
        val instance = checkNotNull(action.workflowInstanceId)
        asUser(director2) { closure.done(id, row(id).version, "The valuation was reported", emptyList()) }
        assertThat(row(id).status).isEqualTo(ActionStatus.DONE_PENDING)
        assertThat(items(secretary, "ACTION_TO_VERIFY")).hasSize(1)
        // The verification is due 5 working days later and reminded 3 after that (both fire on the way).
        fire(instance, "REMIND_AFTER")
        assertThat(items(secretary, "ACTION_TO_VERIFY")).hasSize(3)
        fire(instance, "ESCALATE")
        assertThat(items(chair, "ACTION_ESCALATED")).singleElement().satisfies({ assertThat(it).contains("/actions/$id") })

        asUser(secretary) { closure.verify(id, row(id).version, com.memo.actions.internal.VerifyDecision.CLOSE, null) }
        assertThat(row(id).status).isEqualTo(ActionStatus.CLOSED)
        assertThat(pendingTimers(instance)).isEmpty()

        val other = deciding()
        val cancelled = issued(deciding = other, item = other.appetite)
        val second = checkNotNull(cancelled.workflowInstanceId)
        asUser(secretary) {
            ending.cancel(
                cancelled.id,
                row(cancelled.id).version,
                com.memo.actions.internal
                    .CancelInput(com.memo.actions.internal.CancelKind.NO_LONGER_NEEDED, "Withdrawn"),
            )
        }
        assertThat(pendingTimers(second)).isEmpty()
        clock.now = clock.now.plus(java.time.Duration.ofDays(400))
        TenantContext.call(tenant.id) { ActorContext.asSystem("test") { timers.runDue() } }
        assertThat(items(director2, "ACTION_DUE_SOON")).isEmpty()
    }

    @Test
    fun `a substitute of the position's holder receives the reminders and marks the action done`() {
        val substitute = accounts.anActiveUser(transactions, tenant, aPassword())
        seat(accounts.anActiveUser(transactions, tenant, aPassword()), ForumRole.SECRETARY)
        val action = issued(owner = position())
        val id = action.id
        val task = checkNotNull(taskOf(id, treasurer))
        // The holder is away: the task goes to a substitute who acts for them (ADR-0040).
        asUser(admin) { workflows.reassign(task.id, substitute.id.value, "Out of office") }
        assertThat(row(id).holderUserId).isEqualTo(substitute.id.value)
        fire(checkNotNull(action.workflowInstanceId), "REMIND_BEFORE")
        assertThat(items(substitute, "ACTION_DUE_SOON")).hasSize(1)
        asUser(substitute) { closure.done(id, row(id).version, "Done for the head of treasury", emptyList()) }
        assertThat(row(id).status).isEqualTo(ActionStatus.DONE_PENDING)
    }
}
