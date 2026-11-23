package com.memo.demo

import com.memo.common.context.Actor
import com.memo.common.context.ActorContext
import com.memo.common.context.ActorScope
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantTransactions
import com.memo.demo.DemoMeetingSeeder.Companion.DIRECTOR
import com.memo.demo.DemoMeetingSeeder.Companion.FOR_DECISION
import com.memo.demo.DemoMeetingSeeder.Companion.FOR_NOTING
import com.memo.demo.DemoMeetingSeeder.Companion.HEAD_CREDIT
import com.memo.demo.DemoMeetingSeeder.Companion.HEAD_FINANCE
import com.memo.demo.DemoMeetingSeeder.Companion.HELD_TITLE
import com.memo.demo.DemoMeetingSeeder.Companion.MEGHNA
import com.memo.demo.DemoMeetingSeeder.Companion.PORTFOLIO
import com.memo.demo.DemoMeetingSeeder.Companion.SECRETARY
import com.memo.demo.DemoMeetingSeeder.Companion.WATCH_LIST
import com.memo.demo.DemoMeetingSeeder.Companion.ZONE
import com.memo.forum.ForumDirectory
import com.memo.forum.SubmissionPurpose
import com.memo.meeting.AgendaSeeding
import com.memo.meeting.AttendanceEventKind
import com.memo.meeting.AttendanceMode
import com.memo.meeting.AttendanceSeeding
import com.memo.meeting.DeclarationIntent
import com.memo.meeting.DeclarationSeeding
import com.memo.meeting.MeetingMode
import com.memo.meeting.MeetingSeeding
import com.memo.meeting.MeetingStatus
import com.memo.meeting.ReplySeeding
import com.memo.meeting.SeedItem
import com.memo.meeting.SeedReply
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

/**
 * Phase 4 E05 demo data (T10), for [DemoMeetingSeeder]: the risk review's replies and the credit committee's held
 * "Monthly credit review" with its register, quorum changes and a decision's quorum snapshot (see [DemoMeetingSeeder]).
 * Through the same services as the API, as the people who would act. [ids] are the demo people by email.
 */
@Component
class DemoMeetingAttendanceSeeder(
    private val meetings: MeetingSeeding,
    private val agendas: AgendaSeeding,
    private val declarations: DeclarationSeeding,
    private val replies: ReplySeeding,
    private val attendance: AttendanceSeeding,
    private val directory: ForumDirectory,
    private val transactions: TenantTransactions,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** The chair's yes for the date first planned, given before the risk review is brought forward (so it goes stale). */
    fun plannedDateAnswer(
        chair: UUID,
        meeting: UUID,
    ) {
        asUser(chair) { replies.answer(meeting, SeedReply.IN_PERSON) }
    }

    /**
     * The risk review's replies, each only when its person has no answer and the meeting is still scheduled: Demo Director
     * sends apologies and their alternate (Demo Head of Credit) stands in; the CFO, a guest, sends apologies; the secretary
     * records the Chairman's yes "by phone". The chair's stale yes is [plannedDateAnswer]'s, so only a newly seeded review
     * has it.
     */
    fun riskReplies(
        ids: Map<String, UUID>,
        forum: UUID,
        meeting: UUID,
        today: LocalDate,
    ) {
        val secretary = ids.getValue(SECRETARY)
        if (asUser(secretary) { meetings.status(meeting) } != MeetingStatus.SCHEDULED) return
        val unanswered = { user: UUID -> !asUser(secretary) { replies.answered(meeting, user) } }
        ids.getValue(DIRECTOR).takeIf(unanswered)?.let {
            asUser(it) { replies.answer(meeting, SeedReply.APOLOGIES, alternateAttends = true, note = DIRECTOR_APOLOGY) }
        }
        ids.getValue(HEAD_FINANCE).takeIf(unanswered)?.let {
            asUser(it) { replies.answer(meeting, SeedReply.APOLOGIES, note = FINANCE_APOLOGY) }
        }
        val chairman = seatHolder(secretary, forum, "BOD")
        if (chairman == null) {
            log.warn("Demo risk review: no Chairman for the answer by phone")
        } else if (unanswered(chairman)) {
            asUser(secretary) { replies.recordByPhone(meeting, chairman, SeedReply.IN_PERSON, "Phone call of $today") }
        }
    }

    /**
     * The credit committee's monthly review, held on the first Monday to Thursday from ten days before [today], 14:00 to
     * 16:00, hybrid, recorded as it happened; the meeting's id. Its Meghna Foods item is the credit memo's [meghna]
     * submission placed from the queue (E06 T12: the decision moves the memo on), or an item of its own without one.
     */
    fun heldCreditReview(
        ids: Map<String, UUID>,
        forum: UUID,
        today: LocalDate,
        meghna: UUID? = null,
    ): UUID {
        val secretary = ids.getValue(SECRETARY)
        val director = ids.getValue(DIRECTOR)
        val credit = ids.getValue(HEAD_CREDIT)
        val finance = ids.getValue(HEAD_FINANCE)
        val day = DemoMeetingSeeder.weekday(today.minusDays(HELD_DAYS_AGO))
        val meeting =
            asUser(secretary) {
                val id = meetings.create(forum, HELD_TITLE, day.atTime(START), day.atTime(END), ZONE.id, ROOM, MeetingMode.HYBRID, JOIN_URL)
                if (meghna != null) {
                    agendas.place(id, meghna, DECISION_MINUTES)
                } else {
                    agendas.item(
                        id,
                        SeedItem(
                            FOR_DECISION,
                            MEGHNA,
                            SubmissionPurpose.DECISION,
                            minutes = DECISION_MINUTES,
                            description = "A BDT 80 million working capital limit for Meghna Foods Ltd, renewed for a year.",
                            resolutionSought = "That the committee renews the limit for twelve months.",
                        ),
                    )
                }
                agendas.item(id, SeedItem(FOR_DECISION, WATCH_LIST, SubmissionPurpose.DECISION, minutes = DECISION_MINUTES))
                agendas.item(id, SeedItem(FOR_NOTING, PORTFOLIO, minutes = NOTING_MINUTES))
                agendas.agreedOnBehalf(id, "In person on $day")
                meetings.schedule(id)
                id
            }
        // The memo's papers were placed after the paper deadline: the chair agreed them before the agenda went out.
        asUser(director) { agendas.agreeLatePapers(meeting) }
        asUser(secretary) { agendas.publish(meeting, null) }
        asUser(director) { replies.answer(meeting, SeedReply.IN_PERSON) }
        asUser(finance) { replies.answer(meeting, SeedReply.IN_PERSON) }
        asUser(credit) { replies.answer(meeting, SeedReply.ONLINE) }
        val item = { title: String -> checkNotNull(asUser(secretary) { declarations.item(meeting, title) }) { "item $title" } }
        val meghnaItem = item(MEGHNA)
        // Not twice: an interest declared on the memo at the desk would already be carried to the item (E04).
        if (!asUser(secretary) { declarations.held(meeting, meghnaItem, credit) }) {
            asUser(credit) { declarations.declare(meeting, meghnaItem, MEGHNA_INTEREST, DeclarationIntent.LEAVE) }
        }
        val cro = seatHolder(secretary, forum, "RSK")
        asUser(secretary) {
            attendance.start(meeting)
            register(meeting, day, item, Attendees(secretary, director, credit, finance, cro))
            attendance.snapshot(meeting, meghnaItem, DemoMeetingSeeder.at(day, SNAPSHOT_AT))
            attendance.end(meeting)
        }
        return meeting
    }

    /** The current holder of the [unit] head's seat on [forum], if any, as [secretary] reads the roster. */
    fun seatHolder(
        secretary: UUID,
        forum: UUID,
        unit: String,
    ): UUID? =
        asUser(secretary) {
            transactions.inTenant(DemoTenant.ID) {
                directory.membersOn(forum).firstOrNull { it.positionId == DemoOrg.unitByCode(unit).headPosition }?.userId
            }
        }

    /**
     * The people of the held review's register: the secretary, the chair, the vice-chair (online), a member and the CRO's
     * seat holder.
     */
    private data class Attendees(
        val secretary: UUID,
        val chair: UUID,
        val viceChair: UUID,
        val member: UUID,
        val cro: UUID?,
    )

    /**
     * The held review's register, in time order as the secretary would have kept it: everyone but the CRO by 14:00 (quorate:
     * 3 of 4, the chair present), the CRO late during matters arising, the vice-chair out of the room for the Meghna Foods item
     * they declared an interest in, the head of finance leaving early, and the vice-chair's connection dropping at 15:30 (quorum
     * lost, 2 of 4) and back at 15:38 (restored).
     */
    private fun register(
        meeting: UUID,
        day: LocalDate,
        item: (String) -> UUID,
        people: Attendees,
    ) {
        val at = { time: String -> DemoMeetingSeeder.at(day, LocalTime.parse(time)) }
        val arrived = { user: UUID, mode: AttendanceMode, time: String ->
            attendance.record(meeting, user, AttendanceEventKind.ARRIVED, at(time), mode)
        }
        val now = { title: String, time: String -> attendance.progress(meeting, item(title), at(time)) }
        arrived(people.secretary, AttendanceMode.IN_PERSON, "13:50")
        arrived(people.chair, AttendanceMode.IN_PERSON, "13:55")
        arrived(people.member, AttendanceMode.IN_PERSON, "13:57")
        arrived(people.viceChair, AttendanceMode.REMOTE, "14:00")
        now("Welcome and apologies", "14:02")
        now("Declarations of interest", "14:05")
        now("Minutes of the last meeting", "14:08")
        now("Matters arising and actions", "14:12")
        if (people.cro == null) log.warn("Demo held credit review: no Chief Risk Officer for the late arrival")
        people.cro?.let { arrived(it, AttendanceMode.IN_PERSON, "14:20") }
        now(MEGHNA, "14:25")
        val meghna = item(MEGHNA)
        attendance.record(meeting, people.viceChair, AttendanceEventKind.STEPPED_OUT, at("14:25"), itemId = meghna)
        attendance.record(meeting, people.viceChair, AttendanceEventKind.STEPPED_BACK, at("14:49"), itemId = meghna)
        now(WATCH_LIST, "14:50")
        attendance.record(meeting, people.member, AttendanceEventKind.LEFT, at("15:15"))
        now(PORTFOLIO, "15:20")
        attendance.record(meeting, people.viceChair, AttendanceEventKind.LEFT, at("15:30"))
        arrived(people.viceChair, AttendanceMode.REMOTE, "15:38")
        now("Any other business (notified in advance)", "15:45")
    }

    private fun <T> asUser(
        user: UUID,
        block: () -> T,
    ): T = ActorContext.call(ActorScope(Actor.User(user), null)) { TenantContext.call(DemoTenant.ID, block) }

    companion object {
        const val MEGHNA_INTEREST = "My wife's family holds shares in Meghna Foods."
        const val DIRECTOR_APOLOGY = "Travelling for the regional review that week."
        const val FINANCE_APOLOGY = "Away at the regulator's annual review that day."

        /** When the Meghna Foods decision was taken, on the held review's day. */
        val SNAPSHOT_AT: LocalTime = LocalTime.parse("14:45")
        private val START = LocalTime.parse("14:00")
        private val END = LocalTime.parse("16:00")
        private const val ROOM = "Credit Committee Room, Level 9, Head Office"
        private const val JOIN_URL = "https://meet.demo.example/credit-committee"
        private const val DECISION_MINUTES = 20
        private const val NOTING_MINUTES = 15

        /** The held review was about ten days ago (the first Monday to Thursday from then). */
        private const val HELD_DAYS_AGO = 10L
    }
}
