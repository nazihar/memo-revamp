package com.memo.meeting.internal.ical

import com.memo.meeting.internal.ical.InviteFixtures.brc
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * A second parser (test plan T1 `InviteCrossParserTest`): files for every method, in zones with and without daylight
 * saving (half-hour and 45-minute offsets, changes at midnight, a zone whose changes follow Ramadan), on a date in every
 * month, parse in biweekly 0.6.8 to the METHOD, UID, SEQUENCE, DTSTART, DTEND, TZID, STATUS and ATTENDEE ical4j reads,
 * and those are what the meeting says.
 */
class InviteCrossParserTest {
    private val writer = InviteFixtures.writer()

    @Test
    fun `both parsers read every method in every zone as the meeting says`() {
        ZONES.map(ZoneId::of).forEach { zone ->
            (1..MONTHS).forEach { month ->
                val start =
                    LocalDateTime
                        .of(2027, 1, 15, 10, 0)
                        .plusMonths(month - 1L)
                        .atZone(zone)
                        .toInstant()
                val event = brc.copy(sequence = month, startsAt = start, endsAt = start.plusSeconds(TWO_HOURS), zone = zone)
                InviteKind.entries.forEach { kind ->
                    val parsed = InviteChecks.valid(writer.write(InviteFixtures.of(kind, event)), kind)
                    assertThat(parsed.method).isEqualTo(kind.method)
                    assertThat(parsed.uid).isEqualTo(event.uid.toString())
                    assertThat(parsed.sequence).isEqualTo(month)
                    assertThat(parsed.start).describedAs("$zone $month").isEqualTo(event.startsAt)
                    assertThat(parsed.end).isEqualTo(event.endsAt)
                    assertThat(parsed.tzid).isEqualTo(zone.id)
                    assertThat(parsed.attendees).hasSize(if (kind == InviteKind.PUBLISH) 0 else 1)
                    assertThat(parsed.status).isEqualTo(
                        when (kind) {
                            InviteKind.CANCEL -> "CANCELLED"
                            InviteKind.UNINVITE -> null
                            else -> "CONFIRMED"
                        },
                    )
                }
            }
        }
    }

    /**
     * Zones whose offsets changed by one-off decisions, with a meeting between two changes (and one across a change).
     * CI found the first: with the change of December 2020 also listed, biweekly read a Volgograd meeting on 1 January
     * 2020 at +03 instead of +04, taking the last observance's offset. The file now ends with the observance in force.
     */
    @Test
    fun `meetings between one-off offset changes are read at the offset in force`() {
        ONE_OFF.forEach { (zoneId, local, hours) ->
            val zone = ZoneId.of(zoneId)
            val start = LocalDateTime.parse(local).atZone(zone).toInstant()
            val event = brc.copy(startsAt = start, endsAt = start.plusSeconds(hours * HOUR), zone = zone)
            InviteKind.entries.forEach { kind ->
                val parsed = InviteChecks.valid(writer.write(InviteFixtures.of(kind, event)), kind)
                assertThat(parsed.start).describedAs("$zoneId $local").isEqualTo(event.startsAt)
                assertThat(parsed.end).describedAs("$zoneId $local").isEqualTo(event.endsAt)
            }
        }
    }

    /**
     * Meetings that start or end exactly at a change of offset, or inside the hour that clocks going back repeat, in
     * both directions (CI run 44904197328 found Australia/Adelaide at the end of its daylight saving). A time in the
     * repeated hour's second occurrence is written in UTC: as a local time it would be read as the first occurrence
     * (RFC 5545 §3.3.5), an hour early. Times in the first occurrence keep TZID.
     */
    @Test
    fun `meetings at a change of offset are read at the instant the meeting says`() {
        TRANSITIONS.forEach { (zoneId, utc, minutes) ->
            val zone = ZoneId.of(zoneId)
            val start = Instant.parse(utc)
            val event = brc.copy(startsAt = start, endsAt = start.plusSeconds(minutes * MINUTE), zone = zone)
            InviteKind.entries.forEach { kind ->
                val bytes = writer.write(InviteFixtures.of(kind, event))
                val parsed = InviteChecks.valid(bytes, kind)
                assertThat(parsed.start).describedAs("$zoneId $utc start").isEqualTo(event.startsAt)
                assertThat(parsed.end).describedAs("$zoneId $utc end").isEqualTo(event.endsAt)
                // The event's DTSTART, not the VTIMEZONE observances'.
                val local = String(bytes, Charsets.UTF_8).substringAfter("BEGIN:VEVENT").lines().first { it.startsWith("DTSTART") }
                val inUtc = laterOccurrence(zone, event.startsAt) || laterOccurrence(zone, event.endsAt)
                assertThat(local).describedAs("$zoneId $utc form").startsWith(if (inUtc) "DTSTART:" else "DTSTART;TZID=$zoneId:")
            }
        }
    }

    /** True when [at]'s local time is repeated in [zone] and [at] is its second occurrence. */
    private fun laterOccurrence(
        zone: ZoneId,
        at: Instant,
    ): Boolean {
        val offsets = zone.rules.getValidOffsets(at.atZone(zone).toLocalDateTime())
        return offsets.size > 1 && zone.rules.getOffset(at) != offsets.first()
    }

    private companion object {
        const val HOUR = 3600L
        const val MINUTE = 60L

        /** Zone, start in UTC, length in minutes. */
        val TRANSITIONS =
            listOf(
                // Daylight saving ends 03:00 → 02:00 on 7 April 2019: at the change, and inside the repeated hour.
                Triple("Australia/Adelaide", "2019-04-06T16:30:00Z", 60L),
                Triple("Australia/Adelaide", "2019-04-06T16:45:00Z", 60L),
                // The same local times in the first occurrence (still daylight saving): TZID.
                Triple("Australia/Adelaide", "2019-04-06T15:45:00Z", 30L),
                // Daylight saving starts 02:00 → 03:00 on 6 October 2019.
                Triple("Australia/Adelaide", "2019-10-05T16:30:00Z", 60L),
                // London 25 October 2026 (02:00 BST → 01:00 GMT), and 29 March 2026 (01:00 GMT → 02:00 BST).
                Triple("Europe/London", "2026-10-25T01:00:00Z", 60L),
                Triple("Europe/London", "2026-10-25T00:30:00Z", 30L),
                Triple("Europe/London", "2026-10-25T00:15:00Z", 60L),
                Triple("Europe/London", "2026-03-29T01:00:00Z", 60L),
                // New York 1 November 2026 (02:00 EDT → 01:00 EST), and 8 March 2026 (02:00 EST → 03:00 EDT).
                Triple("America/New_York", "2026-11-01T06:00:00Z", 60L),
                Triple("America/New_York", "2026-11-01T05:30:00Z", 30L),
                Triple("America/New_York", "2026-03-08T07:00:00Z", 60L),
                // Lord Howe's 30-minute shift: 02:00 → 01:30 on 5 April 2026, 02:00 → 02:30 on 4 October 2026.
                Triple("Australia/Lord_Howe", "2026-04-04T15:00:00Z", 60L),
                Triple("Australia/Lord_Howe", "2026-04-04T15:15:00Z", 30L),
                Triple("Australia/Lord_Howe", "2026-10-03T15:30:00Z", 60L),
            )
        val ONE_OFF =
            listOf(
                // +04 from 2018-10-28 until 2020-12-27, then +03.
                Triple("Europe/Volgograd", "2020-01-01T04:00", 1L),
                // Across the change back to +03 at 02:00 on 27 December 2020.
                Triple("Europe/Volgograd", "2020-12-27T01:00", 3L),
                // +03 from 2010-10-31, +04 again from 2011-03-27.
                Triple("Europe/Samara", "2011-06-01T12:00", 2L),
                // +08:30 from 2015-08-15 until 2018-05-04.
                Triple("Asia/Pyongyang", "2016-06-01T10:00", 2L),
                // -04:30 from 2007-12-09 until 2016-05-01.
                Triple("America/Caracas", "2016-01-01T09:30", 2L),
                // +03 until 2021-02-01, then +02.
                Triple("Africa/Juba", "2020-06-01T10:00", 2L),
                // Daylight saving whose October change is at 24:00, which RRULE cannot say: explicit, with STANDARD first.
                Triple("Africa/Cairo", "2027-05-02T01:00", 2L),
                Triple("Africa/Cairo", "2027-01-10T10:00", 2L),
            )
        const val MONTHS = 12
        const val TWO_HOURS = 7200L
        val ZONES =
            listOf(
                "Asia/Dhaka",
                "Europe/London",
                "America/New_York",
                "America/Santiago",
                "America/Sao_Paulo",
                "Australia/Lord_Howe",
                "Asia/Kolkata",
                "Asia/Kathmandu",
                "Pacific/Chatham",
                "Africa/Casablanca",
                "Asia/Tehran",
                "UTC",
            )
    }
}
