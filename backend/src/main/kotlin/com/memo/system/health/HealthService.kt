package com.memo.system.health

import com.memo.shared.health.HealthStatus
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

private val DEFAULT_CACHE_TTL: Duration = Duration.ofSeconds(5)
private val DEFAULT_PROBE_TIMEOUT: Duration = Duration.ofSeconds(2)

@ConfigurationProperties("memo.health")
data class HealthProperties(
    /** How long a snapshot is served before probes run again (anti-amplification, threat model D1). */
    val cacheTtl: Duration = DEFAULT_CACHE_TTL,
    /** Upper bound for each probe (threat model D2). */
    val probeTimeout: Duration = DEFAULT_PROBE_TIMEOUT,
)

data class ComponentHealth(
    val name: String,
    val status: ComponentStatus,
)

data class HealthSnapshot(
    val status: HealthStatus,
    val checkedAt: Instant,
    val components: List<ComponentHealth>,
)

/**
 * Runs all [ComponentProbe]s in parallel on virtual threads, each bounded by [HealthProperties.probeTimeout],
 * and caches the aggregated snapshot for [HealthProperties.cacheTtl].
 */
@Service
class HealthService(
    probes: List<ComponentProbe>,
    private val properties: HealthProperties,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val probes = probes.sortedBy { PROBE_ORDER.indexOf(it.name).let { i -> if (i < 0) Int.MAX_VALUE else i } }
    private val executor: ExecutorService = Executors.newVirtualThreadPerTaskExecutor()

    @Volatile
    private var cached: HealthSnapshot? = null

    fun current(): HealthSnapshot {
        val now = clock.instant()
        cached?.let { if (now.isBefore(it.checkedAt.plus(properties.cacheTtl))) return it }
        return synchronized(this) {
            cached?.takeIf { now.isBefore(it.checkedAt.plus(properties.cacheTtl)) } ?: probeAll(now).also { cached = it }
        }
    }

    private fun probeAll(now: Instant): HealthSnapshot {
        val futures = probes.map { probe -> probe to executor.submit<ComponentStatus> { probe.check() } }
        val deadline = System.nanoTime() + properties.probeTimeout.toNanos()
        val results =
            futures.map { (probe, future) ->
                val status =
                    try {
                        future.get((deadline - System.nanoTime()).coerceAtLeast(0), TimeUnit.NANOSECONDS)
                    } catch (_: TimeoutException) {
                        future.cancel(true)
                        log.warn("Health probe '{}' timed out", probe.name)
                        ComponentStatus.DOWN
                    } catch (e: ExecutionException) {
                        log.warn("Health probe '{}' failed: {}", probe.name, e.cause?.javaClass?.simpleName)
                        ComponentStatus.DOWN
                    }
                probe to status
            }
        return HealthSnapshot(aggregate(results), now, results.map { (p, s) -> ComponentHealth(p.name, s) })
    }

    companion object {
        /** Public order of components in the response. */
        val PROBE_ORDER = listOf("database", "objectStorage", "documentServer", "malwareScanner", "mail")

        /** DOWN if a critical component is down; DEGRADED if any other enabled component is down; otherwise UP. */
        fun aggregate(results: List<Pair<ComponentProbe, ComponentStatus>>): HealthStatus =
            HealthStatus.worstOf(
                results.map { (probe, status) ->
                    when {
                        status != ComponentStatus.DOWN -> HealthStatus.UP
                        probe.critical -> HealthStatus.DOWN
                        else -> HealthStatus.DEGRADED
                    }
                },
            )
    }
}
