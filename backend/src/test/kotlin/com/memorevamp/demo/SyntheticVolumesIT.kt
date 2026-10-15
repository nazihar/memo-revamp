package com.memorevamp.demo

import com.memorevamp.common.tenant.TenantTransactions
import com.memorevamp.tenancy.TenantSettings
import com.memorevamp.testsupport.IntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.util.UUID

class SyntheticVolumesIT : IntegrationTest() {
    @Autowired lateinit var generator: SyntheticVolumeGenerator

    @Autowired lateinit var settings: TenantSettings

    @Autowired lateinit var transactions: TenantTransactions

    @Test
    fun `generates the requested tenants, each with its own isolated settings`() {
        val runId =
            UUID
                .randomUUID()
                .toString()
                .replace("-", "")
                .take(12)
        val tenants = generator.generate(VolumeSpec(tenants = 3, settingsPerTenant = 5), runId)

        assertThat(tenants).hasSize(3).doesNotHaveDuplicates()
        tenants.forEach { tenant ->
            transactions.inTenant(tenant) { assertThat(settings.list().filter { it.key.startsWith("synthetic.") }).hasSize(5) }
        }
    }

    @Test
    fun `rejects unreasonable specs`() {
        assertThatThrownBy { VolumeSpec(tenants = 0, settingsPerTenant = 1) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { VolumeSpec(tenants = 1, settingsPerTenant = 5_000) }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
