package com.memo.meeting.internal.rsvp

import com.memo.common.web.RefusedException
import com.memo.forum.ForumRole
import com.memo.forum.ForumSeat
import com.memo.meeting.internal.AudienceRow
import com.memo.meeting.internal.AudienceSource
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.UUID

/**
 * Who may stand in (test plan E05 T3 `AlternateRulesTest`, ADR-0064, design E05 §1.5): `ALTERNATE_NONE`,
 * `ALTERNATE_IS_MEMBER`, `ALTERNATE_BUSY`, and when a standing stops holding after a roster change.
 */
class AlternateRulesTest {
    private val member = UUID.randomUUID()
    private val alternate = UUID.randomUUID()
    private val other = UUID.randomUUID()
    private val meetingId = UUID.randomUUID()

    @Test
    fun `the member's seat with an alternate is the one that stands in`() {
        val seat = seat(member, alternate)
        val roster = listOf(seat(member, null), seat, seat(other, null))
        assertThat(AlternateRules.seat(member, roster, emptyList()) { true }).isEqualTo(seat)
        // Already standing in for this member: still allowed (nothing changes).
        assertThat(AlternateRules.seat(member, roster, listOf(standing(alternate, seat))) { true }).isEqualTo(seat)
    }

    @Test
    fun `no seat with an alternate, or one who cannot attend, is ALTERNATE_NONE`() {
        refused("ALTERNATE_NONE") { AlternateRules.seat(member, listOf(seat(member, null)), emptyList()) { true } }
        refused("ALTERNATE_NONE") { AlternateRules.seat(member, listOf(seat(other, alternate)), emptyList()) { true } }
        refused("ALTERNATE_NONE") { AlternateRules.seat(member, listOf(seat(member, alternate)), emptyList()) { false } }
    }

    @Test
    fun `an alternate who sits on the committee is ALTERNATE_IS_MEMBER`() {
        refused("ALTERNATE_IS_MEMBER") {
            AlternateRules.seat(member, listOf(seat(member, alternate), seat(alternate, null)), emptyList()) { true }
        }
    }

    @Test
    fun `an alternate already standing in for another member is ALTERNATE_BUSY`() {
        val otherSeat = seat(other, alternate)
        val roster = listOf(seat(member, alternate), otherSeat)
        refused("ALTERNATE_BUSY") { AlternateRules.seat(member, roster, listOf(standing(alternate, otherSeat))) { true } }
    }

    @Test
    fun `a standing holds while the member keeps the seat, the seat keeps the alternate, and the alternate holds no seat`() {
        val seat = seat(member, alternate)
        val row = standing(alternate, seat)
        assertThat(AlternateRules.holds(row, listOf(seat))).isTrue()
        assertThat(AlternateRules.holds(row, emptyList())).describedAs("the member's term ended").isFalse()
        assertThat(AlternateRules.holds(row, listOf(seat.copy(alternateUserId = other)))).describedAs("another alternate").isFalse()
        assertThat(AlternateRules.holds(row, listOf(seat, seat(alternate, null)))).describedAs("the alternate became a member").isFalse()
        assertThat(AlternateRules.holds(row, listOf(seat.copy(userId = other)))).describedAs("the position has a new holder").isFalse()
    }

    private fun refused(
        reason: String,
        block: () -> Unit,
    ) {
        assertThatThrownBy(block).isInstanceOfSatisfying(RefusedException::class.java) { assertThat(it.reason).isEqualTo(reason) }
    }

    private fun seat(
        user: UUID,
        alternate: UUID?,
    ) = ForumSeat(
        memberId = UUID.randomUUID(),
        forumId = UUID.randomUUID(),
        userId = user,
        positionId = null,
        role = ForumRole.MEMBER,
        voting = true,
        attributes = emptySet(),
        alternateUserId = alternate,
        startsOn = LocalDate.of(2026, 1, 1),
        termEndsOn = null,
        termNumber = 1,
        until = null,
    )

    private fun standing(
        user: UUID,
        seat: ForumSeat,
    ) = AudienceRow(meetingId, user, AudienceSource.ALTERNATE, true, null, seat.memberId, seat.userId)
}
