package com.memo.meeting

import com.memo.audit.AuditLog
import com.memo.common.context.ActorContext
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantDsl
import com.memo.forum.MeetingPolicy
import com.memo.forum.internal.ForumTypeLifecycle
import com.memo.identity.UserAccount
import com.memo.jooq.tables.references.MEETING_INVITATION
import com.memo.meeting.internal.AgendaLateness
import com.memo.meeting.internal.AgendaOrigin
import com.memo.meeting.internal.DeclarationReminders
import com.memo.meeting.internal.DeclarationReviews
import com.memo.meeting.internal.ItemInput
import com.memo.meeting.internal.ItemKind
import com.memo.meeting.internal.MeetingNotifier
import com.memo.meeting.internal.MeetingReminders
import com.memo.meeting.internal.MeetingRow
import com.memo.meeting.internal.MeetingSweep
import com.memo.meeting.internal.MeetingSweepStore
import com.memo.meeting.internal.TimesInput
import com.memo.meeting.internal.invite.InvitationSafetyNet
import com.memo.meeting.internal.rsvp.RsvpAnswer
import com.memo.meeting.internal.rsvp.RsvpInput
import com.memo.testsupport.Audit
import com.memo.testsupport.MailOutbox
import com.memo.testsupport.MutableClock
import com.memo.testsupport.aPassword
import com.memo.testsupport.anActiveUser
import com.memo.workflow.WorkingDays
import com.memo.workflow.internal.Calendars
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * The sweep's reminders before a meeting (test plan E05 T6, design E05 §9 (e)–(h)) on a test clock, with the invitations
 * sent by the reconciler as its job would: the reply reminder on the deadline day from 09:00 to those without an answer
 * for the current time, once (the deadline moved by a holiday); the meeting reminder the working day before with the
 * declarations line, and E04's reminder not sent to the same person; the reply line in place of the reply reminder when
 * both fall on one day; quorum at risk once per meeting and sequence, to the secretary and the chair; and the safety net
 * sending an invitation whose job was lost.
 */
class MeetingRemindersIT : AttendanceTestSupport() {
    @Autowired lateinit var sweepStore: MeetingSweepStore

    @Autowired lateinit var lateness: AgendaLateness

    @Autowired lateinit var notifier: MeetingNotifier

    @Autowired lateinit var safetyNet: InvitationSafetyNet

    @Autowired lateinit var meetingReminders: MeetingReminders

    @Autowired lateinit var declarationReminders: DeclarationReminders

    @Autowired lateinit var reviews: DeclarationReviews

    @Autowired lateinit var auditLog: AuditLog

    @Autowired lateinit var workingDays: WorkingDays

    @Autowired lateinit var calendars: Calendars

    @Autowired lateinit var typeVersions: ForumTypeLifecycle

    @Autowired lateinit var tenantDsl: TenantDsl

    private val clock = MutableClock()
    private val sweep by lazy {
        MeetingSweep(sweepStore, lateness, notifier, access, safetyNet, meetingReminders, declarationReminders, auditLog, clock)
    }

    override fun label() = "reminders"

    @Test
    fun `the reply reminder goes on the deadline day from nine to those without an answer for the current time, once`() {
        val day = LocalDate.now(DHAKA).plusDays(DAYS_AHEAD)
        // The second working day before is a holiday: the deadline moves to the working day before it.
        val holiday = checkNotNull(asUser(admin) { workingDays.before(day, 2) })
        asUser(admin) { calendars.setDays(holiday, null, emptyList(), "Holiday") }
        val deadline = checkNotNull(asUser(admin) { workingDays.before(day, 2) })
        assertThat(deadline).isBefore(holiday)
        val meeting = publishedOn(day)
        reconcile(meeting.id)
        answer(chair, meeting(meeting.id), RsvpInput(RsvpAnswer.REMOTE))
        // A new time makes the chair's answer one for the old time; the directors answer the new one.
        val moved =
            meeting(meeting.id).let {
                asUser(secretary) { lifecycle.reschedule(it.id, it.version, times(day, 14), "The chair is travelling") }
            }
        reconcile(moved.id)
        answer(director, moved, RsvpInput(RsvpAnswer.IN_PERSON))
        answer(director2, moved, RsvpInput(RsvpAnswer.APOLOGIES))

        at(deadline, LocalTime.of(8, 59))
        assertThat(told(REPLY, moved)).isEmpty()
        at(deadline, FROM)
        // The chair (stale) and the four others (no answer); not the directors, who answered the current time.
        assertThat(told(REPLY, moved)).contains(chair, *others.toTypedArray()).doesNotContain(director, director2)
        Audit.assertAudited(tenant.id, "meeting.reminded.reply", moved.id.toString(), null)
        val mail = MailOutbox(keys, json).last(tenant, REPLY, chair.email)
        assertThat(mail.params).containsEntry("committee", "Board Risk Committee").containsEntry("date", day.toString())

        val before = told(REPLY, moved).size
        clock.advance(Duration.ofHours(1))
        swept()
        at(deadline.plusDays(1), FROM)
        assertThat(told(REPLY, moved)).hasSize(before)
    }

    @Test
    fun `the meeting reminder carries the declarations line, and the declarations reminder does not go to the same person`() {
        val day = LocalDate.now(DHAKA).plusDays(DAYS_AHEAD)
        val dayBefore = checkNotNull(asUser(admin) { workingDays.before(day, 1) })
        val meeting = publishedOn(day)
        reconcile(meeting.id)
        answer(chair, meeting, RsvpInput(RsvpAnswer.IN_PERSON))
        answer(director, meeting, RsvpInput(RsvpAnswer.REMOTE))
        answer(director2, meeting, RsvpInput(RsvpAnswer.APOLOGIES))
        asUser(director) { reviews.review(meeting.id, 1) }

        at(dayBefore, LocalTime.of(8, 59))
        assertThat(told(MEETING_REMINDER, meeting) + told(DECLARATIONS_DUE, meeting)).isEmpty()
        at(dayBefore, FROM)
        val reminded = told(MEETING_REMINDER, meeting)
        // Those who said yes or did not answer; not director2, who sent their apologies.
        assertThat(reminded).contains(chair, director, *others.toTypedArray()).doesNotContain(director2)
        // One email each: whoever has items to review gets the line in the reminder, and no declarations reminder; director2
        // gets the declarations reminder alone.
        assertThat(told(DECLARATIONS_DUE, meeting)).containsExactly(director2)
        val chairs = MailOutbox(keys, json).last(tenant, MEETING_REMINDER, chair.email).params
        assertThat(chairs.getValue("declarations")).contains("declarations of interest")
        assertThat(chairs.getValue("reply")).isEmpty()
        assertThat(chairs).containsEntry("toReview", "2").containsEntry("unanswered", "false")
        val directors = MailOutbox(keys, json).last(tenant, MEETING_REMINDER, director.email).params
        assertThat(directors.getValue("declarations")).isEmpty()
        val other = MailOutbox(keys, json).last(tenant, MEETING_REMINDER, others[0].email).params
        assertThat(other.getValue("reply")).contains("whether you will attend")
        assertThat(other.getValue("declarations")).isNotEmpty()
        val audited = Audit.assertAudited(tenant.id, "meeting.reminded.meeting", meeting.id.toString(), null)
        assertThat(audited.canonical).contains("\"date\":\"$day\"")
        Audit.assertAudited(tenant.id, "meeting.reminded.declarations_due", meeting.id.toString(), null)

        // Nothing goes twice.
        val counts = told(MEETING_REMINDER, meeting).size to told(DECLARATIONS_DUE, meeting).size
        clock.advance(Duration.ofHours(1))
        swept()
        assertThat(told(MEETING_REMINDER, meeting).size to told(DECLARATIONS_DUE, meeting).size).isEqualTo(counts)
    }

    @Test
    fun `when the reply deadline is the working day before, the meeting reminder asks for the answer and no reply reminder goes`() {
        publishPolicy(MeetingPolicy(replyDeadlineWorkingDays = 1))
        val day = LocalDate.now(DHAKA).plusDays(DAYS_AHEAD)
        val dayBefore = checkNotNull(asUser(admin) { workingDays.before(day, 1) })
        val meeting = publishedOn(day)
        assertThat(meeting.typeVersion).isEqualTo(2)
        reconcile(meeting.id)
        answer(chair, meeting, RsvpInput(RsvpAnswer.IN_PERSON))

        at(dayBefore, FROM)
        assertThat(told(MEETING_REMINDER, meeting)).contains(chair, director)
        assertThat(MailOutbox(keys, json).last(tenant, MEETING_REMINDER, director.email).params.getValue("reply")).isNotEmpty()
        assertThat(told(REPLY, meeting)).isEmpty()
        clock.advance(Duration.ofHours(1))
        swept()
        assertThat(told(REPLY, meeting)).isEmpty()
    }

    @Test
    fun `without the meeting reminder, the reply reminder still goes on the deadline day`() {
        publishPolicy(MeetingPolicy(replyDeadlineWorkingDays = 1, meetingReminder = false))
        val day = LocalDate.now(DHAKA).plusDays(DAYS_AHEAD)
        val dayBefore = checkNotNull(asUser(admin) { workingDays.before(day, 1) })
        val meeting = publishedOn(day)
        reconcile(meeting.id)

        at(dayBefore, FROM)
        assertThat(told(MEETING_REMINDER, meeting)).isEmpty()
        assertThat(told(REPLY, meeting)).contains(chair, director, director2)
    }

    @Test
    fun `quorum at risk goes from five on the deadline day to the secretary and the chair, once per meeting and sequence`() {
        val day = LocalDate.now(DHAKA).plusDays(DAYS_AHEAD)
        val deadline = checkNotNull(asUser(admin) { workingDays.before(day, 2) })
        val atRisk = publishedOn(day)
        reconcile(atRisk.id)
        val quorate = publishedOn(day)
        reconcile(quorate.id)
        voting.forEach { answer(it, quorate, RsvpInput(RsvpAnswer.IN_PERSON)) }
        // Only two say yes to the first meeting: not 4 of 7.
        listOf(chair, director).forEach { answer(it, atRisk, RsvpInput(RsvpAnswer.IN_PERSON)) }

        at(deadline, LocalTime.of(16, 59))
        assertThat(told(AT_RISK, atRisk)).isEmpty()
        at(deadline, LocalTime.of(17, 0))
        assertThat(told(AT_RISK, atRisk)).containsExactlyInAnyOrder(secretary, chair)
        assertThat(told(AT_RISK, quorate)).isEmpty()
        assertThat(told(AT_RISK, atRisk, director)).isEmpty()
        val audited = Audit.assertAudited(tenant.id, "meeting.reminded.quorum_at_risk", atRisk.id.toString(), null)
        assertThat(audited.canonical).contains("\"quorate\":false").contains("\"present\":2")
        assertThat(MailOutbox(keys, json).last(tenant, AT_RISK, chair.email).params).containsEntry("committee", "Board Risk Committee")
        clock.advance(Duration.ofHours(1))
        swept()
        assertThat(told(AT_RISK, atRisk)).hasSize(2)

        // A new place raises the invitation's sequence: the secretary and the chair are told again once.
        val current = meeting(atRisk.id)
        asUser(secretary) {
            lifecycle.edit(current.id, current.version, place(location = "Committee room"))
        }
        clock.advance(Duration.ofHours(1))
        swept()
        assertThat(told(AT_RISK, atRisk)).hasSize(4)
        clock.advance(Duration.ofHours(1))
        swept()
        assertThat(told(AT_RISK, atRisk)).hasSize(4)
    }

    @Test
    fun `the safety net schedules again an invitation whose send is 15 minutes overdue, and it goes`() {
        val meeting = scheduled()
        assertThat(invitees(meeting.id)).isEmpty()
        // The job was lost: the send has been due for 20 minutes.
        transactions.inTenant(tenant.id) {
            tenantDsl
                .current()
                .update(MEETING_INVITATION)
                .set(MEETING_INVITATION.DUE_AT, Instant.now().minus(Duration.ofMinutes(OVERDUE_MINUTES)).atOffset(ZoneOffset.UTC))
                .where(MEETING_INVITATION.MEETING_ID.eq(meeting.id))
                .execute()
        }
        scheduler.jobs = true
        clock.now = Instant.now()
        swept()
        val until = Instant.now().plus(Duration.ofSeconds(WAIT_SECONDS))
        while (invitees(meeting.id).isEmpty() && Instant.now().isBefore(until)) Thread.sleep(POLL_MILLIS)
        assertThat(invitees(meeting.id).keys).contains(director.id.value, director2.id.value)
        assertThat(checkNotNull(invitation(meeting.id)).dueAt).isNull()
    }

    /** A BRC meeting on [day] at 10:00 in Dhaka, scheduled, with two items published. */
    private fun publishedOn(day: LocalDate): MeetingRow {
        val created =
            asUser(secretary) {
                lifecycle.create(brc, place(location = "Board room"), times(day, 10))
            }
        asUser(secretary) {
            agendaStart.start(created.id, AgendaOrigin.TEMPLATE, null)
            val section =
                drafts
                    .draft(created.id)
                    .sections
                    .single { it.title == "For decision" }
                    .id
            listOf("Credit proposal", "Risk appetite").forEach { title ->
                agendaItems.add(created.id, drafts.draft(created.id).version, ItemInput(ItemKind.ITEM, sectionId = section, title = title))
            }
        }
        val scheduled = asUser(secretary) { meeting(created.id).let { lifecycle.schedule(it.id, it.version) } }
        asUser(chair) { chairing.agree(created.id, drafts.draft(created.id).version, false, null) }
        asUser(secretary) { publisher.publish(created.id, drafts.draft(created.id).version, null, true) }
        return meeting(scheduled.id)
    }

    private fun times(
        day: LocalDate,
        hour: Int,
    ) = TimesInput(LocalDateTime.of(day, LocalTime.of(hour, 0)), LocalDateTime.of(day, LocalTime.of(hour + 3, 0)), DHAKA.id)

    /** Publishes version 2 of the committee's type with [policy] (reviewed by a second administrator). */
    private fun publishPolicy(policy: MeetingPolicy) {
        val checker = accounts.anActiveUser(transactions, tenant, aPassword(), tenantAdmin = true)
        val draft = asUser(admin) { typeVersions.newDraft(typeId) }
        val saved = asUser(admin) { typeVersions.replace(typeId, 2, draft.revision, draft.settings.copy(meetings = policy)) }
        asUser(admin) { typeVersions.submit(typeId, 2, saved.revision) }
        asUser(checker) { typeVersions.approve(typeId, 2, null) }
    }

    /** Moves the clock to [time] on [day] in Dhaka and sweeps once. */
    private fun at(
        day: LocalDate,
        time: LocalTime,
    ) {
        clock.now = day.atTime(time).atZone(DHAKA).toInstant()
        swept()
    }

    /** One run of the sweep in the test tenant, as the task runs it: the system, in a tenant transaction. */
    private fun swept(): Int =
        TenantContext.call(tenant.id) {
            ActorContext.asSystem("test") { transactions.inTenant(tenant.id) { sweep.sweep() } }
        }

    /** Who of the committee was told [kind] about [meeting] in the app (once each, or the list repeats them). */
    private fun told(
        kind: String,
        meeting: MeetingRow,
        vararg among: UserAccount,
    ): List<UserAccount> {
        val people = among.toList().ifEmpty { listOf(secretary, chair, director, director2) + others }
        return people.flatMap { person -> items(person, kind).filter { meeting.id.toString() in it }.map { person } }
    }

    private companion object {
        val DHAKA: ZoneId = ZoneId.of("Asia/Dhaka")
        val FROM: LocalTime = LocalTime.of(9, 0)
        const val DAYS_AHEAD = 20L
        const val REPLY = "MEETING_REPLY_REMINDER"
        const val MEETING_REMINDER = "MEETING_REMINDER"
        const val AT_RISK = "MEETING_QUORUM_AT_RISK"
        const val DECLARATIONS_DUE = "MEETING_DECLARATIONS_DUE"
        const val OVERDUE_MINUTES = 20L
        const val WAIT_SECONDS = 30L
        const val POLL_MILLIS = 200L
    }
}
