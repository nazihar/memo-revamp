package com.memo.tenancy

import org.springframework.stereotype.Component
import java.time.Clock
import java.time.DateTimeException
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * The tenant's calendar (ADR-0026): business dates are calendar dates in the tenant's time zone, the setting
 * `default.timezone` (an IANA id; UTC when missing or invalid). Inside a tenant context. The org module's `OrgCalendar`
 * reads the same zone; this one is for modules the org module depends on (the session, for the web's date defaults).
 */
@Component
class TenantCalendar(
    private val settings: TenantSettings,
    private val clock: Clock,
) {
    fun zone(): ZoneId = zoneOf(settings)

    fun today(): LocalDate = LocalDate.now(clock.withZone(zone()))

    companion object {
        const val TIME_ZONE = "default.timezone"

        /** The zone [settings] name, UTC when missing or invalid. */
        fun zoneOf(settings: TenantSettings): ZoneId =
            settings.get(TIME_ZONE)?.value?.let {
                try {
                    ZoneId.of(it)
                } catch (_: DateTimeException) {
                    null
                }
            } ?: ZoneOffset.UTC
    }
}
