package com.memo.meeting.internal.ical

import net.fortuna.ical4j.data.CalendarBuilder
import net.fortuna.ical4j.model.TimeZoneRegistryFactory
import net.fortuna.ical4j.util.Configurator
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.IOException
import java.io.StringReader
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.time.Instant
import java.time.ZoneId
import java.util.Collections

/**
 * No run-time calls to tzurl.org (ADR-0063, AGENTS.md §3.1; test plan T1 `NoNetworkTest`): `ical4j.properties`
 * switches the timezone updater off, and writing, validating and parsing invitations in many zones, and ical4j's own
 * registry lookups, ask for no connection. Every outbound connection made through the JDK (URL connections, the HTTP
 * client, sockets with a proxy lookup) consults the default [ProxySelector], which here records and refuses it.
 */
class NoNetworkTest {
    @Test
    fun `ical4j's timezone updater is switched off`() {
        assertThat(Configurator.getProperty("net.fortuna.ical4j.timezone.update.enabled")).contains("false")
    }

    @Test
    fun `writing, validating and parsing invitations opens no connection`() {
        val asked = Collections.synchronizedList(mutableListOf<URI>())
        val previous = ProxySelector.getDefault()
        // Only this thread's connections are recorded and refused: the selector is JVM-wide, and other tests running at the
        // same time (a background PDF job reaching object storage) keep the selector they had.
        val mine = Thread.currentThread()
        ProxySelector.setDefault(
            object : ProxySelector() {
                override fun select(uri: URI): List<Proxy> {
                    if (Thread.currentThread() !== mine) return previous.select(uri)
                    asked += uri
                    throw IllegalStateException("no network in this test: $uri")
                }

                override fun connectFailed(
                    uri: URI?,
                    sa: SocketAddress?,
                    ioe: IOException?,
                ) {
                    if (Thread.currentThread() !== mine) previous.connectFailed(uri, sa, ioe)
                }
            },
        )
        try {
            val writer = InviteFixtures.writer()
            ZONES.map(ZoneId::of).forEach { zone ->
                val start = Instant.parse("2027-03-28T09:00:00Z")
                val event = InviteFixtures.brc.copy(startsAt = start, endsAt = start.plusSeconds(HOUR), zone = zone)
                InviteKind.entries.forEach { kind ->
                    val bytes = writer.write(InviteFixtures.of(kind, event))
                    CalendarBuilder().build(StringReader(String(bytes, Charsets.UTF_8))).validate()
                }
                TimeZoneRegistryFactory.getInstance().createRegistry().getTimeZone(zone.id)
            }
        } finally {
            ProxySelector.setDefault(previous)
        }
        assertThat(asked).isEmpty()
    }

    private companion object {
        const val HOUR = 3600L
        val ZONES = listOf("Asia/Dhaka", "Europe/London", "America/New_York", "Australia/Sydney", "Africa/Casablanca", "Asia/Kolkata")
    }
}
