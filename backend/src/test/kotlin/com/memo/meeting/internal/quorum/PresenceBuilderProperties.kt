package com.memo.meeting.internal.quorum

import com.memo.forum.ForumRole
import com.memo.forum.ForumSeat
import com.memo.forum.Quorum
import com.memo.forum.QuorumComposition
import com.memo.forum.QuorumCounts
import com.memo.forum.QuorumRule
import com.memo.forum.QuorumThreshold
import com.memo.forum.ThresholdKind
import com.memo.meeting.AttendanceEventKind
import com.memo.meeting.AttendanceMode
import com.memo.meeting.AttendanceSource
import com.memo.meeting.AttendanceState
import com.memo.meeting.internal.attendance.AttendanceEventRow
import com.memo.meeting.internal.attendance.AttendanceRules
import com.memo.meeting.internal.attendance.Standing
import net.jqwik.api.Arbitraries
import net.jqwik.api.Arbitrary
import net.jqwik.api.Combinators
import net.jqwik.api.Example
import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.Provide
import org.assertj.core.api.Assertions.assertThat
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * The presence builder and the live quorum over random rosters, alternates and attendance (test plan E05 T4
 * `PresenceBuilderProperties`, ADR-0064): a person fills at most one seat, an alternate only while the member is absent,
 * never when either is recused from the item, and never a seat holder; arrivals never break a quorum, and departures never
 * make one but for a member online where that does not count, whose alternate counts once they leave; the evaluation at the
 * last event's time from the events equals the evaluation from the rows derived event by event (live equals recorded).
 */
class PresenceBuilderProperties {
    private val item: UUID = UUID(5, 5)

    @Property
    fun aPersonFillsAtMostOneSeatAndAnAlternateOnlyAnAbsentMembersSeat(
        @ForAll("meetings") meeting: Meeting,
    ) {
        listOf(null, item).forEach { scope ->
            val seats = PresenceBuilder.build(meeting.roster, meeting.standIns, meeting.present, scope, meeting.recused)
            assertThat(seats.map { it.userId }).doesNotHaveDuplicates()
            assertThat(seats.map { it.memberId }).doesNotHaveDuplicates()
            val holders = meeting.roster.map { it.userId }.toSet()
            seats.forEach { seat ->
                val roster = meeting.roster.single { it.memberId == seat.memberId }
                assertThat(seat.holderId).isEqualTo(roster.userId)
                val here = meeting.present[seat.userId]
                assertThat(here).describedAs("only people present fill seats").isNotNull()
                if (scope != null) assertThat(checkNotNull(here).steppedOutOf).isNotEqualTo(scope)
                if (seat.byAlternate) {
                    assertThat(meeting.standIns[seat.memberId]).isEqualTo(seat.userId)
                    assertThat(seat.userId).describedAs("a seat holder never stands in").isNotIn(holders)
                    val holder = meeting.present[seat.holderId]
                    assertThat(holder == null || (scope != null && holder.steppedOutOf == scope))
                        .describedAs("the alternate only while the member is absent")
                        .isTrue()
                    assertThat(seat.recused).isFalse()
                    if (scope != null) {
                        assertThat(seat.holderId).describedAs("never for a recused member").isNotIn(meeting.recused)
                        assertThat(seat.userId).describedAs("never a recused alternate").isNotIn(meeting.recused)
                    }
                } else {
                    assertThat(seat.userId).isEqualTo(seat.holderId)
                    assertThat(seat.recused).isEqualTo(scope != null && seat.userId in meeting.recused)
                }
            }
            // Every holder present (and in the room for the item) fills a seat.
            meeting.roster
                .map { it.userId }
                .distinct()
                .filter { user -> meeting.present[user]?.let { scope == null || it.steppedOutOf != scope } == true }
                .forEach { user -> assertThat(seats.map { it.userId }).contains(user) }
        }
    }

    @Property
    fun anArrivalNeverBreaksAQuorum(
        @ForAll("meetings") meeting: Meeting,
        @ForAll("rules") rule: QuorumRule,
    ) {
        // A member holding two seats who arrives silences both seats' alternates (ADR-0064: only while the member is absent).
        if (meeting.multiSeat) return
        val before = evaluate(rule, meeting, meeting.present)
        val absent = meeting.people.firstOrNull { it !in meeting.present } ?: return
        val after = evaluate(rule, meeting, meeting.present + (absent to PersonPresence()))
        if (before.quorate) assertThat(after.quorate).isTrue()
        assertThat(after.present).isGreaterThanOrEqualTo(before.present)
    }

    @Property
    fun aDepartureNeverMakesAMeetingQuorate(
        @ForAll("meetings") meeting: Meeting,
        @ForAll("rules") rule: QuorumRule,
    ) {
        // A member holding two seats who arrives silences both seats' alternates (ADR-0064: only while the member is absent).
        // The other exception (theOnlineMembersAlternateCountsOnceTheMemberLeaves): a member online where online attendance
        // does not count still takes part, so their alternate in the room does not count until they leave.
        val leaving = meeting.present.keys.firstOrNull()
        if (meeting.multiSeat || leaving == null || silencesTheirAlternate(rule, meeting, leaving)) return
        val before = evaluate(rule, meeting, meeting.present)
        val after = evaluate(rule, meeting, meeting.present - leaving)
        if (!before.quorate) assertThat(after.quorate).isFalse()
    }

    /**
     * The failing sample of seed -7426836235321309603 (2026-11-20, shrunk): two CHAIR seats; the first holder is away and
     * their alternate in the room, the second holder online (`remoteCounts` false) with their alternate in the room. The
     * online member takes part, so their alternate does not count (ADR-0064, model articles art. 26: only in the
     * appointor's absence): 1 of 2. When the online member leaves, their alternate counts: 2 of 2, quorate. A departure
     * making the meeting quorate is right here; the recusals of the sample play no part at the meeting level.
     */
    @Example
    fun theOnlineMembersAlternateCountsOnceTheMemberLeaves() {
        fun seat(
            i: Long,
            holder: Long,
            alternate: Long,
        ) = ForumSeat(
            memberId = UUID(1, i),
            forumId = UUID(9, 1),
            userId = UUID(2, holder),
            positionId = null,
            role = ForumRole.CHAIR,
            voting = true,
            attributes = emptySet(),
            alternateUserId = UUID(2, alternate),
            startsOn = LocalDate.of(2026, 1, 1),
            termEndsOn = null,
            termNumber = 1,
            until = null,
        )
        val roster = listOf(seat(0, 0, 1), seat(1, 2, 3))
        val meeting =
            Meeting(
                roster = roster,
                standIns = roster.associate { it.memberId to checkNotNull(it.alternateUserId) },
                present =
                    mapOf(
                        UUID(2, 2) to PersonPresence(remote = true),
                        UUID(2, 1) to PersonPresence(),
                        UUID(2, 3) to PersonPresence(),
                    ),
                recused = setOf(UUID(2, 0), UUID(2, 1), UUID(2, 2), UUID(2, 3)),
            )
        val rule = QuorumRule(minimum = 1, alternatesCount = true, remoteCounts = false, recusedCount = false)
        val before = evaluate(rule, meeting, meeting.present)
        assertThat(before.quorate to before.present).isEqualTo(false to 1)
        assertThat(silencesTheirAlternate(rule, meeting, UUID(2, 2))).isTrue()
        val after = evaluate(rule, meeting, meeting.present - UUID(2, 2))
        assertThat(after.quorate to after.present).isEqualTo(true to 2)
        val seats = PresenceBuilder.build(meeting.roster, meeting.standIns, meeting.present - UUID(2, 2))
        assertThat(seats.single { it.memberId == UUID(1, 1) }.let { it.userId to it.byAlternate }).isEqualTo(UUID(2, 3) to true)
    }

    /** [user] holds a seat, takes part online where that does not count, and the seat's alternate is present. */
    private fun silencesTheirAlternate(
        rule: QuorumRule,
        meeting: Meeting,
        user: UUID,
    ): Boolean =
        !rule.remoteCounts &&
            meeting.present[user]?.remote == true &&
            meeting.roster.any { seat -> seat.userId == user && meeting.standIns[seat.memberId]?.let { it in meeting.present } == true }

    @Property
    fun liveFromTheEventsEqualsTheRowsDerivedEventByEvent(
        @ForAll("meetings") meeting: Meeting,
        @ForAll("rules") rule: QuorumRule,
        @ForAll("walks") walks: List<Int>,
    ) {
        val events = walk(meeting.people, walks)
        // The rows as the service keeps them: one event at a time, each person's row from the one before.
        val rows = mutableMapOf<UUID, Standing>()
        events.forEach { event -> rows[event.userId] = AttendanceRules.next(rows[event.userId] ?: Standing(), event) }
        val fromRows =
            rows
                .filterValues { it.state == AttendanceState.PRESENT }
                .mapValues { PersonPresence(it.value.mode == AttendanceMode.REMOTE, it.value.steppedOutOf) }
        val last = events.lastOrNull()?.at
        assertThat(LiveQuorum.presentAt(events, last)).isEqualTo(fromRows)
        assertThat(LiveQuorum.presentAt(events, null)).isEqualTo(fromRows)
        assertThat(evaluate(rule, meeting, LiveQuorum.presentAt(events, last))).isEqualTo(evaluate(rule, meeting, fromRows))
    }

    private fun evaluate(
        rule: QuorumRule,
        meeting: Meeting,
        present: Map<UUID, PersonPresence>,
    ) = Quorum.evaluate(
        rule,
        PresenceBuilder.seats(meeting.roster),
        PresenceBuilder.attendance(PresenceBuilder.build(meeting.roster, meeting.standIns, present)),
    )

    /** Valid events for [people] from [choices]: each choice picks a person and one of the events allowed for them now. */
    private fun walk(
        people: List<UUID>,
        choices: List<Int>,
    ): List<AttendanceEventRow> {
        val states = mutableMapOf<UUID, Standing>()
        val events = mutableListOf<AttendanceEventRow>()
        val start = Instant.parse("2026-12-12T04:00:00Z")
        choices.forEachIndexed { i, choice ->
            val person = people[choice % people.size]
            val standing = states[person] ?: Standing()
            val options = options(standing)
            val kind = options.getOrNull((choice / people.size) % maxOf(1, options.size)) ?: return@forEachIndexed
            val mode =
                when (kind) {
                    AttendanceEventKind.ARRIVED -> {
                        if (choice % 2 == 0) AttendanceMode.IN_PERSON else AttendanceMode.REMOTE
                    }

                    AttendanceEventKind.MODE_CHANGED -> {
                        if (standing.mode ==
                            AttendanceMode.REMOTE
                        ) {
                            AttendanceMode.IN_PERSON
                        } else {
                            AttendanceMode.REMOTE
                        }
                    }

                    else -> {
                        null
                    }
                }
            val stepping = kind == AttendanceEventKind.STEPPED_OUT || kind == AttendanceEventKind.STEPPED_BACK
            val at = start.plusSeconds(60L * i)
            val event =
                AttendanceEventRow(
                    UUID(3, i.toLong()),
                    UUID(9, 9),
                    person,
                    kind,
                    mode,
                    at,
                    item.takeIf { stepping },
                    null,
                    null,
                    null,
                    AttendanceSource.RECORDED,
                    false,
                    null,
                    at,
                )
            states[person] = AttendanceRules.next(standing, event)
            events += event
        }
        return events
    }

    /** The events allowed for someone [standing] as they are. */
    private fun options(standing: Standing): List<AttendanceEventKind> =
        when (standing.state) {
            AttendanceState.EXPECTED -> {
                listOf(AttendanceEventKind.ARRIVED, AttendanceEventKind.APOLOGIES, AttendanceEventKind.ABSENT)
            }

            AttendanceState.APOLOGIES, AttendanceState.LEFT -> {
                listOf(AttendanceEventKind.ARRIVED)
            }

            AttendanceState.ABSENT -> {
                emptyList()
            }

            AttendanceState.PRESENT -> {
                listOf(AttendanceEventKind.LEFT, AttendanceEventKind.MODE_CHANGED) +
                    if (standing.steppedOutOf == null) AttendanceEventKind.STEPPED_OUT else AttendanceEventKind.STEPPED_BACK
            }
        }

    data class Meeting(
        val roster: List<ForumSeat>,
        val standIns: Map<UUID, UUID>,
        val present: Map<UUID, PersonPresence>,
        val recused: Set<UUID>,
    ) {
        val people: List<UUID> get() = (roster.map { it.userId } + standIns.values).distinct()

        /** Someone holds more than one seat. */
        val multiSeat: Boolean get() = roster.groupBy { it.userId }.any { it.value.size > 1 }
    }

    @Provide
    fun walks(): Arbitrary<List<Int>> =
        Arbitraries
            .integers()
            .between(0, 1_000)
            .list()
            .ofMaxSize(40)

    @Provide
    fun rules(): Arbitrary<QuorumRule> =
        Combinators
            .combine(
                Arbitraries.integers().between(1, 4),
                Arbitraries.of(true, false),
                Arbitraries.of(true, false),
                Arbitraries.of(QuorumCounts.VOTING, QuorumCounts.ALL),
                Arbitraries.of(emptyList(), listOf(listOf(ForumRole.CHAIR, ForumRole.VICE_CHAIR))),
                Arbitraries.of(emptyList(), listOf(QuorumComposition("independent", atLeast = 2))),
            ).`as` { minimum, alternates, remote, counts, required, composition ->
                QuorumRule(
                    counts = counts,
                    threshold = QuorumThreshold(ThresholdKind.MAJORITY),
                    minimum = minimum,
                    required = required,
                    composition = composition,
                    alternatesCount = alternates,
                    remoteCounts = remote,
                )
            }

    @Provide
    fun meetings(): Arbitrary<Meeting> {
        val seat =
            Combinators
                .combine(
                    Arbitraries.of(*ForumRole.entries.toTypedArray()),
                    Arbitraries.of(true, false),
                    Arbitraries.of(setOf(), setOf("independent")),
                    // Who holds it: one of 8 people (a holder may hold two seats); its alternate: none, an outsider, or a holder.
                    Arbitraries.integers().between(0, 7),
                    Arbitraries.integers().between(-1, 11),
                ).`as` { role, voting, attributes, holder, alternate -> Generated(role, voting, attributes, holder, alternate) }
        return seat.list().ofMinSize(1).ofMaxSize(9).flatMap { generated ->
            val roster =
                generated.mapIndexed { i, g ->
                    ForumSeat(
                        memberId = UUID(1, i.toLong()),
                        forumId = UUID(9, 1),
                        userId = UUID(2, g.holder.toLong()),
                        positionId = null,
                        role = g.role,
                        voting = g.voting,
                        attributes = g.attributes,
                        alternateUserId = g.alternate.takeIf { it >= 0 }?.let { UUID(2, it.toLong()) },
                        startsOn = LocalDate.of(2026, 1, 1),
                        termEndsOn = null,
                        termNumber = 1,
                        until = null,
                    )
                }
            // Standing in: some seats' alternates, each seat at most one, an alternate possibly for two seats.
            val standIns = roster.filter { it.alternateUserId != null && it.memberId.leastSignificantBits % 3 != 2L }
            val people = (roster.map { it.userId } + standIns.mapNotNull { it.alternateUserId }).distinct()
            val presence =
                Combinators
                    .combine(Arbitraries.of(true, false), Arbitraries.of(null, item))
                    .`as` { remote, out -> PersonPresence(remote, out) }
                    .injectNull(0.4)
            Combinators
                .combine(presence.list().ofSize(people.size), Arbitraries.of(true, false).list().ofSize(people.size))
                .`as` { presences, recusals ->
                    Meeting(
                        roster = roster,
                        standIns = standIns.associate { it.memberId to checkNotNull(it.alternateUserId) },
                        present = people.zip(presences).mapNotNull { (p, presence) -> presence?.let { p to it } }.toMap(),
                        recused =
                            people
                                .zip(recusals)
                                .filter { it.second }
                                .map { it.first }
                                .toSet(),
                    )
                }
        }
    }

    data class Generated(
        val role: ForumRole,
        val voting: Boolean,
        val attributes: Set<String>,
        val holder: Int,
        val alternate: Int,
    )
}
