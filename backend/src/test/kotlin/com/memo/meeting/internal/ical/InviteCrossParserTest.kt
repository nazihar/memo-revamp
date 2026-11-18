package com.memo.meeting.internal.ical

import com.memo.meeting.internal.ical.InviteFixtures.brc
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
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

    private companion object {
        const val HOUR = 3600L
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
