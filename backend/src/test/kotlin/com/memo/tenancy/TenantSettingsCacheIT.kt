package com.memo.tenancy

import com.memo.common.cache.PgNotificationListener
import com.memo.common.tenant.TenantTransactions
import com.memo.testsupport.AppRoleJdbc
import com.memo.testsupport.AppRoleJdbc.update
import com.memo.testsupport.IntegrationTest
import com.memo.testsupport.aTenant
import com.memo.testsupport.awaitCacheNotificationsApplied
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/** ADR-0020: settings reads come from the cache; changes through the service invalidate; a transaction reads its own writes. */
class TenantSettingsCacheIT : IntegrationTest() {
    @Autowired lateinit var registry: TenantRegistry

    @Autowired lateinit var settings: TenantSettings

    @Autowired lateinit var transactions: TenantTransactions

    @Autowired lateinit var listener: PgNotificationListener

    /** Changes the value behind the service's back, so a cache hit is observable. */
    private fun changeDirectly(
        tenant: com.memo.common.tenant.TenantId,
        key: String,
        value: String,
    ) = AppRoleJdbc.connect { c ->
        c.autoCommit = false
        c.prepareStatement("select set_config('app.tenant_id', ?, true)").use {
            it.setString(1, tenant.toString())
            it.execute()
        }
        c.update("update tenancy_setting set value = ? where key = ?", value, key)
        c.commit()
    }

    @Test
    fun `reads are served from the cache until the service changes the value`() {
        val a = registry.aTenant("scache")
        transactions.inTenant(a) { settings.put("sc.k", "v1") }
        // This node also receives its own notification later; let it land before relying on a cache hit.
        awaitCacheNotificationsApplied(listener)
        assertThat(transactions.readInTenant(a) { settings.get("sc.k")?.value }).isEqualTo("v1")
        changeDirectly(a, "sc.k", "behind-the-back")
        assertThat(transactions.readInTenant(a) { settings.get("sc.k")?.value }).describedAs("cache hit").isEqualTo("v1")
        transactions.inTenant(a) { settings.put("sc.k", "v2") }
        assertThat(transactions.readInTenant(a) { settings.get("sc.k")?.value }).isEqualTo("v2")
    }

    @Test
    fun `a transaction reads its own writes and other tenants never share entries`() {
        val a = registry.aTenant("scache-own")
        val b = registry.aTenant("scache-other")
        transactions.inTenant(a) { settings.put("sc.k", "a1") }
        transactions.inTenant(b) { settings.put("sc.k", "b1") }
        assertThat(transactions.readInTenant(a) { settings.get("sc.k")?.value }).isEqualTo("a1")
        assertThat(transactions.readInTenant(b) { settings.get("sc.k")?.value }).isEqualTo("b1")
        transactions.inTenant(a) {
            settings.put("sc.k", "a2")
            assertThat(settings.get("sc.k")?.value).isEqualTo("a2")
        }
    }
}
