package com.memo.minutes.internal

import com.memo.forum.ForumRole
import com.memo.forum.Outcome
import com.memo.forum.OutcomeKind
import com.memo.forum.QuorumRule
import com.memo.meeting.MeetingStatus
import com.memo.meeting.QuorumSnapshotInputs
import com.memo.meeting.QuorumSnapshotPresence
import com.memo.meeting.QuorumSnapshotSeat
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Each invariant of a decision with its problem code (test plan E06 T3 `DecisionRulesTest`, design E06 §1.1): the outcome
 * of the type, the text, conditions and targets, the vote, the people named against the snapshot (present, voting, not
 * recused; the counts), "took no part" derived from the snapshot, the time and the correction's reason.
 */
class DecisionRulesTest {
    private val outcomes =
        listOf(
            Outcome(OutcomeKind.APPROVED, "Approved", true),
            Outcome(OutcomeKind.APPROVED_WITH_CONDITIONS, "Approved with conditions", true),
            Outcome(OutcomeKind.DEFERRED, "Deferred", false),
            Outcome(OutcomeKind.REFERRED, "Referred to another committee", false),
            Outcome(OutcomeKind.NOTED, "Noted", false),
        )
    private val date: LocalDate = LocalDate.of(2026, 12, 12)

    private fun codes(input: DecisionInput): List<String> = DecisionChecks.content(input, outcomes, date).second.map { it.code }

    private fun fields(input: DecisionInput): List<String> = DecisionChecks.content(input, outcomes, date).second.map { it.field }

    @Test
    fun `the outcome must be one of the committee type's`() {
        assertThat(codes(DecisionInput(OutcomeKind.REJECTED))).containsExactly("OUTCOME_NOT_OFFERED")
        assertThat(codes(DecisionInput(null))).containsExactly("OUTCOME_NOT_OFFERED")
        val (content, problems) = DecisionChecks.content(DecisionInput(OutcomeKind.NOTED), outcomes, date)
        assertThat(problems).isEmpty()
        assertThat(content?.outcome?.label).isEqualTo("Noted")
    }

    @Test
    fun `a resolution needs its text, conditions need a line each, and validity is not before the meeting`() {
        assertThat(codes(DecisionInput(OutcomeKind.APPROVED))).containsExactly("TEXT_REQUIRED")
        assertThat(codes(DecisionInput(OutcomeKind.APPROVED, text = "   "))).containsExactly("TEXT_REQUIRED")
        assertThat(codes(DecisionInput(OutcomeKind.APPROVED, text = "x".repeat(4001)))).containsExactly("LENGTH")
        assertThat(codes(DecisionInput(OutcomeKind.APPROVED_WITH_CONDITIONS, text = "Resolved"))).containsExactly("CONDITIONS_REQUIRED")
        val lines =
            DecisionInput(OutcomeKind.APPROVED_WITH_CONDITIONS, text = "Resolved", conditions = listOf("Security", " ", "x".repeat(1001)))
        assertThat(fields(lines)).containsExactly("conditions/1", "conditions/2")
        val many = DecisionInput(OutcomeKind.APPROVED, text = "Resolved", conditions = (1..21).map { "Condition $it" })
        assertThat(codes(many)).containsExactly("RANGE")
        assertThat(
            fields(DecisionInput(OutcomeKind.APPROVED, text = "Resolved", validUntil = date.minusDays(1))),
        ).containsExactly("validUntil")
        assertThat(codes(DecisionInput(OutcomeKind.APPROVED, text = "Resolved", validUntil = date.plusDays(90)))).isEmpty()
    }

    @Test
    fun `a deferral and a referral carry their target`() {
        assertThat(codes(DecisionInput(OutcomeKind.DEFERRED))).containsExactly("TARGET_REQUIRED")
        assertThat(codes(DecisionInput(OutcomeKind.DEFERRED, deferTo = DeferTarget(next = true)))).isEmpty()
        assertThat(codes(DecisionInput(OutcomeKind.DEFERRED, deferTo = DeferTarget(date = date)))).containsExactly("RANGE")
        assertThat(codes(DecisionInput(OutcomeKind.DEFERRED, deferTo = DeferTarget(UUID.randomUUID(), date.plusDays(7)))))
            .containsExactly("TARGET_REQUIRED")
        assertThat(codes(DecisionInput(OutcomeKind.REFERRED))).containsExactly("TARGET_REQUIRED", "REASON_REQUIRED")
        val referred = DecisionInput(OutcomeKind.REFERRED, referToForumId = UUID.randomUUID(), referReason = "Credit limits")
        assertThat(codes(referred)).isEmpty()
        // A target given with another outcome is dropped.
        val content = DecisionChecks.content(DecisionInput(OutcomeKind.NOTED, deferTo = DeferTarget(next = true)), outcomes, date).first
        assertThat(content?.deferTo).isNull()
    }

    @Test
    fun `counts come with a vote, never below zero, and names once each and never both`() {
        assertThat(codes(DecisionInput(OutcomeKind.NOTED, votesFor = 3))).containsExactly("VOTE_MODE")
        assertThat(codes(DecisionInput(OutcomeKind.NOTED, voteMode = VoteMode.VOTE, votesAgainst = -1))).containsExactly("RANGE")
        val a = UUID.randomUUID()
        assertThat(codes(DecisionInput(OutcomeKind.NOTED, dissent = listOf(Dissent(a), Dissent(a))))).containsExactly("DUPLICATE")
        assertThat(codes(DecisionInput(OutcomeKind.NOTED, dissent = listOf(Dissent(a)), abstentions = listOf(a)))).containsExactly("BOTH")
        assertThat(
            fields(DecisionInput(OutcomeKind.NOTED, dissent = listOf(Dissent(a, "x".repeat(1001))))),
        ).containsExactly("dissent/0/reason")
    }

    @Test
    fun `the people named are present for the item, in a voting seat, and not recused, and the counts add up`() {
        val s = Scene()
        val content = { dissent: List<UUID>, abstaining: List<UUID>, votes: Triple<Int?, Int?, Int?> ->
            DecisionContent(
                outcomes[0],
                "Resolved",
                emptyList(),
                null,
                null,
                null,
                null,
                VoteMode.VOTE,
                votes.first,
                votes.second,
                votes.third,
                dissent.map { Dissent(it) },
                abstaining,
            )
        }
        val none = Triple<Int?, Int?, Int?>(null, null, null)
        assertThat(DecisionRules.people(content(listOf(s.member2), emptyList(), Triple(4, 1, 0)), s.inputs)).isEmpty()
        assertThat(
            DecisionRules.people(content(emptyList(), listOf(s.recused), none), s.inputs).map { it.code },
        ).containsExactly("RECUSED_NAMED")
        assertThat(
            DecisionRules.people(content(listOf(s.absent), emptyList(), none), s.inputs).map { it.code },
        ).containsExactly("NOT_PRESENT")
        assertThat(DecisionRules.people(content(listOf(s.steppedOut), emptyList(), none), s.inputs).map { it.code })
            .containsExactly("NOT_PRESENT")
        assertThat(
            DecisionRules.people(content(listOf(s.secretary), emptyList(), none), s.inputs).map { it.code },
        ).containsExactly("NOT_VOTING")
        assertThat(DecisionRules.people(content(listOf(UUID.randomUUID()), emptyList(), none), s.inputs).map { it.code })
            .containsExactly("NOT_VOTING")
        // The alternate standing in votes for the seat.
        assertThat(DecisionRules.people(content(listOf(s.alternate), emptyList(), none), s.inputs)).isEmpty()
        // Five may vote: the chair, two members, the alternate and one more; the recused member does not.
        assertThat(DecisionRules.voters(s.inputs)).hasSize(5)
        assertThat(DecisionRules.people(content(emptyList(), emptyList(), Triple(5, 1, 0)), s.inputs).map { it.code })
            .containsExactly("TOO_MANY_VOTES")
        assertThat(DecisionRules.people(content(listOf(s.member2, s.member3), emptyList(), Triple(3, 1, 0)), s.inputs).map { it.code })
            .containsExactly("TOO_MANY_NAMED")
    }

    @Test
    fun `took no part is the members recused in the room and those stepped out, from the snapshot`() {
        val s = Scene()
        assertThat(DecisionRules.tookNoPart(s.inputs)).containsExactlyInAnyOrder(s.recused, s.steppedOut)
    }

    @Test
    fun `the time defaults to the item's start, else now while in progress, and lies within the meeting`() {
        val start = Instant.parse("2026-12-12T04:00:00Z")
        val window = start to start.plusSeconds(3 * 3600)
        val now = start.plusSeconds(1800)
        assertThat(DecisionRules.decidedAt(null, start.plusSeconds(600), MeetingStatus.IN_PROGRESS, window, now).first)
            .isEqualTo(start.plusSeconds(600))
        assertThat(DecisionRules.decidedAt(null, null, MeetingStatus.IN_PROGRESS, window, now).first).isEqualTo(now)
        assertThat(DecisionRules.decidedAt(null, null, MeetingStatus.HELD, window, now).second?.code).isEqualTo("TIME_REQUIRED")
        assertThat(DecisionRules.decidedAt(now.plusSeconds(600), null, MeetingStatus.IN_PROGRESS, window, now).second?.code)
            .isEqualTo("TIME_OUT_OF_RANGE")
        assertThat(DecisionRules.decidedAt(start.minusSeconds(7200), null, MeetingStatus.HELD, window, now).second?.code)
            .isEqualTo("TIME_OUT_OF_RANGE")
        val later = start.plusSeconds(10 * 3600)
        assertThat(DecisionRules.decidedAt(later.minusSeconds(60), null, MeetingStatus.HELD, window, later).second?.code)
            .isEqualTo("TIME_OUT_OF_RANGE")
    }

    @Test
    fun `a correction needs a reason`() {
        assertThat(DecisionRules.reason(null).second?.code).isEqualTo("REASON_REQUIRED")
        assertThat(DecisionRules.reason("x".repeat(501)).second?.code).isEqualTo("LENGTH")
        assertThat(DecisionRules.reason(" Wording agreed with the chair ").first).isEqualTo("Wording agreed with the chair")
    }

    /** A roster of seven voting seats and a secretary: one recused in the room, one stepped out, one absent, an alternate. */
    class Scene {
        val chair: UUID = UUID.randomUUID()
        val recused: UUID = UUID.randomUUID()
        val member2: UUID = UUID.randomUUID()
        val member3: UUID = UUID.randomUUID()
        val member4: UUID = UUID.randomUUID()
        val steppedOut: UUID = UUID.randomUUID()
        val absent: UUID = UUID.randomUUID()
        val apologies: UUID = UUID.randomUUID()
        val alternate: UUID = UUID.randomUUID()
        val secretary: UUID = UUID.randomUUID()
        private val seats =
            listOf(chair, recused, member2, member3, member4, steppedOut, absent, apologies, secretary).associateWith { UUID.randomUUID() }

        val inputs: QuorumSnapshotInputs =
            QuorumSnapshotInputs(
                meetingId = UUID.randomUUID(),
                itemId = UUID.randomUUID(),
                at = Instant.parse("2026-12-12T05:00:00Z"),
                typeId = UUID.randomUUID(),
                typeVersion = 1,
                rule = QuorumRule(),
                roster =
                    seats.map { (user, seat) ->
                        val role =
                            when (user) {
                                chair -> ForumRole.CHAIR
                                secretary -> ForumRole.SECRETARY
                                else -> ForumRole.MEMBER
                            }
                        QuorumSnapshotSeat(seat, user, role, user != secretary, emptyList())
                    },
                presence =
                    listOf(
                        chair,
                        member2,
                        member3,
                        member4,
                        secretary,
                    ).map { QuorumSnapshotPresence(seats.getValue(it), it, false, false, false) } +
                        QuorumSnapshotPresence(seats.getValue(recused), recused, false, false, true) +
                        QuorumSnapshotPresence(seats.getValue(apologies), alternate, false, true, false),
                recused = listOf(recused),
                steppedOut = listOf(steppedOut),
            )
    }
}
