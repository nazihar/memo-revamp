package com.memo.demo

import com.memo.common.context.Actor
import com.memo.common.context.ActorContext
import com.memo.common.context.ActorScope
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantTransactions
import com.memo.demo.DemoHistory.Committee
import com.memo.demo.DemoHistory.Presence
import com.memo.demo.DemoHistory.Seat
import com.memo.demo.DemoMeetingSeeder.Companion.BOARD_MEMBER
import com.memo.demo.DemoMeetingSeeder.Companion.DIRECTOR
import com.memo.demo.DemoMeetingSeeder.Companion.FOR_DECISION
import com.memo.demo.DemoMeetingSeeder.Companion.FOR_NOTING
import com.memo.demo.DemoMeetingSeeder.Companion.HEAD_CREDIT
import com.memo.demo.DemoMeetingSeeder.Companion.HEAD_FINANCE
import com.memo.demo.DemoMeetingSeeder.Companion.SECRETARY
import com.memo.demo.DemoMeetingSeeder.Companion.ZONE
import com.memo.forum.ForumDirectory
import com.memo.forum.OutcomeKind
import com.memo.forum.SubmissionPurpose
import com.memo.identity.UserAccounts
import com.memo.meeting.AgendaSeeding
import com.memo.meeting.AttendanceEventKind
import com.memo.meeting.AttendanceMode
import com.memo.meeting.AttendanceSeeding
import com.memo.meeting.DeclarationSeeding
import com.memo.meeting.MeetingMode
import com.memo.meeting.MeetingSeeding
import com.memo.meeting.ReplySeeding
import com.memo.meeting.SeedItem
import com.memo.meeting.SeedReply
import com.memo.minutes.DecisionSeeding
import com.memo.minutes.SeedDecision
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

/**
 * Phase 4 E08 T8 demo data: a year of held meetings of the Board Risk Committee (monthly) and the Management Credit
 * Committee (every other month) before the E05 and E06 held reviews, so committee analytics has something to show
 * (attendance per member over the year, the committees' decisions in the register). The plan is [DemoHistory]'s. Each
 * meeting goes the way the app would take it, through the same services as the API, as the people who would act: the
 * agenda from the committee's template with two items for decision and one for noting, agreed for the chair and
 * published; the members' replies (apologies, with the director's alternate standing in at two BRC meetings); the
 * register as the secretary kept it (late arrivals, early departures, one absence without apologies); the decisions at
 * their times; held, and the resolutions numbered by the release job.
 *
 * What the record cannot be told after the fact stays as it happened: the agendas are published, and the replies given,
 * on the day of seeding (so the "papers on time" figure counts these meetings late), and no minutes are drafted. Runs
 * after [DemoMeetingSeeder], so the meetings and resolutions it numbers keep their numbers. Fills gaps only: a history
 * meeting already there (by its title, "Risk review, March 2026", in any state) is left as it is; a step that cannot be
 * taken is logged and skipped.
 */
@Component
class DemoHistorySeeder(
    private val meetings: MeetingSeeding,
    private val agendas: AgendaSeeding,
    private val items: DeclarationSeeding,
    private val replies: ReplySeeding,
    private val attendance: AttendanceSeeding,
    private val decisions: DecisionSeeding,
    private val directory: ForumDirectory,
    private val accounts: UserAccounts,
    private val transactions: TenantTransactions,
    private val clock: Clock,
    @param:Value("\${memo.demo.password:}") private val demoPassword: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** Seeds the missing history meetings; their ids by title (new and found), or null without the demo password. */
    fun seed(): Map<String, UUID>? {
        if (demoPassword.isBlank()) return null
        val people = ActorContext.asSystem("demo-history") { transactions.inTenant(DemoTenant.ID) { people() } }
        val secretary = people.getValue(SECRETARY)
        val today = LocalDate.now(clock.withZone(ZONE))
        val seeded = mutableMapOf<String, UUID>()
        listOf(Committee.RISK to DemoForumSeeder.RISK, Committee.CREDIT to DemoForumSeeder.CREDIT).forEach { (committee, key) ->
            val forum = forumId(secretary, key)
            val seats = seats(committee, forum, people)
            val existing = asUser(secretary) { meetings.dates(forum) }
            DemoHistory.plan(committee, today, existing).forEach { planned ->
                val found = asUser(secretary) { meetings.find(forum, planned.title) }
                val id = found ?: step(planned.title) { held(forum, planned, seats) }
                if (id != null) seeded[planned.title] = id
            }
            // A month taken by a history meeting of an earlier run is left out of the plan above: find those too.
            DemoHistory.plan(committee, today).forEach { planned ->
                asUser(secretary) { meetings.find(forum, planned.title) }?.let { seeded.putIfAbsent(planned.title, it) }
            }
        }
        log.info("Demo committee history seeded: {} meetings", seeded.size)
        return seeded
    }

    /** The people of one history meeting: the secretary, the chair, and the holders of the varied seats. */
    private data class Seats(
        val secretary: UUID,
        val chair: UUID,
        val holders: Map<Seat, UUID>,
        /** Who stands in for the [Seat.THIRD] holder when they send apologies with their alternate (BRC only). */
        val alternate: UUID?,
        /** Demo users answer their invitations in the app; the others' answers are recorded by the secretary by phone. */
        val signsIn: Set<UUID>,
    )

    private fun seats(
        committee: Committee,
        forum: UUID,
        people: Map<String, UUID>,
    ): Seats {
        val secretary = people.getValue(SECRETARY)
        val cro = DemoOrg.unitByCode("RSK").headPosition
        val croHolder = { asUser(secretary) { transactions.inTenant(DemoTenant.ID) { directory.membersOn(forum) } } }
        val holderOf = { position: UUID -> croHolder().firstOrNull { it.positionId == position }?.userId }
        val holders =
            when (committee) {
                Committee.RISK -> {
                    mapOfNotNull(
                        Seat.SECOND to holderOf(DemoOrg.unitByCode("BOD").headPosition),
                        Seat.THIRD to people.getValue(DIRECTOR),
                        Seat.FOURTH to holderOf(cro),
                    )
                }

                Committee.CREDIT -> {
                    mapOfNotNull(
                        Seat.SECOND to people.getValue(HEAD_CREDIT),
                        Seat.THIRD to people.getValue(HEAD_FINANCE),
                        Seat.FOURTH to holderOf(cro),
                    )
                }
            }
        if (holders.size < Seat.entries.size) log.warn("Demo history of {}: a seat has no holder: {}", committee, holders.keys)
        return Seats(
            secretary = secretary,
            chair = people.getValue(if (committee == Committee.RISK) BOARD_MEMBER else DIRECTOR),
            holders = holders,
            alternate = if (committee == Committee.RISK) people.getValue(HEAD_CREDIT) else null,
            signsIn = people.values.toSet(),
        )
    }

    /** [planned] created, published, answered, held as it happened and decided; the meeting's id. */
    private fun held(
        forum: UUID,
        planned: DemoHistory.Planned,
        seats: Seats,
    ): UUID {
        val committee = planned.committee
        val day = planned.day
        val mode = if (Seat.SECOND.presence(planned) == Presence.ONLINE) MeetingMode.HYBRID else MeetingMode.IN_PERSON
        val meeting =
            asUser(seats.secretary) {
                val id =
                    meetings.create(
                        forum,
                        planned.title,
                        day.atTime(committee.start),
                        day.atTime(committee.end),
                        ZONE.id,
                        if (committee == Committee.RISK) RISK_ROOM else CREDIT_ROOM,
                        mode,
                        JOIN_URL.takeIf { mode == MeetingMode.HYBRID },
                    )
                planned.decisions.forEach { title -> agendas.item(id, SeedItem(FOR_DECISION, title, SubmissionPurpose.DECISION, 20)) }
                agendas.item(id, SeedItem(FOR_NOTING, planned.noting, minutes = 10))
                agendas.agreedOnBehalf(id, "By email on ${day.minusDays(AGREED_DAYS_BEFORE)}")
                meetings.schedule(id)
                agendas.publish(id, null)
                id
            }
        answer(meeting, planned, seats)
        val at = { time: LocalTime -> DemoMeetingSeeder.at(day, time) }
        asUser(seats.secretary) {
            attendance.start(meeting)
            register(meeting, planned, seats, at)
            attendance.end(meeting)
        }
        decide(meeting, planned, seats.secretary, at)
        return meeting
    }

    /** The replies before the meeting: yes, online, or apologies (with the alternate where planned); none from the absent. */
    private fun answer(
        meeting: UUID,
        planned: DemoHistory.Planned,
        seats: Seats,
    ) {
        val people = listOf(seats.chair to Presence.ON_TIME) + seats.holders.map { (seat, user) -> user to seat.presence(planned) }
        people.forEach { (user, presence) ->
            val reply =
                when (presence) {
                    Presence.ABSENT -> null
                    Presence.APOLOGIES, Presence.APOLOGIES_ALTERNATE -> SeedReply.APOLOGIES
                    Presence.ONLINE -> SeedReply.ONLINE
                    else -> SeedReply.IN_PERSON
                } ?: return@forEach
            if (user in seats.signsIn) {
                val alternate = presence == Presence.APOLOGIES_ALTERNATE && seats.alternate != null
                val note = APOLOGY.takeIf { reply == SeedReply.APOLOGIES }
                asUser(user) { replies.answer(meeting, reply, alternateAttends = alternate, note = note) }
            } else {
                asUser(seats.secretary) { replies.recordByPhone(meeting, user, reply, "Phone call to the office") }
            }
        }
    }

    /**
     * The register: the secretary and the chair from ten minutes before the start, everyone on time at the start, the
     * alternate in the director's place, the late at 25 minutes past, the absent marked so at the start; the items in turn;
     * those leaving early twenty minutes before the end.
     */
    private fun register(
        meeting: UUID,
        planned: DemoHistory.Planned,
        seats: Seats,
        at: (LocalTime) -> Instant,
    ) {
        val start = planned.committee.start
        val arrive = { user: UUID, time: LocalTime, mode: AttendanceMode ->
            attendance.record(meeting, user, AttendanceEventKind.ARRIVED, at(time), mode)
        }
        arrive(seats.secretary, start.minusMinutes(EARLY_MINUTES), AttendanceMode.IN_PERSON)
        arrive(seats.chair, start.minusMinutes(CHAIR_EARLY_MINUTES), AttendanceMode.IN_PERSON)
        seats.holders.forEach { (seat, user) ->
            when (seat.presence(planned)) {
                Presence.ON_TIME, Presence.LEFT_EARLY -> arrive(user, start, AttendanceMode.IN_PERSON)
                Presence.ONLINE -> arrive(user, start, AttendanceMode.REMOTE)
                Presence.APOLOGIES_ALTERNATE -> seats.alternate?.let { arrive(it, start, AttendanceMode.IN_PERSON) }
                Presence.ABSENT -> attendance.record(meeting, user, AttendanceEventKind.ABSENT, at(start.plusMinutes(2)))
                Presence.LATE, Presence.APOLOGIES -> Unit
            }
        }
        val item = { title: String -> checkNotNull(items.item(meeting, title)) { "item $title" } }
        val now = { title: String, time: LocalTime -> attendance.progress(meeting, item(title), at(time)) }
        now("Welcome and apologies", start.plusMinutes(2))
        now("Minutes of the last meeting", start.plusMinutes(MINUTES_ITEM_MINUTES))
        seats.holders.filter { (seat, _) -> seat.presence(planned) == Presence.LATE }.values.forEach {
            arrive(it, start.plus(DemoHistory.LATE_BY), AttendanceMode.IN_PERSON)
        }
        now(planned.decisions[0], start.plusMinutes(FIRST_DECISION_MINUTES - DISCUSSION_MINUTES))
        now(planned.decisions[1], start.plusMinutes(SECOND_DECISION_MINUTES - DISCUSSION_MINUTES))
        now(planned.noting, start.plusMinutes(NOTING_MINUTES))
        val end = planned.committee.end
        seats.holders.filter { (seat, _) -> seat.presence(planned) == Presence.LEFT_EARLY }.values.forEach {
            attendance.record(meeting, it, AttendanceEventKind.LEFT, at(end.minus(DemoHistory.EARLY_BY)))
        }
    }

    /** The two items approved and the report noted, at their times, unless already decided; numbered when held. */
    private fun decide(
        meeting: UUID,
        planned: DemoHistory.Planned,
        secretary: UUID,
        at: (LocalTime) -> Instant,
    ) {
        val start = planned.committee.start
        val approved = { title: String, minutes: Long ->
            title to SeedDecision(OutcomeKind.APPROVED, at(start.plusMinutes(minutes)), text = resolved(title))
        }
        val outcomes =
            listOf(
                approved(planned.decisions[0], FIRST_DECISION_MINUTES),
                approved(planned.decisions[1], SECOND_DECISION_MINUTES),
                planned.noting to SeedDecision(OutcomeKind.NOTED, at(start.plusMinutes(NOTING_MINUTES + NOTED_AFTER)), text = "Noted."),
            )
        outcomes.forEach { (title, decision) ->
            val item = asUser(secretary) { items.item(meeting, title) } ?: return@forEach
            if (asUser(secretary) { decisions.decided(item) }) return@forEach
            step("decision on $title") { asUser(secretary) { decisions.decide(meeting, item, decision) } }
        }
    }

    private fun forumId(
        secretary: UUID,
        key: String,
    ): UUID =
        checkNotNull(asUser(secretary) { transactions.inTenant(DemoTenant.ID) { directory.forumByKey(key) } }) {
            "demo committee $key missing"
        }.id

    private fun people(): Map<String, UUID> =
        listOf(SECRETARY, BOARD_MEMBER, DIRECTOR, HEAD_CREDIT, HEAD_FINANCE).associateWith {
            checkNotNull(accounts.findByEmail(it)) { "demo user $it missing" }.id.value
        }

    /** [block]'s answer, or null with a warning when it fails (the history's other meetings still go ahead). */
    @Suppress("TooGenericExceptionCaught")
    private fun <T> step(
        what: String,
        block: () -> T,
    ): T? =
        try {
            block()
        } catch (e: RuntimeException) {
            log.warn("Demo history: {} not seeded: {}", what, e.toString())
            null
        }

    private fun <T> asUser(
        user: UUID,
        block: () -> T,
    ): T = ActorContext.call(ActorScope(Actor.User(user), null)) { TenantContext.call(DemoTenant.ID, block) }

    private companion object {
        const val RISK_ROOM = "Board Room, Level 12, Head Office"
        const val CREDIT_ROOM = "Credit Committee Room, Level 9, Head Office"
        const val JOIN_URL = "https://meet.demo.example/credit-committee"
        const val APOLOGY = "Away on bank business that day."
        const val AGREED_DAYS_BEFORE = 10L
        const val EARLY_MINUTES = 10L
        const val CHAIR_EARLY_MINUTES = 8L
        const val MINUTES_ITEM_MINUTES = 8L
        const val DISCUSSION_MINUTES = 15L
        const val FIRST_DECISION_MINUTES = 45L
        const val SECOND_DECISION_MINUTES = 70L
        const val NOTING_MINUTES = 85L
        const val NOTED_AFTER = 5L
    }
}

private fun resolved(title: String) = "It was RESOLVED that the ${title.replaceFirstChar { it.lowercase() }} be approved as presented."

private fun Seat.presence(planned: DemoHistory.Planned): Presence = planned.presence.getValue(this)

private fun <K, V : Any> mapOfNotNull(vararg pairs: Pair<K, V?>): Map<K, V> = pairs.mapNotNull { (k, v) -> v?.let { k to it } }.toMap()
