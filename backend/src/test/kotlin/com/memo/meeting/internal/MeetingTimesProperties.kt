package com.memo.meeting.internal

import net.jqwik.api.Arbitraries
import net.jqwik.api.Arbitrary
import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.Provide
import net.jqwik.api.constraints.IntRange
import org.assertj.core.api.Assertions.assertThat
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * Meeting times round-trip through UTC (test plan T1): a local start that exists in its zone comes back unchanged from
 * the stored instant; one in a daylight-saving gap is refused; one in an overlap takes the earlier offset.
 */
class MeetingTimesProperties {
    @Property
    fun localTimesRoundTripThroughUtc(
        @ForAll("zones") zone: ZoneId,
        @ForAll("days") day: LocalDate,
        @ForAll @IntRange(min = 0, max = 1439) minute: Int,
        @ForAll @IntRange(min = 15, max = 600) length: Int,
    ) {
        val start = LocalDateTime.of(day, LocalTime.MIN.plusMinutes(minute.toLong()))
        val end = start.plusMinutes(length.toLong())
        val (resolved, problems) = MeetingTimes.resolve(LocalTimes(start, end, zone.id))
        val gap = { local: LocalDateTime -> zone.rules.getValidOffsets(local).isEmpty() }
        if (gap(start) || gap(end)) {
            assertThat(problems.map { it.code }).contains("UNKNOWN_LOCAL_TIME")
            assertThat(resolved).isNull()
            return
        }
        val back = checkNotNull(resolved).startsAt.atZone(zone).toLocalDateTime()
        assertThat(back).isEqualTo(start)
        val offsets = zone.rules.getValidOffsets(start)
        assertThat(resolved.startsAt.atZone(zone).offset).isEqualTo(offsets.maxBy { it.totalSeconds })
    }

    @Provide
    fun zones(): Arbitrary<ZoneId> =
        Arbitraries
            .of("Asia/Dhaka", "Europe/London", "America/New_York", "Australia/Lord_Howe", "Asia/Kathmandu", "UTC", "America/Santiago")
            .map(ZoneId::of)

    @Provide
    fun days(): Arbitrary<LocalDate> = Arbitraries.integers().between(0, 3 * 366).map { LocalDate.of(2025, 1, 1).plusDays(it.toLong()) }
}
