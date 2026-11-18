package com.memo.meeting.internal.ical

import com.memo.meeting.MeetingMode
import com.memo.meeting.internal.ical.InviteFixtures.brc
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.ZoneOffset

/** The writer's acceptance criteria (tasks T1) and its input hygiene (design E05 §1.2.1, threat T3). */
class InviteWriterTest {
    private val writer = InviteFixtures.writer()

    @Test
    fun `the BRC request is valid and both parsers read its UID, sequence and times`() {
        val parsed = InviteChecks.valid(writer.write(InviteFixtures.request()), InviteKind.REQUEST)

        assertThat(parsed.method).isEqualTo("REQUEST")
        assertThat(parsed.uid).isEqualTo(InviteFixtures.UID.toString())
        assertThat(parsed.sequence).isZero()
        assertThat(parsed.start).isEqualTo(Instant.parse("2026-12-12T04:00:00Z"))
        assertThat(parsed.end).isEqualTo(Instant.parse("2026-12-12T07:00:00Z"))
        assertThat(parsed.tzid).isEqualTo("Asia/Dhaka")
        assertThat(parsed.attendees).containsExactly("director@demo.example")
    }

    @Test
    fun `a title carrying a line break and a property gives one escaped SUMMARY and no second ATTENDEE`() {
        val event = brc.copy(summary = "Risk;\r\nATTENDEE:mailto:x@evil.example")
        val bytes = writer.write(InviteFixtures.request(event))
        val text = String(bytes, Charsets.UTF_8)
        val lines = text.replace("\r\n ", "").split("\r\n")

        val parsed = InviteChecks.valid(bytes, InviteKind.REQUEST)
        assertThat(lines.filter { it.startsWith("SUMMARY") }).containsExactly("SUMMARY:Risk\\; ATTENDEE:mailto:x@evil.example")
        assertThat(lines.filter { it.startsWith("ATTENDEE") }).hasSize(1)
        assertThat(parsed.attendees).containsExactly("director@demo.example")
        assertThat(parsed.summary).isEqualTo("Risk; ATTENDEE:mailto:x@evil.example")
    }

    @Test
    fun `names lose quotes and line breaks, joining details keep their lines`() {
        val event =
            brc.copy(
                mode = MeetingMode.ONLINE,
                location = null,
                joinUrl = "https://meet.example/j/1",
                joinDetails = "ID 1\r\nPIN\u0007 2",
            )
        val invite = InviteFixtures.request(event).copy(attendee = InviteParty("Dr \"Nadia\"\r\nRahman^", "nadia@demo.example"))
        val text = unfolded(writer.write(invite))

        assertThat(text).contains("ATTENDEE;CN=Dr 'Nadia' Rahman;ROLE=REQ-PARTICIPANT")
        assertThat(text).contains("LOCATION:Online meeting")
        assertThat(text).contains("Joining details: ID 1\\nPIN 2")
    }

    @Test
    fun `a guest is an optional participant, and no file asks for a calendar reply`() {
        val text = unfolded(writer.write(InviteFixtures.request().copy(guest = true)))

        assertThat(text).contains("ROLE=OPT-PARTICIPANT").contains("RSVP=FALSE").doesNotContain("RSVP=TRUE")
    }

    @Test
    fun `the cancellation and the uninvite carry no meeting text beyond the notice and the link`() {
        val cancel = unfolded(writer.write(InviteFixtures.of(InviteKind.CANCEL)))
        val uninvite = unfolded(writer.write(InviteFixtures.of(InviteKind.UNINVITE)))

        assertThat(cancel).contains("STATUS:CANCELLED").contains("This meeting has been cancelled.")
        assertThat(uninvite).doesNotContain("STATUS:").contains("You are no longer invited to this meeting.")
        assertThat(cancel + uninvite).doesNotContain("Please tell us")
    }

    @Test
    fun `malformed addresses, links, times and zones are refused`() {
        val refused =
            listOf(
                InviteFixtures.request().copy(attendee = InviteParty("X", "not an address")),
                InviteFixtures.request().copy(organizer = InviteParty("X", "a@b@c")),
                InviteFixtures.request(brc.copy(meetingUrl = "javascript:alert(1)")),
                InviteFixtures.request(brc.copy(joinUrl = "https://meet.example/j/1\r\nX-Evil: 1")),
                InviteFixtures.request(brc.copy(endsAt = brc.startsAt)),
                InviteFixtures.request(brc.copy(sequence = -1)),
                InviteFixtures.request(brc.copy(zone = ZoneOffset.ofHours(6))),
            )
        refused.forEach { invite ->
            assertThatThrownBy { writer.write(invite) }.isInstanceOf(InviteRefusedException::class.java)
        }
    }

    private fun unfolded(bytes: ByteArray) = String(bytes, Charsets.UTF_8).replace("\r\n ", "")
}
