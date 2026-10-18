package com.memo.common.cache

import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantId
import com.memo.common.tenant.TenantTransactions
import com.memo.tenancy.TenantRegistry
import com.memo.tenancy.TenantSettings
import com.memo.testsupport.AppRoleJdbc
import com.memo.testsupport.IntegrationTest
import com.memo.testsupport.TestDatabase
import com.memo.testsupport.aTenant
import com.memo.testsupport.awaitCacheNotificationsApplied
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

/** ADR-0020: a committed change evicts the entry on every node; a rollback evicts nothing; a reconnect flushes. */
class CacheInvalidationIT : IntegrationTest() {
    @Autowired lateinit var registry: TenantRegistry

    @Autowired lateinit var settings: TenantSettings

    @Autowired lateinit var transactions: TenantTransactions

    /** A second node: its own caches and its own listener connection. */
    private val nodeBCaches = TenantCaches()
    private val nodeBSettings = nodeBCaches.register<String, String>(TenantSettings.CACHE)
    private lateinit var nodeB: PgNotificationListener
    private val loads = AtomicInteger()

    @BeforeEach
    fun startNodeB() {
        nodeB =
            PgNotificationListener(
                nodeBCaches,
                SimpleMeterRegistry(),
                TestDatabase.jdbcUrl,
                "memo_app",
                TestDatabase.appPassword,
                enabled = true,
                applicationName = "node-b-listener",
            )
        nodeB.start()
        await().atMost(Duration.ofSeconds(10)).until { nodeB.isConnected }
    }

    @AfterEach
    fun stopNodeB() = nodeB.stop()

    /** Reads through node B's cache; counts loads so a miss is visible. */
    private fun readOnNodeB(
        tenant: TenantId,
        key: String,
    ): String = TenantContext.call(tenant) { nodeBSettings.get(key) { "loaded-${loads.incrementAndGet()}" } }

    /** Waits until node B has applied every notification committed so far. */
    private fun syncNodeB() = awaitCacheNotificationsApplied(nodeB)

    @Test
    fun `a committed change on node A evicts the entry on node B`() {
        val a = registry.aTenant("cache-a")
        transactions.inTenant(a) { settings.put("cache.k", "v1") }
        syncNodeB()
        val first = readOnNodeB(a, "cache.k")
        assertThat(readOnNodeB(a, "cache.k")).describedAs("served from node B's cache").isEqualTo(first)
        transactions.inTenant(a) { settings.put("cache.k", "v2") }
        await().atMost(Duration.ofSeconds(2)).until { readOnNodeB(a, "cache.k") != first }
    }

    @Test
    fun `a rolled-back change evicts nothing`() {
        val a = registry.aTenant("cache-rb")
        transactions.inTenant(a) { settings.put("cache.k", "v1") }
        syncNodeB()
        val first = readOnNodeB(a, "cache.k")
        assertThatThrownBy {
            transactions.inTenant(a) {
                settings.put("cache.k", "v2")
                error("roll back")
            }
        }.hasMessage("roll back")
        // A committed change to another key proves notifications flow; the rolled-back key stays cached.
        val marker = readOnNodeB(a, "cache.marker")
        transactions.inTenant(a) { settings.put("cache.marker", "m") }
        await().atMost(Duration.ofSeconds(2)).until { readOnNodeB(a, "cache.marker") != marker }
        assertThat(readOnNodeB(a, "cache.k")).isEqualTo(first)
    }

    @Test
    fun `a lost listener connection reconnects and flushes every cache`() {
        val a = registry.aTenant("cache-lost")
        val first = readOnNodeB(a, "cache.k")
        val connectsBefore = nodeB.connects
        AppRoleJdbc.connect { c ->
            c.createStatement().use {
                it.execute("select pg_terminate_backend(pid) from pg_stat_activity where application_name = 'node-b-listener'")
            }
        }
        await().atMost(Duration.ofSeconds(15)).until { nodeB.connects > connectsBefore && nodeB.isConnected }
        assertThat(readOnNodeB(a, "cache.k")).isNotEqualTo(first)
    }
}
