package com.memo.meeting

import com.memo.forum.ForumRole
import com.memo.forum.internal.ForumMemberStore
import com.memo.forum.internal.MemberEdit
import com.memo.forum.internal.MemberInput
import com.memo.identity.UserAccount
import com.memo.meeting.internal.AgendaChair
import com.memo.meeting.internal.AgendaDraftViews
import com.memo.meeting.internal.AgendaItems
import com.memo.meeting.internal.AgendaNoticeScheduler
import com.memo.meeting.internal.AgendaOrigin
import com.memo.meeting.internal.AgendaPublisher
import com.memo.meeting.internal.AgendaStart
import com.memo.meeting.internal.ItemInput
import com.memo.meeting.internal.ItemKind
import com.memo.meeting.internal.MeetingInput
import com.memo.meeting.internal.MeetingPlace
import com.memo.meeting.internal.MeetingRow
import com.memo.meeting.internal.TimesInput
import com.memo.meeting.internal.attendance.Attendance
import com.memo.meeting.internal.attendance.AttendanceInput
import com.memo.meeting.internal.attendance.AttendanceReads
import com.memo.meeting.internal.attendance.Recorded
import com.memo.meeting.internal.rsvp.Replies
import com.memo.meeting.internal.rsvp.RsvpInput
import com.memo.testsupport.aPassword
import com.memo.testsupport.anActiveUser
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * The invitation committee (secretary, chair, two directors) grown to seven voting members, all independent, with an
 * alternate for the chair's seat and for director's: the Board committee preset's rule then reads "4 of 7, including the
 * chair or a vice-chair, a majority of them independent". Meetings start now (so arrivals carry real times) with two
 * published items; helpers record on the register as the secretary.
 */
abstract class AttendanceTestSupport : InvitationTestSupport() {
    @Autowired lateinit var attendance: Attendance

    @Autowired lateinit var attendanceReads: AttendanceReads

    @Autowired lateinit var register: MeetingAttendance

    @Autowired lateinit var attendanceStore: com.memo.meeting.internal.attendance.AttendanceStore

    @Autowired lateinit var progressStore: com.memo.meeting.internal.attendance.MeetingProgressStore

    @Autowired lateinit var replies: Replies

    @Autowired lateinit var memberStore: ForumMemberStore

    @Autowired lateinit var agendaStart: AgendaStart

    @Autowired lateinit var agendaItems: AgendaItems

    @Autowired lateinit var drafts: AgendaDraftViews

    @Autowired lateinit var chairing: AgendaChair

    @Autowired lateinit var publisher: AgendaPublisher

    @Autowired lateinit var agendaNotices: AgendaNoticeScheduler

    lateinit var others: List<UserAccount>
    lateinit var chairAlternate: UserAccount
    lateinit var alternate: UserAccount

    @BeforeEach
    fun sevenMembers() {
        agendaNotices.jobs = false
        chairAlternate = accounts.anActiveUser(transactions, tenant, aPassword())
        alternate = accounts.anActiveUser(transactions, tenant, aPassword())
        memberStore.let { store -> transactions.readInTenant(tenant.id) { store.members(brc) } }.forEach { seat ->
            val stand =
                when (seat.userId) {
                    chair.id.value -> chairAlternate.id.value
                    director.id.value -> alternate.id.value
                    else -> null
                }
            asUser(
                admin,
            ) { members.edit(brc, seat.id, seat.version, MemberEdit(seat.voting, setOf("independent"), stand, seat.termEndsOn, null)) }
        }
        others = (1..4).map { accounts.anActiveUser(transactions, tenant, aPassword()) }
        others.forEach { account ->
            asUser(
                admin,
            ) { members.add(brc, MemberInput(userId = account.id.value, role = ForumRole.MEMBER, attributes = setOf("independent"))) }
        }
    }

    @AfterEach
    fun agendaJobsBack() {
        agendaNotices.jobs = true
    }

    /** The seven voting members: the chair, the two directors and the four others. */
    val voting: List<UserAccount> get() = listOf(chair, director, director2) + others

    /**
     * A BRC meeting that started a minute ago and ends in three hours, with items "Credit proposal" and "Risk appetite"
     * published; [before] runs while it is scheduled (replies). Its items by title.
     */
    fun started(before: (MeetingRow) -> Unit = {}): Pair<MeetingRow, Map<String, UUID>> {
        val zone = ZoneId.of("Asia/Dhaka")
        val start = LocalDateTime.now(zone).truncatedTo(ChronoUnit.MINUTES).minusMinutes(1)
        val created =
            asUser(secretary) {
                lifecycle.create(
                    brc,
                    MeetingInput(
                        MeetingKind.ORDINARY,
                        MeetingPlace(null, MeetingMode.HYBRID, "Board room", "https://meet.example/x", null),
                        null,
                    ),
                    TimesInput(start, start.plusHours(3), zone.id),
                )
            }
        val items =
            asUser(secretary) {
                agendaStart.start(created.id, AgendaOrigin.TEMPLATE, null)
                val section =
                    drafts
                        .draft(created.id)
                        .sections
                        .single { it.title == "For decision" }
                        .id
                listOf("Credit proposal", "Risk appetite").associateWith { title ->
                    agendaItems.add(
                        created.id,
                        drafts.draft(created.id).version,
                        ItemInput(ItemKind.ITEM, sectionId = section, title = title, minutes = 15),
                    )
                }
            }
        val scheduled = asUser(secretary) { meeting(created.id).let { lifecycle.schedule(it.id, it.version) } }
        asUser(chair) { chairing.agree(created.id, drafts.draft(created.id).version, false, null) }
        asUser(secretary) { publisher.publish(created.id, drafts.draft(created.id).version, null, true) }
        before(scheduled)
        val now = meeting(created.id)
        return asUser(secretary) { lifecycle.start(now.id, now.version) } to items
    }

    fun answer(
        account: UserAccount,
        meeting: MeetingRow,
        input: RsvpInput,
    ) = asUser(account) { replies.answer(meeting.id, null, input) }

    /** [kind] for [account] by the secretary, with their row's current version as If-Match. */
    fun record(
        meeting: MeetingRow,
        account: UserAccount,
        kind: AttendanceEventKind,
        mode: AttendanceMode? = null,
        by: UserAccount = secretary,
        version: Long? = versionOf(meeting, account),
        input: (AttendanceInput) -> AttendanceInput = { it },
    ): Recorded = asUser(by) { attendance.record(meeting.id, version, input(AttendanceInput(account.id.value, kind, mode))) }

    /** Everyone in [accounts] arrived in person, in one action. */
    fun arrive(
        meeting: MeetingRow,
        accounts: List<UserAccount>,
        mode: AttendanceMode = AttendanceMode.IN_PERSON,
    ) = asUser(secretary) { attendance.arrivals(meeting.id, accounts.map { it.id.value }, mode, null) }

    /** [account]'s register row version, null when they have none. */
    fun versionOf(
        meeting: MeetingRow,
        account: UserAccount,
    ): Long? = transactions.readInTenant(tenant.id) { attendanceStore.row(meeting.id, account.id.value)?.version }

    fun person(
        meeting: MeetingRow,
        account: UserAccount,
    ) = checkNotNull(asUser(secretary) { attendanceReads.register(meeting.id) }.people).single { it.userId == account.id.value }
}
