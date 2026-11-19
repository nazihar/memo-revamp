package com.memo.meeting

import com.github.kagkarlsson.scheduler.SchedulerClient
import com.github.kagkarlsson.scheduler.task.helper.OneTimeTask
import com.memo.audit.AuditLog
import com.memo.common.authz.AccessAdministration
import com.memo.common.context.ActorContext
import com.memo.common.tenant.TenantContext
import com.memo.forum.ForumRole
import com.memo.forum.internal.ForumInput
import com.memo.forum.internal.ForumMembers
import com.memo.forum.internal.ForumTypePreset
import com.memo.forum.internal.ForumTypes
import com.memo.forum.internal.Forums
import com.memo.forum.internal.MemberInput
import com.memo.meeting.internal.AgendaChair
import com.memo.meeting.internal.AgendaDraftViews
import com.memo.meeting.internal.AgendaLateness
import com.memo.meeting.internal.AgendaOrigin
import com.memo.meeting.internal.AgendaPublisher
import com.memo.meeting.internal.AgendaStart
import com.memo.meeting.internal.MeetingInput
import com.memo.meeting.internal.MeetingLifecycle
import com.memo.meeting.internal.MeetingNotifier
import com.memo.meeting.internal.MeetingPlace
import com.memo.meeting.internal.MeetingRow
import com.memo.meeting.internal.MeetingStore
import com.memo.meeting.internal.MeetingSweep
import com.memo.meeting.internal.MeetingSweepStore
import com.memo.meeting.internal.MeetingSweepTenantTask
import com.memo.meeting.internal.TimesInput
import com.memo.testsupport.AppRoleJdbc
import com.memo.testsupport.AppRoleJdbc.long
import com.memo.testsupport.Audit
import com.memo.testsupport.MemoIntegrationTest
import com.memo.testsupport.MutableClock
import com.memo.testsupport.Person
import com.memo.testsupport.aTestTenant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

/**
 * The hourly meeting sweep (test plan E03, T5; design E03 §9) on a test clock: the agenda-due reminder once after the
 * paper deadline and never for a published agenda, the "not marked as held" reminder once a day after the end, both
 * again after a reschedule, to the secretaries (the administrators when there are none) and never to members; and the
 * per-tenant task, run by the scheduler with the tenant's context set.
 */
class MeetingSweepIT : MemoIntegrationTest() {
    @Autowired lateinit var forumTypes: ForumTypes

    @Autowired lateinit var forums: Forums

    @Autowired lateinit var members: ForumMembers

    @Autowired lateinit var lifecycle: MeetingLifecycle

    @Autowired lateinit var starts: AgendaStart

    @Autowired lateinit var views: AgendaDraftViews

    @Autowired lateinit var chairing: AgendaChair

    @Autowired lateinit var publisher: AgendaPublisher

    @Autowired lateinit var sweepStore: MeetingSweepStore

    @Autowired lateinit var meetingStore: MeetingStore

    @Autowired lateinit var lateness: AgendaLateness

    @Autowired lateinit var notifier: MeetingNotifier

    @Autowired lateinit var administration: AccessAdministration

    @Autowired lateinit var auditLog: AuditLog

    @Autowired lateinit var safetyNet: com.memo.meeting.internal.invite.InvitationSafetyNet

    @Autowired lateinit var scheduler: SchedulerClient

    @Autowired lateinit var sweepTask: OneTimeTask<MeetingSweepTenantTask>

    private val clock = MutableClock()
    private val sweep by lazy { MeetingSweep(sweepStore, lateness, notifier, administration, safetyNet, auditLog, clock) }
    private lateinit var secretary: Person
    private lateinit var chair: Person
    private lateinit var member: Person
    private lateinit var type: UUID
    private lateinit var brc: UUID

    @BeforeEach
    fun committee() {
        secretary = person()
        chair = person()
        member = person()
        type = asUser(admin) { forumTypes.create("BC", "Board committee", ForumTypePreset.BOARD_COMMITTEE, publish = true) }.type.id
        brc = asUser(admin) { forums.create(ForumInput("board-risk", "BRC", "Board Risk Committee", "Risk", type)) }.id
        asUser(admin) { members.add(brc, MemberInput(userId = secretary.id, role = ForumRole.SECRETARY)) }
        asUser(admin) { members.add(brc, MemberInput(userId = chair.id, role = ForumRole.CHAIR)) }
        asUser(admin) { members.add(brc, MemberInput(userId = member.id)) }
    }

    @Test
    fun `the agenda is due once after the deadline, the meeting is not held once a day after its end, and both again after a reschedule`() {
        val day = LocalDate.now(DHAKA).plusDays(DAYS_AHEAD)
        val meeting = meeting(brc, day, scheduled = true)
        val deadline = checkNotNull(asUser(admin) { lateness.deadline(meeting) })

        // Before the deadline, and on its last day, nothing is due.
        clock.now = deadline.atTime(EVENING).atZone(DHAKA).toInstant()
        assertThat(swept()).isZero()
        // The day after: the secretaries are told once; members never.
        clock.now =
            deadline
                .plusDays(1)
                .atTime(MORNING)
                .atZone(DHAKA)
                .toInstant()
        assertThat(swept()).isEqualTo(1)
        assertThat(told(secretary, AGENDA_DUE)).isEqualTo(1)
        assertThat(told(member, AGENDA_DUE) + told(chair, AGENDA_DUE)).isZero()
        Audit.assertAudited(tenant.id, "meeting.reminded.agenda_due", meeting.id.toString(), null)
        clock.advance(Duration.ofHours(1))
        assertThat(swept()).isZero()

        // Still scheduled a day after its end: not marked as held, once.
        clock.now = meeting.endsAt.plus(Duration.ofHours(HOURS_AFTER_END))
        assertThat(swept()).isZero()
        clock.now = meeting.endsAt.plus(Duration.ofDays(1)).plusSeconds(1)
        assertThat(swept()).isEqualTo(1)
        assertThat(told(secretary, NOT_HELD)).isEqualTo(1)
        assertThat(told(member, NOT_HELD)).isZero()
        Audit.assertAudited(tenant.id, "meeting.reminded.not_held", meeting.id.toString(), null)
        assertThat(swept()).isZero()

        // Rescheduled to a later date: that date reminds again.
        val later = day.plusDays(DAYS_AHEAD)
        val moved =
            asUser(secretary.account) {
                lifecycle.reschedule(meeting.id, current(meeting).version, times(later), "The chair is travelling")
            }
        val newDeadline = checkNotNull(asUser(admin) { lateness.deadline(moved) })
        clock.now =
            newDeadline
                .plusDays(1)
                .atTime(MORNING)
                .atZone(DHAKA)
                .toInstant()
        assertThat(swept()).isEqualTo(1)
        assertThat(told(secretary, AGENDA_DUE)).isEqualTo(2)
        clock.now = moved.endsAt.plus(Duration.ofDays(2))
        assertThat(swept()).isEqualTo(1)
        assertThat(told(secretary, NOT_HELD)).isEqualTo(2)
    }

    @Test
    fun `a published agenda and a held meeting are never reminded, and without secretaries the administrators are told`() {
        val day = LocalDate.now(DHAKA).plusDays(DAYS_AHEAD)
        val published = meeting(brc, day, scheduled = true)
        asUser(secretary.account) { starts.start(published.id, AgendaOrigin.TEMPLATE, null) }
        asUser(chair.account) { chairing.agree(published.id, version(published), false, null) }
        asUser(secretary.account) { publisher.publish(published.id, version(published), null, false) }
        val held = meeting(brc, day, scheduled = true)
        asUser(secretary.account) {
            lifecycle.start(held.id, current(held).version)
            lifecycle.end(held.id, current(held).version)
        }
        val alco =
            asUser(admin) { forums.create(ForumInput("alco", "ALCO", "Asset-Liability Committee", "ALCO", type)) }.id
        val orphan = meeting(alco, day, scheduled = false)

        clock.now =
            day
                .minusDays(1)
                .atTime(MORNING)
                .atZone(DHAKA)
                .toInstant()
        // Only the draft meeting of a committee without a secretary is due, and the administrators are told.
        assertThat(swept()).isEqualTo(1)
        assertThat(told(secretary, AGENDA_DUE)).isZero()
        assertThat(told(admin.id.value, AGENDA_DUE)).isEqualTo(1)
        Audit.assertAudited(tenant.id, "meeting.reminded.agenda_due", orphan.id.toString(), null)

        // A day after the end: the held one and the draft are not "not marked as held"; the published one is.
        clock.now = published.endsAt.plus(Duration.ofDays(1)).plusSeconds(1)
        assertThat(swept()).isEqualTo(1)
        Audit.assertAudited(tenant.id, "meeting.reminded.not_held", published.id.toString(), null)
        assertThat(told(secretary, NOT_HELD)).isEqualTo(1)
    }

    @Test
    fun `the hourly task runs the sweep in each tenant with its context set, and touches no other tenant`() {
        // On the real clock: due by now (the deadline was days ago), and a scheduled meeting that ended two days ago.
        val due = meeting(brc, LocalDate.now(DHAKA).plusDays(1), scheduled = true)
        val ended = meeting(brc, LocalDate.now(DHAKA).minusDays(2), scheduled = true)
        val other = registry.aTestTenant(transactions, settings, "sweepother", mfaRequired = false)
        // Another tenant's sweep sees nothing of this one.
        val elsewhere =
            TenantContext.call(other.id) {
                ActorContext.asSystem("test") { transactions.inTenant(other.id) { sweep.sweep() } }
            }
        assertThat(elsewhere).isZero()

        scheduler.scheduleIfNotExists(sweepTask.schedulableInstance(tenant.id.toString(), MeetingSweepTenantTask(tenant.id.value)))
        val deadline = System.currentTimeMillis() + WAIT_MILLIS
        while (told(secretary, AGENDA_DUE) + told(secretary, NOT_HELD) < 2 && System.currentTimeMillis() < deadline) {
            Thread.sleep(POLL_MILLIS)
        }
        assertThat(told(secretary, AGENDA_DUE) to told(secretary, NOT_HELD)).isEqualTo(1L to 1L)
        Audit.assertAudited(tenant.id, "meeting.reminded.agenda_due", due.id.toString(), null)
        Audit.assertAudited(tenant.id, "meeting.reminded.not_held", ended.id.toString(), null)
    }

    /** One run of the sweep in the test tenant, as the task runs it: the system, in a tenant transaction. */
    private fun swept(): Int =
        TenantContext.call(tenant.id) {
            ActorContext.asSystem("test") { transactions.inTenant(tenant.id) { sweep.sweep() } }
        }

    private fun meeting(
        forum: UUID,
        day: LocalDate,
        scheduled: Boolean,
    ): MeetingRow {
        val level = levels()[0].id
        return asUser(admin) {
            val created =
                lifecycle.create(
                    forum,
                    MeetingInput(MeetingKind.ORDINARY, MeetingPlace(null, MeetingMode.IN_PERSON, "Board room", null, null), level),
                    times(day),
                )
            if (scheduled) lifecycle.schedule(created.id, created.version) else created
        }
    }

    private fun times(day: LocalDate) = TimesInput(LocalDateTime.of(day, START), LocalDateTime.of(day, END), DHAKA.id)

    private fun current(meeting: MeetingRow): MeetingRow = asUser(admin) { checkNotNull(meetingStore.find(meeting.id)) }

    private fun version(meeting: MeetingRow): Long = asUser(secretary.account) { views.draft(meeting.id) }.version

    private fun told(
        person: Person,
        kind: String,
    ): Long = told(person.id, kind)

    private fun told(
        user: UUID,
        kind: String,
    ): Long =
        AppRoleJdbc.inTransaction(tenant.id) { c ->
            c.long("select count(*) from notification_item where user_id = ? and kind = ?", user, kind)
        }

    private companion object {
        val DHAKA: ZoneId = ZoneId.of("Asia/Dhaka")
        val START: LocalTime = LocalTime.of(10, 0)
        val END: LocalTime = LocalTime.of(13, 0)
        val MORNING: LocalTime = LocalTime.of(9, 0)
        val EVENING: LocalTime = LocalTime.of(22, 0)
        const val DAYS_AHEAD = 40L
        const val HOURS_AFTER_END = 20L
        const val AGENDA_DUE = "MEETING_AGENDA_DUE"
        const val NOT_HELD = "MEETING_NOT_HELD"
        const val WAIT_MILLIS = 30_000L
        const val POLL_MILLIS = 200L
    }
}
