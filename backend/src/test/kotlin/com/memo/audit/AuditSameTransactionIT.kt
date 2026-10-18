package com.memo.audit

import com.memo.common.tenant.TenantTransactions
import com.memo.tenancy.TenantRegistry
import com.memo.tenancy.TenantSettings
import com.memo.testsupport.Audit
import com.memo.testsupport.IntegrationTest
import com.memo.testsupport.aTenant
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/** TESTING_STRATEGY §12 (Phase 1a): rollback leaves no audit and no change; a failed audit write leaves no change. */
class AuditSameTransactionIT : IntegrationTest() {
    @Autowired lateinit var registry: TenantRegistry

    @Autowired lateinit var settings: TenantSettings

    @Autowired lateinit var transactions: TenantTransactions

    @Autowired lateinit var audit: AuditLog

    @Test
    fun `commit keeps both the change and its audit entry`() {
        val a = registry.aTenant("stx-commit")
        transactions.inTenant(a) { settings.put("stx.key", "v") }
        assertThat(transactions.readInTenant(a) { settings.get("stx.key") }).isNotNull
        Audit.assertAudited(a, "tenant.setting.created", "stx.key")
    }

    @Test
    fun `rollback leaves neither the change nor the audit entry`() {
        val a = registry.aTenant("stx-rollback")
        assertThatThrownBy {
            transactions.inTenant(a) {
                settings.put("stx.key", "v")
                error("business failure after the change")
            }
        }.hasMessageContaining("business failure")
        assertThat(transactions.readInTenant(a) { settings.get("stx.key") }).isNull()
        assertThat(Audit.entries(a).map { it.action }).containsExactly("tenant.created")
    }

    @Test
    fun `a failing audit write rolls the business change back`() {
        val a = registry.aTenant("stx-flushfail")
        // Doubles are not allowed in audit values: the pre-commit flush fails, so the commit must fail.
        assertThatThrownBy {
            transactions.inTenant(a) {
                settings.put("stx.key", "v")
                audit.record("test.badvalue", "test", null, after = mapOf("ratio" to 0.5))
            }
        }.isInstanceOf(IllegalArgumentException::class.java)
        assertThat(transactions.readInTenant(a) { settings.get("stx.key") }).isNull()
        assertThat(Audit.entries(a).map { it.action }).containsExactly("tenant.created")
        Audit.assertChainValid(a)
    }
}
