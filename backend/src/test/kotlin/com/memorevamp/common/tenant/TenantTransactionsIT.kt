package com.memorevamp.common.tenant

import com.memorevamp.tenancy.TenantRegistry
import com.memorevamp.tenancy.TenantSettings
import com.memorevamp.testsupport.AppRoleJdbc
import com.memorevamp.testsupport.IntegrationTest
import com.memorevamp.testsupport.aTenant
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/** ADR-0006 tenant-context semantics (design.md §3). */
class TenantTransactionsIT : IntegrationTest() {
    @Autowired lateinit var transactions: TenantTransactions

    @Autowired lateinit var tenantDsl: TenantDsl

    @Autowired lateinit var dsl: DSLContext

    @Autowired lateinit var registry: TenantRegistry

    @Autowired lateinit var settings: TenantSettings

    private fun dbSetting(): String? = dsl.fetchValue("select current_setting('app.tenant_id', true)") as String?

    @Test
    fun `tenant data access outside a tenant transaction fails closed`() {
        assertThatThrownBy { tenantDsl.current() }.isInstanceOf(MissingTenantContextException::class.java)
        assertThatThrownBy { settings.list() }.isInstanceOf(MissingTenantContextException::class.java)
        assertThat(TenantTransactions.currentTenantOrNull()).isNull()
    }

    @Test
    fun `inside a tenant transaction the database setting and the binding agree`() {
        val a = registry.aTenant("ctx")
        transactions.inTenant(a) {
            assertThat(dbSetting()).isEqualTo(a.toString())
            assertThat(tenantDsl.tenant()).isEqualTo(a)
        }
        assertThat(TenantTransactions.currentTenantOrNull()).isNull()
    }

    @Test
    fun `nesting the same tenant joins and nesting another tenant is rejected`() {
        val a = registry.aTenant("nest-a")
        val b = registry.aTenant("nest-b")
        transactions.inTenant(a) {
            transactions.inTenant(a) { assertThat(tenantDsl.tenant()).isEqualTo(a) }
            assertThatThrownBy { transactions.inTenant(b) { } }.isInstanceOf(IllegalStateException::class.java)
        }
    }

    @Test
    fun `binding is removed after rollback`() {
        val a = registry.aTenant("rb")
        assertThatThrownBy { transactions.inTenant(a) { error("boom") } }.hasMessage("boom")
        assertThat(TenantTransactions.currentTenantOrNull()).isNull()
        assertThatThrownBy { tenantDsl.current() }.isInstanceOf(MissingTenantContextException::class.java)
    }

    @Test
    fun `transaction-local setting does not survive the transaction on the same connection`() {
        AppRoleJdbc.connect { c ->
            c.autoCommit = false
            c.createStatement().use { it.execute("select set_config('app.tenant_id', '${java.util.UUID.randomUUID()}', true)") }
            c.commit()
            val after =
                c.createStatement().use { st ->
                    st.executeQuery("select current_setting('app.tenant_id', true)").use { rs ->
                        rs.next()
                        rs.getString(1)
                    }
                }
            c.rollback()
            assertThat(after).isNullOrEmpty()
        }
    }
}
