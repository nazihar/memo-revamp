package com.memo.org.internal

import com.memo.tenancy.TenantSettings
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.DateTimeException
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Effective dates are calendar dates in the tenant's time zone (ADR-0026): tenant setting `default.timezone` (an IANA id,
 * UTC when missing or invalid). Inside a tenant context.
 */
@Component
class OrgCalendar(
    private val settings: TenantSettings,
    private val clock: Clock,
) {
    fun zone(): ZoneId =
        settings.get(TIME_ZONE)?.value?.let {
            try {
                ZoneId.of(it)
            } catch (_: DateTimeException) {
                null
            }
        } ?: ZoneOffset.UTC

    fun today(): LocalDate = LocalDate.now(clock.withZone(zone()))

    companion object {
        const val TIME_ZONE = "default.timezone"
    }
}
