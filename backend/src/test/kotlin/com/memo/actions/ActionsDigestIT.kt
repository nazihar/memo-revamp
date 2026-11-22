package com.memo.actions

import com.memo.actions.internal.ActionReferences
import com.memo.actions.internal.ActionSafetyNet
import com.memo.actions.internal.DailyRun
import com.memo.common.context.ActorContext
import com.memo.org.OrgDirectory
import com.memo.testsupport.Audit
import com.memo.testsupport.MailOutbox
import com.memo.workflow.internal.Calendars
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset

/**
 * The `actions-daily` task (test plan E07 T5 `ActionsDigestIT`; design E07 §1.8, §9): the in-app items come at once and
 * their emails wait for one digest per person on the next working day from 09:00 in the tenant's zone, with the
 * references only; none on a holiday or before 09:00; a run repeated sends nothing twice. The safety net reports an issued
 * action without a workflow to the secretaries once, brings an action in step with its workflow, and plans the owner's
 * reminders again when they drifted from the date. The task is run here by hand (the scheduled one is off).
 */
class ActionsDigestIT : ActionFixture() {
    override fun label() = "actdigest"

    @Autowired lateinit var calendars: Calendars

    @Autowired lateinit var org: OrgDirectory

    @Autowired lateinit var safetyNet: ActionSafetyNet

    private val outbox by lazy { MailOutbox(keys, json) }

    @BeforeEach
    fun dailyOff() {
        daily.jobs = false
    }

    @AfterEach
    fun dailyBack() {
        daily.jobs = true
    }

    private fun run(at: Instant): DailyRun = transactions.inTenant(tenant.id) { ActorContext.asSystem("test") { daily.run(at) } }

    private fun at(
        day: LocalDate,
        time: LocalTime,
    ): Instant = day.atTime(time).atZone(transactions.readInTenant(tenant.id) { org.zone() }).toInstant()

    private fun nextWorkingDay(after: LocalDate = LocalDate.now(ZoneOffset.UTC)): LocalDate =
        checkNotNull(asUser(admin) { workingDays.after(after, 1) })

    private fun digests(account: com.memo.identity.UserAccount) =
        outbox.all(tenant).filter { it.template == "ACTIONS_DIGEST" && it.to == account.email }

    @Test
    fun `the day's notices come in one digest the next working morning, none on a holiday, and never twice`() {
        val action = issued(contributors = listOf(others[0].id.value))
        val id = action.id
        val reference = checkNotNull(action.reference)
        asUser(director2) { dates.request(id, row(id).version, due.plusDays(7), "The valuer is late") }
        asUser(secretary) { dates.changeDirectly(id, row(id).version, due.plusDays(9), "Moved at the meeting") }
        // In the app at once; no email yet.
        assertThat(items(secretary, "ACTION_DUE_REQUESTED")).hasSize(1)
        assertThat(items(others[0], "ACTION_DUE_CHANGED")).hasSize(1)
        assertThat(outbox.all(tenant).map { it.template }).doesNotContain("ACTIONS_DIGEST")

        // The next working day is a holiday: no digest that day.
        val holiday = nextWorkingDay()
        asUser(admin) { calendars.setDays(holiday, null, emptyList(), "Holiday") }
        assertThat(run(at(holiday, LocalTime.of(9, 30)))).isEqualTo(DailyRun.NOTHING)
        val day = nextWorkingDay()
        assertThat(day).isAfter(holiday)
        // Before 09:00: nothing yet.
        assertThat(run(at(day, LocalTime.of(8, 30)))).isEqualTo(DailyRun.NOTHING)

        val first = run(at(day, LocalTime.of(9, 30)))
        assertThat(first.digests).isEqualTo(3)
        assertThat(first.complete).isTrue()
        listOf(director2, others[0], secretary).forEach { person ->
            assertThat(digests(person)).singleElement().satisfies({ mail ->
                assertThat(mail.params["lines"]).contains(reference).contains("Due dates asked for, decided or changed")
                assertThat(mail.params["link"]).endsWith("/inbox?tab=actions")
                assertThat(mail.params.values.joinToString()).doesNotContain(action.title).doesNotContain("valuer")
            })
        }
        assertThat(digests(chair)).isEmpty()
        assertThat(items(director2, "ACTIONS_DIGEST")).isEmpty()
        Audit.assertAudited(tenant.id, "actions.digest.sent", day.toString())

        // The hourly runs after it, and a repeat, send nothing more that day.
        assertThat(run(at(day, LocalTime.of(10, 30))).digests).isZero()
        assertThat(digests(director2)).hasSize(1)
        // The next working day has no news since: no digest either.
        assertThat(run(at(nextWorkingDay(day), LocalTime.of(9, 30))).digests).isZero()
    }

    @Test
    fun `the safety net reports an action without a workflow once, brings one in step, and plans drifted reminders again`() {
        val d = deciding()
        val planted =
            ActionPlanting(transactions, actionStore, actionsDsl).action(
                tenant.id,
                brc,
                d.meeting.id,
                null,
                null,
                levels()[0].id,
                secretary.id.value,
                "Planted without a workflow",
                director2.id.value,
            )
        val lost = issued(deciding = d)
        alter(lost.id, "workflow_instance_id = null")
        val drifted = issued(deciding = d, item = d.appetite)
        val instance = checkNotNull(drifted.workflowInstanceId)
        val moved = due.plusDays(10)
        alter(drifted.id, "due_date = ?", moved)

        val run = run(at(nextWorkingDay(), LocalTime.of(9, 5)))
        assertThat(run.safety.reported).isEqualTo(1)
        assertThat(run.safety.reconciled).isEqualTo(1)
        assertThat(run.safety.replanned).isEqualTo(1)
        assertThat(items(secretary, "ACTION_NOT_RUNNING")).singleElement().satisfies({ assertThat(it).contains("BRC A") })
        assertThat(timeline(planted).first()).isEqualTo("NOT_RUNNING")
        Audit.assertAudited(tenant.id, "actions.action.not_running", planted.toString())
        assertThat(row(lost.id).workflowInstanceId).isEqualTo(lost.workflowInstanceId)
        assertThat(timeline(lost.id).first()).isEqualTo("RECONCILED")
        Audit.assertAudited(tenant.id, "actions.action.reconciled", lost.id.toString())
        assertThat(pendingTimers(instance).single { it.first == "DUE" }.second).isEqualTo(ActionReferences.dueAt(moved, ZoneOffset.UTC))
        Audit.assertAudited(tenant.id, "actions.timers.replanned", drifted.id.toString())

        // Once a day, and once per run of the action: nothing more to do or say.
        val again = transactions.inTenant(tenant.id) { ActorContext.asSystem("test") { safetyNet.check() } }
        assertThat(again.reported + again.reconciled + again.replanned).isZero()
        assertThat(items(secretary, "ACTION_NOT_RUNNING")).hasSize(1)
    }
}
