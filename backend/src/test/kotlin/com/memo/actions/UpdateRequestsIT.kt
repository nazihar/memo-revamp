package com.memo.actions

import com.memo.actions.internal.ActionUpdateRequests
import com.memo.common.context.ActorContext
import com.memo.identity.UserAccount
import com.memo.meeting.AgendaDeadlines
import com.memo.meeting.internal.MeetingRow
import com.memo.minutes.DecisionFixture
import com.memo.org.OrgDirectory
import com.memo.testsupport.Audit
import com.memo.testsupport.MailOutbox
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
 * Asking owners for an update before the committee's papers (test plan E07 T7 `UpdateRequestsIT`; design E07 §1.12, §9
 * (b)): the daily task asks the holders of open actions with no update since the previous meeting, from the committee
 * type's `updateRequestWorkingDays` (5) before the next meeting's agenda deadline, once per meeting, in the app and by
 * email (the reference and the due date, never the title); "Ask owners for an update" asks at once, once a day per
 * committee, a recused secretary's leaving out the item's actions. The task is run here by hand (the scheduled one is off).
 */
class UpdateRequestsIT : ActionFixture() {
    override fun label() = "actupdates"

    @Autowired lateinit var updateRequests: ActionUpdateRequests

    @Autowired lateinit var deadlines: AgendaDeadlines

    @Autowired lateinit var org: OrgDirectory

    private val outbox by lazy { MailOutbox(keys, json) }

    @BeforeEach
    fun dailyOff() {
        daily.jobs = false
    }

    @AfterEach
    fun dailyBack() {
        daily.jobs = true
    }

    /** A BRC meeting [workingDaysAhead] working days ahead, scheduled. */
    private fun meetingIn(workingDaysAhead: Int): MeetingRow {
        val day = checkNotNull(asUser(admin) { workingDays.after(today, workingDaysAhead) })
        return asUser(secretary) { lifecycle.create(brc, place(), times(day)).let { lifecycle.schedule(it.id, it.version) } }
    }

    private fun askDaily(at: Instant = Instant.now()): Int =
        transactions.inTenant(tenant.id) { ActorContext.asSystem("test") { updateRequests.daily(at) } }

    private fun at(
        day: LocalDate,
        time: LocalTime,
    ): Instant = day.atTime(time).atZone(transactions.readInTenant(tenant.id) { org.zone() }).toInstant()

    private fun asked(account: UserAccount) = items(account, "ACTION_UPDATE_REQUESTED")

    @Test
    fun `holders of actions with no update since the last meeting are asked before the agenda deadline, once per meeting`() {
        val d = deciding()
        val quiet = issued(deciding = d)
        val updated = issued(deciding = d)
        val done = issued(deciding = d)
        val ofPosition = issued(deciding = d, owner = position())
        alter(updated.id, "last_update_at = now()")
        alter(done.id, "status = 'DONE_PENDING', done_at = now()")

        // The next meeting's papers are weeks away: too early to ask.
        meetingIn(40)
        assertThat(askDaily()).isZero()

        // A nearer meeting: its deadline is two working days away, within the five.
        val near = meetingIn(7)
        val deadline = checkNotNull(transactions.readInTenant(tenant.id) { deadlines.of(near.id) })
        assertThat(deadline).isAfterOrEqualTo(today)
        val morning = at(checkNotNull(asUser(admin) { workingDays.after(today, 1) }), LocalTime.of(9, 30))
        val run = transactions.inTenant(tenant.id) { ActorContext.asSystem("test") { daily.run(morning) } }
        assertThat(run.updatesAsked).isEqualTo(2)

        assertThat(asked(director2)).singleElement().satisfies({
            assertThat(it).contains(checkNotNull(quiet.reference), "DEADLINE", deadline.toString()).doesNotContain(quiet.title)
        })
        assertThat(asked(treasurer)).singleElement().satisfies({ assertThat(it).contains(checkNotNull(ofPosition.reference)) })
        assertThat(timeline(quiet.id).first()).isEqualTo(ActionUpdateRequests.UPDATE_REQUESTED)
        assertThat(timeline(updated.id)).doesNotContain(ActionUpdateRequests.UPDATE_REQUESTED)
        assertThat(timeline(done.id)).doesNotContain(ActionUpdateRequests.UPDATE_REQUESTED)
        val mails = outbox.all(tenant).filter { it.template == "ACTION_UPDATE_REQUESTED" }
        assertThat(mails.map { it.to }).containsExactlyInAnyOrder(director2.email, treasurer.email)
        mails.forEach { mail -> assertThat(mail.params.values.joinToString()).doesNotContain(quiet.title) }
        val audited = Audit.assertAudited(tenant.id, "actions.update_requests.sent", brc.toString())
        assertThat(audited.canonical).contains("\"asked\":2").contains(near.id.toString())

        // Once per meeting: the next run asks nobody again.
        assertThat(askDaily(morning)).isZero()
        assertThat(asked(director2)).hasSize(1)
    }

    @Test
    fun `asking owners for an update asks at once, once a day, and a recused secretary's leave out the item's actions`() {
        val secretary2 = secondSecretary()
        val d = deciding { m, items -> declareOn(m, items.getValue(DecisionFixture.CREDIT), secretary) }
        val ofCredit = issue(raise(d.meeting.id, d.credit, fields(owner = person(director2)), by = secretary2), by = secretary2)
        val ofAppetite = issued(deciding = d, item = d.appetite)

        assertThat(outcome(director) { updateRequests.askNow(brc) }).startsWith("403")
        assertThat(asUser(secretary) { updateRequests.askNow(brc) }).isEqualTo(1)
        assertThat(asked(director2)).singleElement().satisfies({
            assertThat(it).contains(checkNotNull(ofAppetite.reference), "NEXT").doesNotContain(checkNotNull(ofCredit.reference))
        })
        assertThat(outcome(secretary2) { updateRequests.askNow(brc) }).isEqualTo("409 ${ActionUpdateRequests.ALREADY_ASKED}")
        val audited = Audit.assertAudited(tenant.id, "actions.update_requests.sent", brc.toString(), secretary.id.value.toString())
        assertThat(audited.canonical).contains("SECRETARY")
    }
}
