package com.memo.meeting.internal.rsvp

import com.memo.common.web.RefusedException
import com.memo.meeting.MeetingMode
import com.memo.meeting.MeetingStatus
import com.memo.meeting.internal.AudienceRow
import com.memo.meeting.internal.AudienceSource
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * The pure rules of replies (test plan E05 T3 `RsvpRulesTest`, design E05 §1.4): the answers each mode offers
 * (`MODE_NOT_OFFERED`), the window by status, staleness by times only, `how` required on behalf, the lengths of notes,
 * and who answers in which capacity.
 */
class RsvpRulesTest {
    @Test
    fun `each mode offers its answers, and apologies always`() {
        assertThat(RsvpRules.offered(MeetingMode.IN_PERSON)).containsExactly(RsvpAnswer.IN_PERSON, RsvpAnswer.APOLOGIES)
        assertThat(RsvpRules.offered(MeetingMode.ONLINE)).containsExactly(RsvpAnswer.REMOTE, RsvpAnswer.APOLOGIES)
        assertThat(RsvpRules.offered(MeetingMode.HYBRID)).containsExactly(RsvpAnswer.IN_PERSON, RsvpAnswer.REMOTE, RsvpAnswer.APOLOGIES)
        assertThat(RsvpRules.answer(RsvpAnswer.REMOTE, MeetingMode.IN_PERSON)?.code).isEqualTo("MODE_NOT_OFFERED")
        assertThat(RsvpRules.answer(RsvpAnswer.IN_PERSON, MeetingMode.ONLINE)?.code).isEqualTo("MODE_NOT_OFFERED")
        MeetingMode.entries.forEach { mode ->
            RsvpRules.offered(mode).forEach { assertThat(RsvpRules.answer(it, mode)).isNull() }
        }
    }

    @Test
    fun `answers are open while the meeting is scheduled only`() {
        assertThatCode { RsvpRules.window(MeetingStatus.SCHEDULED) }.doesNotThrowAnyException()
        (MeetingStatus.entries - MeetingStatus.SCHEDULED).forEach { status ->
            assertThatThrownBy { RsvpRules.window(status) }
                .describedAs(status.name)
                .isInstanceOfSatisfying(RefusedException::class.java) { assertThat(it.reason).isEqualTo("WRONG_STATE") }
        }
    }

    @Test
    fun `an answer is stale when the start or the end moved, and only then`() {
        val start = Instant.parse("2026-12-12T04:00:00Z")
        val end = Instant.parse("2026-12-12T07:00:00Z")
        assertThat(RsvpRules.stale(start, end, start, end)).isFalse()
        assertThat(RsvpRules.stale(start, end, start.plusSeconds(3600), end.plusSeconds(3600))).isTrue()
        assertThat(RsvpRules.stale(start, end, start, end.plusSeconds(1800))).isTrue()
    }

    @Test
    fun `on behalf needs how, the alternate needs apologies, and notes have their lengths`() {
        assertThat(RsvpRules.how(null)?.code).isEqualTo("HOW_REQUIRED")
        assertThat(RsvpRules.how(RsvpHow.IN_APP)?.code).isEqualTo("HOW_REQUIRED")
        listOf(RsvpHow.EMAIL, RsvpHow.PHONE, RsvpHow.IN_PERSON, RsvpHow.OTHER).forEach { assertThat(RsvpRules.how(it)).isNull() }
        assertThat(RsvpRules.alternate(RsvpAnswer.IN_PERSON, true)?.code).isEqualTo("ALTERNATE_WITH_APOLOGIES")
        assertThat(RsvpRules.alternate(RsvpAnswer.APOLOGIES, true)).isNull()
        assertThat(RsvpRules.alternate(RsvpAnswer.REMOTE, false)).isNull()
        assertThat(RsvpRules.optional("note", "x".repeat(RsvpRules.MAX_NOTE), RsvpRules.MAX_NOTE).second).isNull()
        assertThat(RsvpRules.optional("note", "x".repeat(RsvpRules.MAX_NOTE + 1), RsvpRules.MAX_NOTE).second?.code).isEqualTo("LENGTH")
        assertThat(RsvpRules.optional("howNote", "  ", RsvpRules.MAX_HOW_NOTE)).isEqualTo(null to null)
        assertThat(RsvpRules.optional("note", " I will join after 11:00 ", RsvpRules.MAX_NOTE).first).isEqualTo("I will join after 11:00")
    }

    @Test
    fun `a seat answers before a stand-in, a stand-in before a guest, and an item attendee does not answer`() {
        assertThat(RsvpRules.capacity(listOf(row(AudienceSource.GUEST), row(AudienceSource.MEMBER)))).isEqualTo(ReplyCapacity.MEMBER)
        assertThat(RsvpRules.capacity(listOf(row(AudienceSource.GUEST), row(AudienceSource.ALTERNATE)))).isEqualTo(ReplyCapacity.ALTERNATE)
        assertThat(RsvpRules.capacity(listOf(row(AudienceSource.GUEST)))).isEqualTo(ReplyCapacity.GUEST)
        assertThat(RsvpRules.capacity(listOf(row(AudienceSource.ATTENDEE)))).isNull()
        assertThat(RsvpRules.capacity(emptyList())).isNull()
    }

    private fun row(source: AudienceSource) = AudienceRow(UUID.randomUUID(), UUID.randomUUID(), source, false, null)
}
