package com.memo.demo

import com.memo.common.context.Actor
import com.memo.common.context.ActorContext
import com.memo.common.context.ActorScope
import com.memo.common.tenant.TenantContext
import com.memo.demo.DemoMeetingSeeder.Companion.BOARD_MEMBER
import com.memo.demo.DemoMeetingSeeder.Companion.CONSENT
import com.memo.demo.DemoMeetingSeeder.Companion.DIRECTOR
import com.memo.demo.DemoMeetingSeeder.Companion.FOR_DECISION
import com.memo.demo.DemoMeetingSeeder.Companion.SECRETARY
import com.memo.demo.DemoMeetingSeeder.Companion.ZONE
import com.memo.demo.DemoMinutesSeeder.Companion.CYBER
import com.memo.demo.DemoMinutesSeeder.Companion.INCIDENTS
import com.memo.demo.DemoMinutesSeeder.Companion.POLICY
import com.memo.demo.DemoMinutesSeeder.Companion.RISK_TITLE
import com.memo.demo.DemoMinutesSeeder.Companion.RUPSHA
import com.memo.demo.DemoMinutesSeeder.Companion.RUPSHA_INTEREST
import com.memo.forum.SubmissionPurpose
import com.memo.meeting.AgendaSeeding
import com.memo.meeting.AttendanceEventKind
import com.memo.meeting.AttendanceMode
import com.memo.meeting.AttendanceSeeding
import com.memo.meeting.DeclarationIntent
import com.memo.meeting.DeclarationSeeding
import com.memo.meeting.MeetingSeeding
import com.memo.meeting.SeedItem
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

/**
 * Phase 4 E06 T12 demo data, for [DemoMeetingSeeder]: the Board Risk Committee's held "Risk review, third quarter" (about
 * three and a half weeks ago, 10:00 to 12:00 in the board room), its agenda (two items for decision and a consent section
 * of two), Demo Director's declaration on the Rupsha Steel item and the register kept as it happened. Its decisions and
 * minutes are [DemoMinutesSeeder]'s. Through the same services as the API, as the people who would act.
 */
@Component
class DemoHeldRiskReviewSeeder(
    private val meetings: MeetingSeeding,
    private val agendas: AgendaSeeding,
    private val declarations: DeclarationSeeding,
    private val attendance: AttendanceSeeding,
    private val e05: DemoMeetingAttendanceSeeder,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** The BRC's held "Risk review, third quarter" with its agenda, declaration and register (see the class); its id. */
    fun heldRiskReview(
        ids: Map<String, UUID>,
        forum: UUID,
        today: LocalDate,
    ): UUID {
        val secretary = ids.getValue(SECRETARY)
        val director = ids.getValue(DIRECTOR)
        val day = DemoMeetingSeeder.weekday(today.minusDays(HELD_DAYS_AGO))
        val meeting = asUser(secretary) { riskAgenda(forum, day) }
        val item = { title: String -> checkNotNull(asUser(secretary) { declarations.item(meeting, title) }) { "item $title" } }
        asUser(director) { declarations.declare(meeting, item(RUPSHA), RUPSHA_INTEREST, DeclarationIntent.LEAVE) }
        val people = listOf(secretary, ids.getValue(BOARD_MEMBER), e05.seatHolder(secretary, forum, "BOD"), director)
        if (people[2] == null) log.warn("Demo held risk review: no Chairman on the committee")
        val arrivals = people.filterNotNull() + listOfNotNull(e05.seatHolder(secretary, forum, "RSK"))
        asUser(secretary) { riskRegister(meeting, day, item, arrivals, director) }
        return meeting
    }

    /** The held review's agenda, agreed for the chair and published, as the bound secretary; the meeting's id. */
    private fun riskAgenda(
        forum: UUID,
        day: LocalDate,
    ): UUID {
        val id = meetings.create(forum, RISK_TITLE, day.atTime(START), day.atTime(END), ZONE.id, ROOM)
        agendas.item(
            id,
            SeedItem(
                FOR_DECISION,
                RUPSHA,
                SubmissionPurpose.DECISION,
                minutes = DECISION_MINUTES,
                description = "A BDT 1.2 billion exposure to Rupsha Steel Mills Ltd, above the single-borrower limit.",
                resolutionSought = "That the committee approves the exposure above the single-borrower limit for a year.",
            ),
        )
        agendas.item(
            id,
            SeedItem(
                FOR_DECISION,
                CYBER,
                SubmissionPurpose.DECISION,
                minutes = DECISION_MINUTES,
                description = "Four metrics for the cyber risk appetite, with their amber and red levels.",
                resolutionSought = "That the committee adopts the cyber risk appetite metrics.",
            ),
        )
        agendas.item(
            id,
            SeedItem(
                CONSENT,
                POLICY,
                SubmissionPurpose.DECISION,
                resolutionSought = "That the committee approves the credit risk policy without changes.",
            ),
        )
        agendas.item(id, SeedItem(CONSENT, INCIDENTS))
        agendas.agreedOnBehalf(id, "By email on ${day.minusDays(AGREED_DAYS_BEFORE)}")
        meetings.schedule(id)
        agendas.publish(id, null)
        return id
    }

    /**
     * The held review's register as the bound secretary kept it: [people] (the secretary, the chair, the Chairman, Demo
     * Director, then the CRO) arriving from 09:50, the items in turn, the director out of the room for the Rupsha Steel
     * item; then held.
     */
    private fun riskRegister(
        meeting: UUID,
        day: LocalDate,
        item: (String) -> UUID,
        people: List<UUID>,
        director: UUID,
    ) {
        val at = { time: String -> DemoMeetingSeeder.at(day, LocalTime.parse(time)) }
        attendance.start(meeting)
        people.zip(listOf("09:50", "09:55", "09:57", "09:58", "10:00")).forEach { (user, time) ->
            attendance.record(meeting, user, AttendanceEventKind.ARRIVED, at(time), AttendanceMode.IN_PERSON)
        }
        val now = { title: String, time: String -> attendance.progress(meeting, item(title), at(time)) }
        now("Welcome and apologies", "10:02")
        now("Declarations of interest", "10:05")
        now("Minutes of the last meeting", "10:08")
        now("Matters arising and actions", "10:12")
        now(RUPSHA, "10:20")
        attendance.record(meeting, director, AttendanceEventKind.STEPPED_OUT, at("10:20"), itemId = item(RUPSHA))
        attendance.record(meeting, director, AttendanceEventKind.STEPPED_BACK, at("10:48"), itemId = item(RUPSHA))
        now(CYBER, "10:50")
        now(POLICY, "11:15")
        now("Any other business (notified in advance)", "11:25")
        attendance.end(meeting)
    }

    private fun <T> asUser(
        user: UUID,
        block: () -> T,
    ): T = ActorContext.call(ActorScope(Actor.User(user), null)) { TenantContext.call(DemoTenant.ID, block) }

    private companion object {
        val START: LocalTime = LocalTime.parse("10:00")
        val END: LocalTime = LocalTime.parse("12:00")
        const val ROOM = "Board Room, Level 12, Head Office"
        const val DECISION_MINUTES = 25
        const val AGREED_DAYS_BEFORE = 7L

        /** The held risk review was about three and a half weeks ago (the first Monday to Thursday from then). */
        const val HELD_DAYS_AGO = 24L
    }
}
