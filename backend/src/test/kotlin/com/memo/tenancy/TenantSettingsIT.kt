package com.memo.tenancy

import com.memo.common.concurrency.ConcurrentModificationException
import com.memo.common.tenant.TenantId
import com.memo.common.tenant.TenantTransactions
import com.memo.testsupport.IntegrationTest
import com.memo.testsupport.aTenant
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TenantSettingsIT : IntegrationTest() {
    @Autowired lateinit var registry: TenantRegistry

    @Autowired lateinit var settings: TenantSettings

    @Autowired lateinit var transactions: TenantTransactions

    private val tenant: TenantId by lazy { registry.aTenant("settings") }

    private fun <T> inTenant(block: () -> T) = transactions.inTenant(tenant, block)

    @Test
    fun `put creates, overwrites and bumps the version`() {
        val created = inTenant { settings.put("ui.theme", "light") }
        val updated = inTenant { settings.put("ui.theme", "dark") }
        assertThat(created.version).isZero()
        assertThat(updated.value).isEqualTo("dark")
        assertThat(updated.version).isEqualTo(1)
        assertThat(inTenant { settings.get("ui.theme") }).isEqualTo(updated)
    }

    @Test
    fun `list is ordered by key and delete removes`() {
        inTenant {
            settings.put("list.b", "2")
            settings.put("list.a", "1")
        }
        assertThat(inTenant { settings.list() }.map { it.key }.filter { it.startsWith("list.") }).containsExactly("list.a", "list.b")
        assertThat(inTenant { settings.delete("list.a") }).isTrue()
        assertThat(inTenant { settings.delete("list.a") }).isFalse()
    }

    @Test
    fun `guarded update rejects a stale version (no lost update)`() {
        val v0 = inTenant { settings.put("lock.key", "v0") }
        inTenant { settings.update("lock.key", "first writer", expectedVersion = v0.version) }
        assertThatThrownBy { inTenant { settings.update("lock.key", "second writer", expectedVersion = v0.version) } }
            .isInstanceOf(ConcurrentModificationException::class.java)
        assertThat(inTenant { settings.get("lock.key") }!!.value).isEqualTo("first writer")
    }

    @Test
    fun `invalid keys are rejected`() {
        assertThatThrownBy { inTenant { settings.put("Bad Key", "x") } }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
