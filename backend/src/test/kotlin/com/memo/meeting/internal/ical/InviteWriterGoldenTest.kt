package com.memo.meeting.internal.ical

import com.memo.meeting.MeetingMode
import com.memo.meeting.internal.ical.InviteFixtures.DHAKA
import com.memo.meeting.internal.ical.InviteFixtures.LONDON
import com.memo.meeting.internal.ical.InviteFixtures.at
import com.memo.meeting.internal.ical.InviteFixtures.brc
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale

/**
 * The invitation files byte for byte (ADR-0063, test plan T1 `InviteWriterGoldenTest`): request, update, cancel,
 * uninvite and publish, in a zone with daylight saving and one without, a neutral title and an Arabic committee. DTSTAMP
 * is normalised before comparing. Set `GOLDEN_UPDATE=true` to rewrite the files (then review the diff).
 */
class InviteWriterGoldenTest {
    private val writer = InviteFixtures.writer()

    @Test
    fun `a first invitation in Dhaka (no daylight saving)`() {
        golden("request-asia-dhaka.ics", InviteFixtures.request(), InviteKind.REQUEST)
    }

    @Test
    fun `a first invitation in London in the week the clocks go back`() {
        val event =
            brc.copy(
                startsAt = at("2026-10-27T14:00", LONDON),
                endsAt = at("2026-10-27T16:30", LONDON),
                zone = LONDON,
                summary = "Audit Committee",
                mode = MeetingMode.HYBRID,
                location = "Committee Room 2, 1 Threadneedle Street",
                joinUrl = "https://meet.example/j/8841-2210",
            )
        golden("request-europe-london.ics", InviteFixtures.request(event), InviteKind.REQUEST)
    }

    @Test
    fun `an update after a reschedule has the next sequence and the new times`() {
        val event = brc.copy(sequence = 1, startsAt = at("2026-12-12T14:00", DHAKA), endsAt = at("2026-12-12T17:00", DHAKA))
        golden("update-time.ics", InviteFixtures.request(event), InviteKind.REQUEST)
    }

    @Test
    fun `an update after a new place and joining link`() {
        val event =
            brc.copy(
                sequence = 2,
                startsAt = at("2026-12-12T14:00", DHAKA),
                endsAt = at("2026-12-12T17:00", DHAKA),
                mode = MeetingMode.HYBRID,
                location = "Conference Room A, Level 3, Head Office",
                joinUrl = "https://meet.example/j/123456",
                joinDetails = "Meeting ID 123 456\nThe passcode is on the meeting page",
            )
        golden("update-place-link.ics", InviteFixtures.request(event), InviteKind.REQUEST)
    }

    @Test
    fun `the meeting cancelled`() {
        val event = brc.copy(sequence = 3, startsAt = at("2026-12-12T14:00", DHAKA), endsAt = at("2026-12-12T17:00", DHAKA))
        golden("cancel-meeting.ics", InviteFixtures.of(InviteKind.CANCEL, event), InviteKind.CANCEL)
    }

    @Test
    fun `one person uninvited`() {
        golden("uninvite.ics", InviteFixtures.of(InviteKind.UNINVITE, brc.copy(sequence = 1)), InviteKind.UNINVITE)
    }

    @Test
    fun `the Add to my calendar download`() {
        golden("publish-download.ics", InviteFixtures.of(InviteKind.PUBLISH), InviteKind.PUBLISH)
    }

    @Test
    fun `a neutral title is private`() {
        golden(
            "request-neutral-title.ics",
            InviteFixtures.request(brc.copy(summary = "Committee meeting", confidential = true)),
            InviteKind.REQUEST,
        )
    }

    @Test
    fun `an Arabic committee, in Arabic, folds between characters`() {
        val event =
            brc.copy(
                summary = "لجنة المخاطر المنبثقة عن مجلس الإدارة — الاجتماع الدوري الرابع",
                location = "قاعة مجلس الإدارة، الطابق الثاني عشر، المكتب الرئيسي",
                mode = MeetingMode.HYBRID,
                joinUrl = "https://meet.example/j/777",
            )
        val invite =
            InviteFixtures
                .request(
                    event,
                    Locale.forLanguageTag("ar"),
                ).copy(attendee = InviteFixtures.director.copy(name = "مدير تجريبي"))
        val text = golden("request-arabic.ics", invite, InviteKind.REQUEST)
        assertThat(text.lines().map { it.toByteArray(Charsets.UTF_8).size }.max()).isLessThanOrEqualTo(IcsFolding.MAX_OCTETS + 1)
    }

    @Test
    fun `the request matches the design's example apart from DTSTAMP`() {
        val later = InviteWriter(Clock.fixed(Instant.parse("2026-11-19T09:30:00Z"), ZoneOffset.UTC))
        val one = normalised(String(writer.write(InviteFixtures.request()), Charsets.UTF_8))
        val two = normalised(String(later.write(InviteFixtures.request()), Charsets.UTF_8))
        assertThat(two).isEqualTo(one)
    }

    private fun golden(
        name: String,
        invite: Invite,
        kind: InviteKind,
    ): String {
        val bytes = writer.write(invite)
        InviteChecks.valid(bytes, kind)
        val text = normalised(String(bytes, Charsets.UTF_8))
        val path = Path.of("src/test/resources/ical", name)
        if (System.getenv("GOLDEN_UPDATE") == "true" || !Files.exists(path)) {
            Files.createDirectories(path.parent)
            Files.write(path, text.toByteArray(Charsets.UTF_8))
            if (System.getenv("GOLDEN_UPDATE") != "true") error("golden file $name was missing and has been written: review it")
        }
        val expected = Files.readAllBytes(path)
        InviteChecks.valid(expected, kind)
        assertThat(text).isEqualTo(String(expected, Charsets.UTF_8))
        return text
    }

    private fun normalised(text: String) = text.replace(Regex("DTSTAMP:\\d{8}T\\d{6}Z"), "DTSTAMP:$STAMP")

    private companion object {
        const val STAMP = "20261118T040000Z"
    }
}
