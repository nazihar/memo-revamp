package com.memo.meeting

import com.memo.audit.AuditLog
import com.memo.common.authz.AccessAdministration
import com.memo.meeting.internal.AgendaLateness
import com.memo.meeting.internal.AgendaNoticeScheduler
import com.memo.meeting.internal.DeclarationReminders
import com.memo.meeting.internal.MeetingInput
import com.memo.meeting.internal.MeetingLifecycle
import com.memo.meeting.internal.MeetingNotifier
import com.memo.meeting.internal.MeetingPlace
import com.memo.meeting.internal.MeetingReads
import com.memo.meeting.internal.MeetingReminders
import com.memo.meeting.internal.MeetingRow
import com.memo.meeting.internal.MeetingStore
import com.memo.meeting.internal.MeetingSweep
import com.memo.meeting.internal.MeetingSweepStore
import com.memo.meeting.internal.TimesInput
import com.memo.meeting.internal.attendance.Attendance
import com.memo.meeting.internal.attendance.AttendanceInput
import com.memo.meeting.internal.attendance.AttendanceReads
import com.memo.meeting.internal.attendance.AttendanceStore
import com.memo.meeting.internal.invite.InvitationPanel
import com.memo.meeting.internal.invite.InvitationSafetyNet
import com.memo.meeting.internal.invite.InvitationScheduler
import com.memo.meeting.internal.quorum.QuorumReads
import com.memo.meeting.internal.rsvp.Replies
import com.memo.testsupport.MutableClock
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.beans.factory.annotation.Autowired
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * E05's read paths at the design's volume (test plan E05 T6 `InvitationVolumeIT`, design E05 §8): [MeetingVolume] with its
 * E05 rows (144,000 invitees, 430,000 messages, 144,000 replies and 190,000 reply events, 290,000 attendance events,
 * 60,000 snapshots; the weekly `load` stage sets `MEETING_VOLUME`, and the test is skipped without it), measured through
 * the services each endpoint calls, with every statement's plan checked. The focal meeting (40 invited, 60 items) answers
 * the invitations panel, the replies and the forecast; the first committee's last held meeting (39 people, about 300
 * events) the register and the quorum of an item; a meeting started for the test the attendance writes, the live quorum
 * and the snapshots; the director "My meetings" with answers; and the hourly sweep a pass over the tenant.
 */
@EnabledIfEnvironmentVariable(named = "MEETING_VOLUME", matches = "[1-9][0-9]*")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class InvitationVolumeIT : MeetingVolumeSupport() {
    @Autowired lateinit var replies: Replies

    @Autowired lateinit var panel: InvitationPanel

    @Autowired lateinit var quorumReads: QuorumReads

    @Autowired lateinit var attendanceReads: AttendanceReads

    @Autowired lateinit var attendance: Attendance

    @Autowired lateinit var attendanceStore: AttendanceStore

    @Autowired lateinit var snapshots: QuorumSnapshots

    @Autowired lateinit var reads: MeetingReads

    @Autowired lateinit var lifecycle: MeetingLifecycle

    @Autowired lateinit var meetingStore: MeetingStore

    @Autowired lateinit var invitationJobs: InvitationScheduler

    @Autowired lateinit var agendaJobs: AgendaNoticeScheduler

    @Autowired lateinit var sweepStore: MeetingSweepStore

    @Autowired lateinit var lateness: AgendaLateness

    @Autowired lateinit var notifier: MeetingNotifier

    @Autowired lateinit var access: AccessAdministration

    @Autowired lateinit var safetyNet: InvitationSafetyNet

    @Autowired lateinit var meetingReminders: MeetingReminders

    @Autowired lateinit var declarationReminders: DeclarationReminders

    @Autowired lateinit var auditLog: AuditLog

    override val large: List<String> = MeetingVolumeIT.LARGE + E05_LARGE

    /** A meeting of the first committee that started half an hour ago, with about 300 events on its register. */
    private lateinit var running: MeetingRow
    private lateinit var heldItem: UUID
    private val present = mutableListOf<UUID>()

    @BeforeAll
    fun generate() {
        // Nothing here waits for the jobs, and other test contexts share the scheduler.
        invitationJobs.jobs = false
        agendaJobs.jobs = false
        generate("invitevol", e05 = true)
        heldItem = volume.generated("mvi${volume.held}-1-0")
        running = started()
    }

    @AfterAll
    fun jobsBack() {
        invitationJobs.jobs = true
        agendaJobs.jobs = true
    }

    @Test
    @Order(1)
    fun `the replies, the invitations panel and the forecast of the focal meeting answer within budget`() {
        val list = asUser(people.secretary) { replies.list(volume.focal) }
        assertThat(checkNotNull(list.people)).hasSizeGreaterThanOrEqualTo(INVITED - 4)
        measure("replies (secretary, 40 invited)", REPLIES_BUDGET_MS, people.secretary) { replies.list(volume.focal) }
        val invitations = asUser(people.secretary) { panel.panel(volume.focal) }
        assertThat(invitations.people).hasSizeGreaterThanOrEqualTo(INVITED - 1)
        assertThat(invitations.people.mapNotNull { it.delivery }).isNotEmpty
        measure("invitations panel (secretary, 39 invitees, 117 messages)", PANEL_BUDGET_MS, people.secretary) {
            panel.panel(volume.focal)
        }
        val forecast = asUser(people.secretary) { quorumReads.forecast(volume.focal) }
        assertThat(forecast.items).hasSizeGreaterThanOrEqualTo(FOCAL_ITEMS - 5)
        measure("forecast (60 items)", FORECAST_BUDGET_MS, people.secretary) { quorumReads.forecast(volume.focal) }
    }

    @Test
    @Order(2)
    fun `the register and an item's quorum of the held meeting answer within budget`() {
        val register = asUser(people.secretary) { attendanceReads.register(volume.held) }
        assertThat(checkNotNull(register.events).size).isGreaterThan(HELD_EVENTS)
        measure("attendance register (held, 39 people, about 300 events)", REGISTER_BUDGET_MS, people.secretary) {
            attendanceReads.register(volume.held)
        }
        val during = asUser(people.secretary) { meetingStore.find(volume.held) }!!.startsAt.plus(Duration.ofMinutes(STEPPED_OUT_AT))
        val item = asUser(people.secretary) { attendanceReads.quorum(volume.held, during, heldItem) }
        assertThat(item.present).isLessThan(asUser(people.secretary) { attendanceReads.quorum(volume.held, during) }.present)
        measure("quorum of an item (held, 39 seats)", QUORUM_BUDGET_MS, people.secretary) {
            attendanceReads.quorum(volume.held, during, heldItem)
        }
    }

    @Test
    @Order(3)
    fun `the live quorum, an attendance event and a snapshot of a meeting in progress answer within budget`() {
        assertThat(asUser(people.secretary) { attendanceReads.quorum(running.id, null) }.quorate).isTrue()
        measure("live quorum (in progress)", QUORUM_BUDGET_MS, people.secretary) { attendanceReads.quorum(running.id, null) }
        // One member leaves and comes back, again and again: each write re-derives their row and evaluates the quorum.
        val writes =
            (1..WARM_UP + RUNS).map { run ->
                val kind = if (run % 2 == 1) AttendanceEventKind.LEFT else AttendanceEventKind.ARRIVED
                val mode = AttendanceMode.IN_PERSON.takeIf { kind == AttendanceEventKind.ARRIVED }
                timed { record(present.first(), kind, mode, null) }
            }
        report("attendance event (in progress, 300 events)", writes.drop(WARM_UP), EVENT_BUDGET_MS)
        measure("snapshot (take, inside the caller's transaction)", SNAPSHOT_BUDGET_MS, people.secretary, write = true, inside = true) {
            snapshots.take(running.id)
        }
    }

    @Test
    @Order(4)
    fun `my meetings with answers and a pass of the hourly sweep answer within budget`() {
        assertThat(asUser(people.director) { reads.mine(null) }.mapNotNull { it.rsvp }).isNotEmpty
        measure("my meetings with answers (director)", MINE_BUDGET_MS, people.director) { reads.mine(null) }
        // At 18:00 in Dhaka: the meeting reminders of tomorrow's meetings, the replies due today, quorum at risk.
        val clock =
            MutableClock(
                LocalDate
                    .now(DHAKA)
                    .atTime(LocalTime.of(18, 0))
                    .atZone(DHAKA)
                    .toInstant(),
            )
        val sweep =
            MeetingSweep(sweepStore, lateness, notifier, access, safetyNet, meetingReminders, declarationReminders, auditLog, clock)
        measure("sweep pass (one tenant, 3,600 meetings)", SWEEP_BUDGET_MS, people.admin, write = true) { sweep.sweep() }
    }

    /**
     * A meeting of the first committee from half an hour ago, started, with its 39 people on the register: six send
     * apologies, the rest arrive (the chair among them), and 30 members leave and come back four times (about 300 events).
     */
    private fun started(): MeetingRow {
        val start = LocalDateTime.now(DHAKA).truncatedTo(ChronoUnit.MINUTES).minusMinutes(RUNNING_FOR)
        val created =
            asUser(people.secretary, write = true) {
                lifecycle.create(
                    focalForum,
                    MeetingInput(MeetingKind.ORDINARY, MeetingPlace(null, MeetingMode.IN_PERSON, "Board room", null, null), levels.low),
                    TimesInput(start, start.plusHours(3), DHAKA.id),
                )
            }
        val scheduled = asUser(people.secretary, write = true) { lifecycle.schedule(created.id, created.version) }
        val meeting = asUser(people.secretary, write = true) { lifecycle.start(scheduled.id, scheduled.version) }
        val members = (1..MeetingVolume.FOCAL_MEMBERS).map { volume.generated("mvu1-$it") }
        val now = Instant.now()
        members.take(APOLOGIES).forEach { record(it, AttendanceEventKind.APOLOGIES, null, null, meeting) }
        present += members.drop(APOLOGIES)
        asUser(people.secretary, write = true) {
            attendance.arrivals(
                meeting.id,
                present + people.chair + people.secretary + people.director,
                AttendanceMode.IN_PERSON,
                now.minus(Duration.ofMinutes(ARRIVED_AGO)),
            )
        }
        present.take(CYCLING).forEach { user ->
            (0 until CYCLES).forEach { c ->
                val left = now.minus(Duration.ofMinutes(ARRIVED_AGO - 1L - 4 * c))
                record(user, AttendanceEventKind.LEFT, null, left, meeting)
                record(user, AttendanceEventKind.ARRIVED, AttendanceMode.IN_PERSON, left.plus(Duration.ofMinutes(2)), meeting)
            }
        }
        return meeting
    }

    private fun record(
        user: UUID,
        kind: AttendanceEventKind,
        mode: AttendanceMode?,
        at: Instant?,
        meeting: MeetingRow = running,
    ) {
        asUser(people.secretary, write = true) {
            attendance.record(meeting.id, attendanceStore.row(meeting.id, user)?.version, AttendanceInput(user, kind, mode, at))
        }
    }

    private companion object {
        val DHAKA: ZoneId = ZoneId.of("Asia/Dhaka")
        const val INVITED = 39
        const val FOCAL_ITEMS = 60
        const val HELD_EVENTS = 250
        const val STEPPED_OUT_AT = 105L
        const val RUNNING_FOR = 30L
        const val ARRIVED_AGO = 25L
        const val APOLOGIES = 6
        const val CYCLING = 30
        const val CYCLES = 4

        // Design E05 §8.
        const val REPLIES_BUDGET_MS = 100L
        const val PANEL_BUDGET_MS = 150L
        const val FORECAST_BUDGET_MS = 200L
        const val REGISTER_BUDGET_MS = 150L
        const val QUORUM_BUDGET_MS = 100L
        const val EVENT_BUDGET_MS = 200L
        const val SNAPSHOT_BUDGET_MS = 50L
        const val MINE_BUDGET_MS = 100L
        const val SWEEP_BUDGET_MS = 5_000L

        /** E05's large tables (design E05 §8). */
        val E05_LARGE =
            listOf(
                "meeting_invitation",
                "meeting_invitee",
                "meeting_invitation_message",
                "meeting_rsvp",
                "meeting_rsvp_event",
                "meeting_reminder",
                "meeting_attendance",
                "meeting_attendance_event",
                "meeting_quorum_change",
                "meeting_quorum_snapshot",
            )
    }
}
