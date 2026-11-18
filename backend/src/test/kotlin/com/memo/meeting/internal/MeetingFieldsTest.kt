package com.memo.meeting.internal

import com.memo.meeting.MeetingMode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import java.time.ZoneId

/** A meeting's field checks (design E03 §1.1, test plan T1): zone names, https links, place by mode, times. */
class MeetingFieldsTest {
    private fun codes(place: MeetingPlace) = MeetingFields.place(place).second.map { "${it.field}:${it.code}" }

    @Test
    fun `the place follows the mode`() {
        assertThat(codes(MeetingPlace(null, MeetingMode.ONLINE, null, null, null))).containsExactly("joinUrl:REQUIRED")
        assertThat(codes(MeetingPlace(null, MeetingMode.IN_PERSON, " ", null, null))).containsExactly("location:REQUIRED")
        assertThat(codes(MeetingPlace(null, MeetingMode.HYBRID, null, null, null))).containsExactly("location:REQUIRED", "joinUrl:REQUIRED")
        assertThat(codes(MeetingPlace(null, MeetingMode.IN_PERSON, "Board room", null, null))).isEmpty()
        assertThat(codes(MeetingPlace("  ", MeetingMode.ONLINE, null, "https://meet.example/abc", null))).isEmpty()
        assertThat(MeetingFields.place(MeetingPlace("  ", MeetingMode.ONLINE, null, "https://meet.example/abc", " ")).first.title).isNull()
    }

    @Test
    fun `texts have their lengths`() {
        val place = MeetingPlace("t".repeat(201), MeetingMode.IN_PERSON, "l".repeat(501), null, "d".repeat(1001))
        assertThat(codes(place)).containsExactly("title:LENGTH", "location:LENGTH", "joinDetails:LENGTH")
    }

    @Test
    fun `a join link is https with a host and no credentials`() {
        assertThat(MeetingFields.link("https://meet.example/abc")).isNull()
        assertThat(MeetingFields.link("HTTPS://meet.example/abc?pwd=1")).isNull()
        assertThat(MeetingFields.link("http://meet.example/abc")?.code).isEqualTo("LINK_NOT_HTTPS")
        assertThat(MeetingFields.link("javascript:alert(1)")?.code).isEqualTo("FORMAT")
        assertThat(MeetingFields.link("https://user:secret@meet.example/abc")?.code).isEqualTo("LINK_CREDENTIALS")
        assertThat(MeetingFields.link("https://")?.code).isEqualTo("FORMAT")
        assertThat(MeetingFields.link("not a link")?.code).isEqualTo("FORMAT")
        assertThat(MeetingFields.link("https://meet.example/" + "a".repeat(2000))?.code).isEqualTo("LENGTH")
    }

    @Test
    fun `a reason is required and bounded`() {
        assertThat(MeetingFields.reason(" ").second?.code).isEqualTo("REASON_REQUIRED")
        assertThat(MeetingFields.reason(null).second?.code).isEqualTo("REASON_REQUIRED")
        assertThat(MeetingFields.reason("r".repeat(501)).second?.code).isEqualTo("LENGTH")
        assertThat(MeetingFields.reason(" Chair unwell ")).isEqualTo("Chair unwell" to null)
    }

    @Test
    fun `zones are IANA names the JVM knows`() {
        assertThat(MeetingTimes.zone("Asia/Dhaka")).isEqualTo(ZoneId.of("Asia/Dhaka"))
        assertThat(MeetingTimes.zone("+06:00")).isNull()
        assertThat(MeetingTimes.zone("Mars/Olympus")).isNull()
        val times = LocalTimes(LocalDateTime.of(2026, 12, 12, 10, 0), LocalDateTime.of(2026, 12, 12, 13, 0), "Nowhere/Town")
        assertThat(MeetingTimes.resolve(times).second.map { it.code }).containsExactly("UNKNOWN_ZONE")
    }

    @Test
    fun `a meeting ends after it starts and lasts at most seven days`() {
        val start = LocalDateTime.of(2026, 12, 12, 10, 0)
        val ok = MeetingTimes.resolve(LocalTimes(start, start.plusHours(3), "Asia/Dhaka"))
        assertThat(ok.second).isEmpty()
        assertThat(ok.first?.startsAt.toString()).isEqualTo("2026-12-12T04:00:00Z")
        assertThat(MeetingTimes.resolve(LocalTimes(start, start, "Asia/Dhaka")).second.map { it.code }).containsExactly("END_BEFORE_START")
        assertThat(MeetingTimes.resolve(LocalTimes(start, start.plusDays(7), "Asia/Dhaka")).second).isEmpty()
        assertThat(MeetingTimes.resolve(LocalTimes(start, start.plusDays(7).plusMinutes(1), "Asia/Dhaka")).second.map { it.code })
            .containsExactly("TOO_LONG")
    }

    @Test
    fun `a start in a daylight-saving gap is refused and one in an overlap takes the earlier offset`() {
        val london = "Europe/London"
        // 29 March 2026: clocks go from 01:00 to 02:00; 25 October 2026: 01:00–02:00 happens twice.
        val gap = MeetingTimes.resolve(LocalTimes(LocalDateTime.of(2026, 3, 29, 1, 30), LocalDateTime.of(2026, 3, 29, 4, 0), london))
        assertThat(gap.second.map { "${it.field}:${it.code}" }).containsExactly("startsAt:UNKNOWN_LOCAL_TIME")
        val overlap = MeetingTimes.resolve(LocalTimes(LocalDateTime.of(2026, 10, 25, 1, 30), LocalDateTime.of(2026, 10, 25, 4, 0), london))
        assertThat(overlap.first?.startsAt.toString()).isEqualTo("2026-10-25T00:30:00Z")
    }
}
