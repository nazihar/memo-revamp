package com.memo.testsupport

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

/**
 * A clock tests move by hand, for time-based rules (throttling, expiry) without sleeping. Starts on a whole microsecond:
 * PostgreSQL rounds timestamps to microseconds, so a finer start would make stored instants drift from computed ones.
 * [withZone] keeps the zone asked for and follows this clock, so `LocalDate.now(clock.withZone(zone))` is the date in that
 * zone, as with the system clock (it used to stay in UTC, which hid the tenant's date from a test).
 */
class MutableClock(
    var now: Instant = Instant.now(Clock.systemUTC()).truncatedTo(ChronoUnit.MICROS),
) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId?): Clock = if (zone == null || zone == ZoneOffset.UTC) this else Zoned(this, zone)

    override fun instant(): Instant = now

    fun advance(by: Duration) {
        now = now.plus(by)
    }

    /** This clock seen in [zone]. */
    private class Zoned(
        private val source: MutableClock,
        private val zone: ZoneId,
    ) : Clock() {
        override fun getZone(): ZoneId = zone

        override fun withZone(zone: ZoneId?): Clock = source.withZone(zone)

        override fun instant(): Instant = source.instant()
    }
}
