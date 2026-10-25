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
 */
class MutableClock(
    var now: Instant = Instant.now(Clock.systemUTC()).truncatedTo(ChronoUnit.MICROS),
) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId?): Clock = this

    override fun instant(): Instant = now

    fun advance(by: Duration) {
        now = now.plus(by)
    }
}
