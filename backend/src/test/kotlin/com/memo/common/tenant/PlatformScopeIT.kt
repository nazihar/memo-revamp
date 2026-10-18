package com.memo.common.tenant

import com.memo.tenancy.TenantRegistry
import com.memo.tenancy.TenantSettings
import com.memo.testsupport.IntegrationTest
import com.memo.testsupport.TransactionProbe
import com.memo.testsupport.aSetting
import com.memo.testsupport.aTenant
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/** ADR-0017: platform scope lists tenants and sees nothing else. */
class PlatformScopeIT : IntegrationTest() {
    @Autowired lateinit var platform: PlatformTransactions

    @Autowired lateinit var registry: TenantRegistry

    @Autowired lateinit var settings: TenantSettings

    @Autowired lateinit var transactions: TenantTransactions

    @Autowired lateinit var probe: TransactionProbe

    @Autowired lateinit var dsl: DSLContext

    @Test
    fun `platform scope lists every active tenant`() {
        val a = registry.aTenant("plat-a")
        val b = registry.aTenant("plat-b")
        assertThat(registry.listActiveIds()).contains(a, b)
    }

    @Test
    fun `platform scope sees no tenant data`() {
        val a = registry.aTenant("plat-data")
        transactions.aSetting(settings, a)
        platform.read {
            assertThat(probe.tenantRows("tenancy_setting")).isZero()
            assertThat(probe.tenantRows("tenancy_setting_view")).isZero()
            assertThatThrownBy { settings.list() }.isInstanceOf(MissingTenantContextException::class.java)
        }
    }

    @Test
    fun `platform scope cannot change the registry`() {
        val a = registry.aTenant("plat-ro")
        val updated =
            platform.write {
                dsl.execute("update tenancy_tenant set name = 'changed' where id = ?", a.value)
            }
        assertThat(updated).isZero()
        assertThat(transactions.readInTenant(a) { registry.current()!!.name }).isNotEqualTo("changed")
    }

    @Test
    fun `platform transactions do not mix with tenant transactions`() {
        val a = registry.aTenant("plat-mix")
        transactions.inTenant(a) {
            assertThatThrownBy { platform.read { } }.isInstanceOf(IllegalStateException::class.java)
        }
        platform.read {
            assertThatThrownBy { transactions.inTenant(a) { } }.isInstanceOf(IllegalStateException::class.java)
        }
    }
}
