package com.memo.org.internal

import com.memo.tenancy.TenantCalendar
import com.memo.tenancy.TenantSettings
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId

/**
 * Effective dates are calendar dates in the tenant's time zone (ADR-0026): tenant setting `default.timezone` (an IANA id,
 * UTC when missing or invalid; read as `TenantCalendar` reads it). Inside a tenant context.
 */
@Component
class OrgCalendar(
    private val settings: TenantSettings,
    private val clock: Clock,
) {
    fun zone(): ZoneId = TenantCalendar.zoneOf(settings)

    fun today(): LocalDate = LocalDate.now(clock.withZone(zone()))

    companion object {
        const val TIME_ZONE = TenantCalendar.TIME_ZONE
    }
}
