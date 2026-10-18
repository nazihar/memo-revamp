package com.memo.audit

import com.memo.common.context.ActorContext
import com.memo.common.tenant.TenantTransactions
import com.memo.tenancy.TenantRegistry
import com.memo.tenancy.TenantSettings
import com.memo.testsupport.Audit
import com.memo.testsupport.IntegrationTest
import com.memo.testsupport.aTenant
import io.micrometer.core.instrument.MeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** ADR-0019 / threat model D1: concurrent writers in one tenant produce a gapless, valid chain without deadlocks. */
class AuditConcurrencyIT : IntegrationTest() {
    @Autowired lateinit var registry: TenantRegistry

    @Autowired lateinit var settings: TenantSettings

    @Autowired lateinit var transactions: TenantTransactions

    @Autowired lateinit var meters: MeterRegistry

    private val log = LoggerFactory.getLogger(javaClass)

    @Test
    fun `sixteen concurrent writers produce a gapless valid chain`() {
        val a = registry.aTenant("aud-conc")
        val writers = 16
        val perWriter = 50
        val barrier = CyclicBarrier(writers)
        val timer = meters.find("memo.audit.flush").timer()!!
        val before = timer.count()
        val started = System.nanoTime()
        Executors.newFixedThreadPool(writers).use { pool ->
            val futures =
                (1..writers).map { w ->
                    pool.submit {
                        ActorContext.asSystem("writer-$w") {
                            barrier.await(30, TimeUnit.SECONDS)
                            repeat(perWriter) { i -> transactions.inTenant(a) { settings.put("conc.w$w.k$i", "v") } }
                        }
                    }
                }
            futures.forEach { it.get(5, TimeUnit.MINUTES) }
        }
        val elapsedS = (System.nanoTime() - started) / 1e9
        val seqs = Audit.entries(a).map { it.seq }
        assertThat(seqs).isEqualTo((1L..(writers * perWriter + 1L)).toList())
        Audit.assertChainValid(a)
        val p95Ms =
            timer
                .takeSnapshot()
                .percentileValues()
                .firstOrNull { it.percentile() == 0.95 }
                ?.value(TimeUnit.MILLISECONDS)
        log.info(
            "audit flush: {} flushes, p95 {} ms (includes queueing on the head lock), {} audited commits/s in one tenant",
            timer.count() - before,
            p95Ms,
            "%.0f".format(writers * perWriter / elapsedS),
        )
        // Design §10: throughput per tenant is the budget under contention; only gross regressions fail on noisy runners.
        assertThat(writers * perWriter / elapsedS).isGreaterThan(50.0)
    }

    @Test
    fun `an uncontended flush is cheap`() {
        val a = registry.aTenant("aud-solo")
        val timer = meters.find("memo.audit.flush").timer()!!
        val before = timer.totalTime(TimeUnit.MILLISECONDS)
        val runs = 50
        repeat(runs) { i -> transactions.inTenant(a) { settings.put("solo.k$i", "v") } }
        val meanMs = (timer.totalTime(TimeUnit.MILLISECONDS) - before) / runs
        log.info("audit flush uncontended: mean {} ms over {} commits", "%.2f".format(meanMs), runs)
        // Design §10 budget is 5 ms; allow headroom for CI runners.
        assertThat(meanMs).isLessThan(25.0)
    }
}
