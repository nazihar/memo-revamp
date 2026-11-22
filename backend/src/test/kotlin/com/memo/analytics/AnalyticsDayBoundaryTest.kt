package com.memo.analytics

import com.memo.analytics.measures.Period
import com.memo.meeting.MeetingKind
import com.memo.meeting.MeetingMode
import com.memo.meeting.MeetingStatus
import com.memo.meeting.internal.MeetingRow
import com.memo.org.internal.OrgCalendar
import com.memo.tenancy.TenantSetting
import com.memo.tenancy.TenantSettings
import com.memo.testsupport.MutableClock
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * The default period around the tenant's midnight (design E08 §2.1; master CI 45052317191): the period ends on the
 * tenant's today (`OrgCalendar`) and a held meeting is dated in its own zone (`MeetingRow.date`, as `AttendanceStats.held`
 * and the register filter it). With the clock pinned at 23:30 and 00:30 in the tenant's zone, a meeting the tenant held a
 * minute before is in the default period, on its last day; at 18:30 UTC a UTC+6 tenant is already on the next day.
 */
class AnalyticsDayBoundaryTest {
    private fun calendar(
        zone: String?,
        clock: MutableClock,
    ): OrgCalendar {
        val settings = mockk<TenantSettings>()
        every { settings.get(OrgCalendar.TIME_ZONE) } returns zone?.let { TenantSetting(OrgCalendar.TIME_ZONE, it, 1) }
        return OrgCalendar(settings, clock)
    }

    private fun heldMeeting(
        startsAt: Instant,
        zone: ZoneId,
    ) = MeetingRow(
        id = UUID.randomUUID(),
        forumId = UUID.randomUUID(),
        typeVersion = 1,
        kind = MeetingKind.ORDINARY,
        title = null,
        startsAt = startsAt,
        endsAt = startsAt.plus(Duration.ofHours(3)),
        zone = zone,
        mode = MeetingMode.IN_PERSON,
        location = "Board room",
        joinUrl = null,
        joinDetails = null,
        classificationId = UUID.randomUUID(),
        number = 1,
        numberYear = 2026,
        label = "BRC 1/2026",
        status = MeetingStatus.HELD,
        statusReason = null,
        createdBy = null,
        createdAt = startsAt,
        version = 1,
    )

    @ParameterizedTest(name = "{0} at {1} (local {2})")
    @CsvSource(
        "Asia/Dhaka, 2026-11-22T17:30:00Z, 2026-11-22",
        "Asia/Dhaka, 2026-11-22T18:30:00Z, 2026-11-23",
        "Asia/Riyadh, 2026-11-22T20:30:00Z, 2026-11-22",
        "Asia/Riyadh, 2026-11-22T21:30:00Z, 2026-11-23",
        "Asia/Dubai, 2026-11-22T19:30:00Z, 2026-11-22",
        "Asia/Dubai, 2026-11-22T20:30:00Z, 2026-11-23",
        "UTC, 2026-11-22T23:30:00Z, 2026-11-22",
        "UTC, 2026-11-23T00:30:00Z, 2026-11-23",
    )
    fun `a meeting held a minute ago is in the default period on either side of the tenant's midnight`(
        zone: String,
        at: Instant,
        localDay: LocalDate,
    ) {
        val clock = MutableClock(at)
        val today = calendar(zone, clock).today()
        assertThat(today).isEqualTo(localDay)
        val meeting = heldMeeting(clock.instant().minus(Duration.ofMinutes(1)), ZoneId.of(zone))
        val period = Period.of(null, null, today)
        assertThat(period.to).isEqualTo(localDay)
        assertThat(meeting.date).isEqualTo(localDay)
        assertThat(meeting.date in period).isTrue()
        assertThat(period.from).isEqualTo(localDay.minusMonths(Period.DEFAULT_MONTHS).plusDays(1))
    }

    @Test
    fun `the period moves with the clock across the tenant's midnight`() {
        val clock = MutableClock(Instant.parse("2026-11-22T17:30:00Z"))
        val calendar = calendar("Asia/Dhaka", clock)
        assertThat(calendar.today()).isEqualTo(LocalDate.of(2026, 11, 22))
        clock.advance(Duration.ofHours(1))
        assertThat(calendar.today()).isEqualTo(LocalDate.of(2026, 11, 23))
    }

    @Test
    fun `a tenant without a zone keeps UTC dates, so a meeting just held in Dhaka is dated after its default period`() {
        // What master's CI met at 18:04 UTC: the test tenant had no zone while its committee met in Dhaka.
        val clock = MutableClock(Instant.parse("2026-11-22T18:30:00Z"))
        val today = calendar(null, clock).today()
        assertThat(today).isEqualTo(LocalDate.of(2026, 11, 22))
        val meeting = heldMeeting(clock.instant().minus(Duration.ofMinutes(1)), ZoneId.of("Asia/Dhaka"))
        assertThat(meeting.date).isEqualTo(LocalDate.of(2026, 11, 23))
        assertThat(meeting.date in Period.of(null, null, today)).isFalse()
    }
}
