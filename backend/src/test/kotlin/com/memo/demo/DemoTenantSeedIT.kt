package com.memo.demo

import com.memo.common.tenant.TenantTransactions
import com.memo.tenancy.TenantRegistry
import com.memo.tenancy.TenantSettings
import com.memo.testsupport.IntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.core.env.StandardEnvironment

class DemoTenantSeedIT : IntegrationTest() {
    @Autowired lateinit var seeder: DemoTenantSeeder

    @Autowired lateinit var registry: TenantRegistry

    @Autowired lateinit var settings: TenantSettings

    @Autowired lateinit var transactions: TenantTransactions

    @Test
    fun `seeding is idempotent and produces the demo tenant as defined in code`() {
        seeder.seed()
        seeder.seed()

        transactions.inTenant(DemoTenant.ID) {
            val tenant = registry.current()!!
            assertThat(tenant.slug).isEqualTo(DemoTenant.SLUG)
            assertThat(tenant.name).isEqualTo(DemoTenant.NAME)
            assertThat(settings.list().associate { it.key to it.value }).containsAllEntriesOf(DemoTenant.SETTINGS)
            assertThat(settings.list().map { it.version }).allMatch { it == 0L }
        }
    }

    @Test
    fun `refuses to seed under the prod profile`() {
        val prod = StandardEnvironment().apply { setActiveProfiles("prod") }
        val prodSeeder = DemoTenantSeeder(registry, settings, transactions, prod)
        assertThatThrownBy { prodSeeder.seed() }.hasMessageContaining("never be seeded in production")
    }
}
