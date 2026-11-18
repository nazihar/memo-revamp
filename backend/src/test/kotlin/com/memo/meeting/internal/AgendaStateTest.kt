package com.memo.meeting.internal

import com.memo.meeting.MeetingStatus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The agenda's state machine (test plan E03, T3; design E03 §1.3): publishing is allowed only while the meeting is
 * scheduled or in progress, revision 1 waits for the chair's agreement when the type asks for it, an unreadable paper
 * refuses it, and so does a revision with nothing new. Every allowed and forbidden publication.
 */
class AgendaStateTest {
    @Test
    fun `a scheduled or in-progress meeting publishes, a draft is not scheduled, and held, cancelled and later are frozen`() {
        assertThat(refusal(MeetingStatus.SCHEDULED)).isNull()
        assertThat(refusal(MeetingStatus.IN_PROGRESS)).isNull()
        assertThat(refusal(MeetingStatus.DRAFT)).isEqualTo("MEETING_NOT_SCHEDULED")
        for (status in listOf(MeetingStatus.HELD, MeetingStatus.CANCELLED, MeetingStatus.MINUTES_APPROVED, MeetingStatus.CLOSED)) {
            assertThat(refusal(status)).describedAs(status.name).isEqualTo("FROZEN")
        }
    }

    @Test
    fun `revision 1 without the chair's agreement, an unreadable paper or nothing new is refused, in that order`() {
        assertThat(refusal(MeetingStatus.SCHEDULED, agreementMissing = true)).isEqualTo("CHAIR_AGREEMENT_REQUIRED")
        assertThat(refusal(MeetingStatus.SCHEDULED, unreadable = true)).isEqualTo("PAPER_NOT_READABLE")
        assertThat(refusal(MeetingStatus.SCHEDULED, nothingNew = true)).isEqualTo("NOTHING_TO_PUBLISH")
        assertThat(refusal(MeetingStatus.SCHEDULED, agreementMissing = true, unreadable = true, nothingNew = true))
            .isEqualTo("CHAIR_AGREEMENT_REQUIRED")
        assertThat(refusal(MeetingStatus.SCHEDULED, unreadable = true, nothingNew = true)).isEqualTo("PAPER_NOT_READABLE")
        assertThat(refusal(MeetingStatus.DRAFT, agreementMissing = true)).isEqualTo("MEETING_NOT_SCHEDULED")
        assertThat(refusal(MeetingStatus.HELD, agreementMissing = true)).isEqualTo("FROZEN")
    }

    @Test
    fun `the draft takes any change before the meeting, tabled ones while it runs, and none after`() {
        assertThat(AgendaChanges.of(MeetingStatus.DRAFT)).isEqualTo(AgendaChanges.ANY)
        assertThat(AgendaChanges.of(MeetingStatus.SCHEDULED)).isEqualTo(AgendaChanges.ANY)
        assertThat(AgendaChanges.of(MeetingStatus.IN_PROGRESS)).isEqualTo(AgendaChanges.TABLED_ONLY)
        assertThat(AgendaChanges.of(MeetingStatus.HELD)).isEqualTo(AgendaChanges.NONE)
        assertThat(AgendaChanges.of(MeetingStatus.CANCELLED)).isEqualTo(AgendaChanges.NONE)
    }

    private fun refusal(
        status: MeetingStatus,
        agreementMissing: Boolean = false,
        unreadable: Boolean = false,
        nothingNew: Boolean = false,
    ) = RevisionRules.refusal(status, agreementMissing, unreadable, nothingNew)
}
