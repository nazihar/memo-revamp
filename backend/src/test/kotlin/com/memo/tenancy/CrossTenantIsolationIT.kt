package com.memo.tenancy

import com.memo.common.tenant.TenantId
import com.memo.common.tenant.TenantTransactions
import com.memo.testsupport.AppRoleJdbc
import com.memo.testsupport.CrossTenantProbes
import com.memo.testsupport.IntegrationTest
import com.memo.testsupport.aSetting
import com.memo.testsupport.aTenant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired

/** Tenant B can never see or change tenant A's data, at the database level and through the application API. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CrossTenantIsolationIT : IntegrationTest() {
    @Autowired lateinit var registry: TenantRegistry

    @Autowired lateinit var settings: TenantSettings

    @Autowired lateinit var transactions: TenantTransactions

    private val a: TenantId by lazy { registry.aTenant("a") }
    private val b: TenantId by lazy { registry.aTenant("b") }

    @BeforeAll
    fun tenants() {
        transactions.aSetting(settings, a, key = "shared.key", value = "secret-of-a")
        transactions.aSetting(settings, b, key = "shared.key", value = "value-of-b")
    }

    @Test
    fun `database blocks every cross-tenant probe in both directions`() {
        assertThat(CrossTenantProbes.run(attacker = b, victim = a)).isEmpty()
        assertThat(CrossTenantProbes.run(attacker = a, victim = b)).isEmpty()
    }

    @Test
    fun `application API only returns the current tenant's data`() {
        val seenByB = transactions.inTenant(b) { settings.list() }
        assertThat(seenByB.map { it.value }).containsExactly("value-of-b")

        transactions.inTenant(b) {
            assertThat(settings.get("shared.key")!!.value).isEqualTo("value-of-b")
            assertThat(registry.current()!!.id).isEqualTo(b)
        }
        transactions.inTenant(a) { assertThat(settings.get("shared.key")!!.value).isEqualTo("secret-of-a") }
    }

    @Test
    fun `deleting in one tenant never touches another tenant's row with the same key`() {
        transactions.inTenant(b) { settings.put("temp.key", "b") }
        transactions.inTenant(a) { settings.put("temp.key", "a") }
        transactions.inTenant(b) { assertThat(settings.delete("temp.key")).isTrue() }
        transactions.inTenant(a) { assertThat(settings.get("temp.key")!!.value).isEqualTo("a") }
    }

    @Test
    fun `tenant predicate is evaluated once per query (InitPlan), not per row`() {
        val plan =
            AppRoleJdbc.inTransaction(a) { c ->
                c.createStatement().use { st ->
                    st.executeQuery("explain select * from tenancy_setting where key = 'x'").use { rs ->
                        buildString { while (rs.next()) appendLine(rs.getString(1)) }
                    }
                }
            }
        assertThat(plan).contains("InitPlan")
    }
}
