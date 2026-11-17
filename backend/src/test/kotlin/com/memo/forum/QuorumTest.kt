package com.memo.forum

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * ADR-0057 building blocks against the clauses of published charters (research §3.3): each case is the rule written with
 * the vocabulary and a meeting that is, or is not, quorate under the clause.
 */
class QuorumTest {
    private fun seat(
        n: Int,
        role: ForumRole = ForumRole.MEMBER,
        voting: Boolean = role.votesByDefault,
        vararg attributes: String,
    ) = QuorumSeat(UUID(0, n.toLong()), role, voting, attributes.toSet())

    private fun present(vararg seats: QuorumSeat) = seats.associate { it.memberId to QuorumPresence() }

    @Test
    fun `majority of voting members, never fewer than two`() {
        val roster = (1..7).map { seat(it) }
        val rule = QuorumRule()
        assertThat(Quorum.evaluate(rule, roster, present(*roster.take(4).toTypedArray())).quorate).isTrue()
        val three = Quorum.evaluate(rule, roster, present(*roster.take(3).toTypedArray()))
        assertThat(three.quorate).isFalse()
        assertThat(three.needed).isEqualTo(4)
        assertThat(three.unmet.single().kind).isEqualTo(UnmetKind.COUNT)
        // A one-member forum still needs the floor of two.
        assertThat(Quorum.evaluate(rule, roster.take(1), present(roster[0])).needed).isEqualTo(2)
    }

    @Test
    fun `HSBC - two members including the chair or their delegate`() {
        val chair = seat(1, ForumRole.CHAIR)
        val vice = seat(2, ForumRole.VICE_CHAIR)
        val members = (3..6).map { seat(it) }
        val roster = listOf(chair, vice) + members
        val rule =
            QuorumRule(
                threshold = QuorumThreshold(ThresholdKind.COUNT, count = 2),
                required = listOf(listOf(ForumRole.CHAIR, ForumRole.VICE_CHAIR)),
            )
        assertThat(Quorum.evaluate(rule, roster, present(vice, members[0])).quorate).isTrue()
        val noChair = Quorum.evaluate(rule, roster, present(members[0], members[1]))
        assertThat(noChair.quorate).isFalse()
        assertThat(noChair.unmet.single().kind).isEqualTo(UnmetKind.ROLE)
        assertThat(noChair.unmet.single().roles).containsExactly(ForumRole.CHAIR, ForumRole.VICE_CHAIR)
    }

    @Test
    fun `Barclays - any two independent non-executive directors`() {
        val ined = (1..3).map { seat(it, ForumRole.MEMBER, true, "independent") }
        val exec = seat(4)
        val rule =
            QuorumRule(
                threshold = QuorumThreshold(ThresholdKind.COUNT, count = 2),
                composition = listOf(QuorumComposition("independent", atLeast = 2)),
            )
        val roster = ined + exec
        assertThat(Quorum.evaluate(rule, roster, present(ined[0], ined[1])).quorate).isTrue()
        val one = Quorum.evaluate(rule, roster, present(ined[0], exec))
        assertThat(one.quorate).isFalse()
        assertThat(one.unmet.single()).isEqualTo(UnmetCondition(UnmetKind.COMPOSITION, 2, 1, attribute = "independent"))
    }

    @Test
    fun `Exim - two-thirds inclusive of the chairman, an interested director counted`() {
        val chair = seat(1, ForumRole.CHAIR)
        val members = (2..6).map { seat(it) }
        val roster = listOf(chair) + members
        val rule =
            QuorumRule(
                threshold = QuorumThreshold(ThresholdKind.FRACTION, numerator = 2, denominator = 3),
                required = listOf(listOf(ForumRole.CHAIR)),
                recusedCount = true,
            )
        // 6 members: two-thirds is 4.
        val attendance = present(chair, members[0], members[1]) + (members[2].memberId to QuorumPresence(recused = true))
        val result = Quorum.evaluate(rule, roster, attendance)
        assertThat(result.needed).isEqualTo(4)
        assertThat(result.quorate).isTrue()
        // Under the UK model articles the recused director does not count.
        assertThat(Quorum.evaluate(rule.copy(recusedCount = false), roster, attendance).quorate).isFalse()
    }

    @Test
    fun `JPMorgan - one-third or the next highest integer`() {
        val rule = QuorumRule(threshold = QuorumThreshold(ThresholdKind.FRACTION, numerator = 1, denominator = 3), minimum = 1)
        assertThat(Quorum.evaluate(rule, (1..7).map { seat(it) }, emptyMap()).needed).isEqualTo(3)
        assertThat(Quorum.evaluate(rule, (1..6).map { seat(it) }, emptyMap()).needed).isEqualTo(2)
    }

    @Test
    fun `Catalyst ALCO - a majority including two board and two management members`() {
        val board = (1..3).map { seat(it, ForumRole.MEMBER, true, "board") }
        val management = (4..7).map { seat(it, ForumRole.MEMBER, true, "management") }
        val rule =
            QuorumRule(
                composition = listOf(QuorumComposition("board", atLeast = 2), QuorumComposition("management", atLeast = 2)),
            )
        val roster = board + management
        assertThat(Quorum.evaluate(rule, roster, present(board[0], board[1], management[0], management[1])).quorate).isTrue()
        val result = Quorum.evaluate(rule, roster, present(board[0], management[0], management[1], management[2]))
        assertThat(result.quorate).isFalse()
        assertThat(result.unmet.map { it.attribute }).containsExactly("board")
    }

    @Test
    fun `observers and standing invitees never count, non-voting members count only under ALL`() {
        val members = (1..3).map { seat(it) }
        val observer = seat(4, ForumRole.OBSERVER)
        val invitee = seat(5, ForumRole.STANDING_INVITEE)
        val secretary = seat(6, ForumRole.SECRETARY)
        val roster = members + listOf(observer, invitee, secretary)
        val voting = Quorum.evaluate(QuorumRule(), roster, present(members[0], observer, invitee, secretary))
        assertThat(voting.basis).isEqualTo(3)
        assertThat(voting.present).isEqualTo(1)
        val all = Quorum.evaluate(QuorumRule(counts = QuorumCounts.ALL), roster, present(members[0], observer, invitee, secretary))
        assertThat(all.basis).isEqualTo(4)
        assertThat(all.present).isEqualTo(2)
    }

    @Test
    fun `remote attendance and alternates follow their toggles`() {
        val roster = (1..3).map { seat(it) }
        val attendance =
            mapOf(
                roster[0].memberId to QuorumPresence(remote = true),
                roster[1].memberId to QuorumPresence(byAlternate = true),
            )
        assertThat(Quorum.evaluate(QuorumRule(), roster, attendance).quorate).isTrue()
        assertThat(Quorum.evaluate(QuorumRule(remoteCounts = false), roster, attendance).present).isEqualTo(1)
        assertThat(Quorum.evaluate(QuorumRule(alternatesCount = false), roster, attendance).present).isEqualTo(1)
    }

    @Test
    fun `fixed seats count vacancies against the meeting`() {
        val roster = (1..4).map { seat(it) }
        val rule = QuorumRule(basis = QuorumBasis.SEATS, seats = 9)
        val result = Quorum.evaluate(rule, roster, present(*roster.toTypedArray()))
        assertThat(result.needed).isEqualTo(5)
        assertThat(result.quorate).isFalse()
    }

    @Test
    fun `a majority of independents among those present`() {
        val independents = (1..2).map { seat(it, ForumRole.MEMBER, true, "independent") }
        val others = (3..5).map { seat(it) }
        val rule = QuorumRule(composition = listOf(QuorumComposition("independent", majority = true)))
        val roster = independents + others
        assertThat(Quorum.evaluate(rule, roster, present(independents[0], independents[1], others[0])).quorate).isTrue()
        val result = Quorum.evaluate(rule, roster, present(independents[0], others[0], others[1]))
        assertThat(result.unmet.single()).isEqualTo(UnmetCondition(UnmetKind.COMPOSITION, 2, 1, attribute = "independent", majority = true))
    }
}
