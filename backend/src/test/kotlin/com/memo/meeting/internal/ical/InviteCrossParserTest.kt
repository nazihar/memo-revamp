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

    private companion object {
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
