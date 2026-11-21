package com.memo.common.cache

import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * A load that an eviction overtakes is not kept (ADR-0020, found in E05 T6): an eviction arriving while a value is being
 * loaded cannot remove the entry still being computed, so without the check the load would keep the old value until the
 * TTL. Here the eviction lands inside the loader, as another node's notification would.
 */
class TenantCacheTest {
    private val tenant = TenantId(UUID.randomUUID())
    private val cache = TenantCache<String, String>("test", Duration.ofMinutes(10), 100)

    @Test
    fun `a value loaded while an eviction landed is served once and loaded again on the next read`() {
        val loads = AtomicInteger()
        val first =
            TenantContext.call(tenant) {
                cache.get("relay") {
                    loads.incrementAndGet()
                    cache.evict(tenant, "relay")
                    "none"
                }
            }
        val second = TenantContext.call(tenant) { cache.get("relay") { "saved".also { loads.incrementAndGet() } } }

        assertThat(first).isEqualTo("none")
        assertThat(second).isEqualTo("saved")
        assertThat(loads.get()).isEqualTo(2)
    }

    @Test
    fun `without an eviction a value is loaded once, and an eviction afterwards removes it`() {
        val loads = AtomicInteger()
        val load = { TenantContext.call(tenant) { cache.get("relay") { "v${loads.incrementAndGet()}" } } }

        assertThat(listOf(load(), load())).containsExactly("v1", "v1")
        cache.evict(tenant, "relay")
        assertThat(load()).isEqualTo("v2")
        cache.evictAll()
        assertThat(load()).isEqualTo("v3")
    }
}
