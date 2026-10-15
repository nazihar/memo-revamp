package com.memorevamp.system.health

import com.memorevamp.shared.health.HealthStatus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class HealthServiceTest {
    private class FakeProbe(
        override val name: String,
        var status: ComponentStatus = ComponentStatus.UP,
        override val critical: Boolean = false,
    ) : ComponentProbe {
        val calls = AtomicInteger()

        override fun check(): ComponentStatus {
            calls.incrementAndGet()
            return status
        }
    }

    private class MutableClock(
        var now: Instant,
    ) : Clock() {
        override fun getZone() = ZoneOffset.UTC

        override fun withZone(zone: java.time.ZoneId?) = this

        override fun instant() = now
    }

    private val start = Instant.parse("2026-10-14T10:00:00Z")
    private val props = HealthProperties(cacheTtl = Duration.ofSeconds(5), probeTimeout = Duration.ofMillis(300))

    private fun aggregate(vararg results: Pair<FakeProbe, ComponentStatus>) = HealthService.aggregate(results.toList())

    @Test
    fun `all up or disabled is UP`() {
        val db = FakeProbe("database", critical = true)
        val mail = FakeProbe("mail")
        assertThat(aggregate(db to ComponentStatus.UP, mail to ComponentStatus.DISABLED)).isEqualTo(HealthStatus.UP)
    }

    @Test
    fun `non-critical component down is DEGRADED`() {
        val db = FakeProbe("database", critical = true)
        val mail = FakeProbe("mail")
        assertThat(aggregate(db to ComponentStatus.UP, mail to ComponentStatus.DOWN)).isEqualTo(HealthStatus.DEGRADED)
    }

    @Test
    fun `database down is DOWN regardless of others`() {
        val db = FakeProbe("database", critical = true)
        val mail = FakeProbe("mail")
        assertThat(aggregate(db to ComponentStatus.DOWN, mail to ComponentStatus.DOWN)).isEqualTo(HealthStatus.DOWN)
    }

    @Test
    fun `snapshot is cached for the configured ttl then refreshed`() {
        val clock = MutableClock(start)
        val probe = FakeProbe("database", critical = true)
        val service = HealthService(listOf(probe), props, clock)

        repeat(10) { service.current() }
        assertThat(probe.calls.get()).isEqualTo(1)

        clock.now = start.plusSeconds(4)
        service.current()
        assertThat(probe.calls.get()).isEqualTo(1)

        clock.now = start.plusSeconds(5)
        val refreshed = service.current()
        assertThat(probe.calls.get()).isEqualTo(2)
        assertThat(refreshed.checkedAt).isEqualTo(start.plusSeconds(5))
    }

    @Test
    fun `hanging probe is reported DOWN within the timeout`() {
        val release = CountDownLatch(1)
        val hanging =
            object : ComponentProbe {
                override val name = "mail"

                override fun check(): ComponentStatus {
                    release.await(10, TimeUnit.SECONDS)
                    return ComponentStatus.UP
                }
            }
        val service = HealthService(listOf(FakeProbe("database", critical = true), hanging), props, MutableClock(start))

        val began = System.nanoTime()
        val snapshot = service.current()
        val elapsed = Duration.ofNanos(System.nanoTime() - began)
        release.countDown()

        assertThat(snapshot.components.single { it.name == "mail" }.status).isEqualTo(ComponentStatus.DOWN)
        assertThat(snapshot.status).isEqualTo(HealthStatus.DEGRADED)
        assertThat(elapsed).isLessThan(Duration.ofSeconds(2))
    }

    @Test
    fun `throwing probe is reported DOWN`() {
        val throwing =
            object : ComponentProbe {
                override val name = "objectStorage"

                override fun check(): ComponentStatus = error("boom")
            }
        val snapshot = HealthService(listOf(throwing), props, MutableClock(start)).current()
        assertThat(snapshot.components.single().status).isEqualTo(ComponentStatus.DOWN)
    }

    @Test
    fun `components are listed in the public order`() {
        val probes = listOf("mail", "objectStorage", "database", "malwareScanner", "documentServer").map { FakeProbe(it) }
        val names = HealthService(probes, props, MutableClock(start)).current().components.map { it.name }
        assertThat(names).containsExactly("database", "objectStorage", "documentServer", "malwareScanner", "mail")
    }
}
