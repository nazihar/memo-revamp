package com.memo.audit

import com.memo.common.context.ActorContext
import com.memo.common.tenant.TenantTransactions
import com.memo.tenancy.TenantRegistry
import com.memo.tenancy.TenantSettings
import com.memo.testsupport.AppRoleJdbc
import com.memo.testsupport.AppRoleJdbc.update
import com.memo.testsupport.Audit
import com.memo.testsupport.IntegrationTest
import com.memo.testsupport.OwnerJdbc
import com.memo.testsupport.aTenant
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.sql.SQLException

/** ADR-0019: what gets recorded, where it may be recorded, and that the store is append-only. */
class AuditRecordIT : IntegrationTest() {
    @Autowired lateinit var registry: TenantRegistry

    @Autowired lateinit var settings: TenantSettings

    @Autowired lateinit var transactions: TenantTransactions

    @Autowired lateinit var audit: AuditLog

    @Autowired lateinit var transactionManager: PlatformTransactionManager

    @Test
    fun `a new tenant's chain starts with tenant created`() {
        val a = registry.aTenant("aud-new")
        val first = Audit.entries(a).first()
        assertThat(first.seq).isEqualTo(1)
        assertThat(first.action).isEqualTo("tenant.created")
        assertThat(first.objectId).isEqualTo(a.toString())
        assertThat(first.actorType).isEqualTo("SYSTEM")
        assertThat(first.actorId).isEqualTo("test")
        Audit.assertChainValid(a)
    }

    @Test
    fun `setting changes are recorded with before and after values and the actor`() {
        val a = registry.aTenant("aud-set")
        ActorContext.asSystem("settings-admin") {
            transactions.inTenant(a) { settings.put("ui.theme", "light") }
            transactions.inTenant(a) { settings.put("ui.theme", "dark") }
            transactions.inTenant(a) { settings.delete("ui.theme") }
        }
        val created = Audit.assertAudited(a, "tenant.setting.created", "ui.theme", actorId = "settings-admin")
        assertThat(created.canonical).contains("\"after\":{\"value\":\"light\"}").contains("\"before\":null")
        val changed = Audit.assertAudited(a, "tenant.setting.changed", "ui.theme")
        assertThat(changed.canonical).contains("\"before\":{\"value\":\"light\"}").contains("\"after\":{\"value\":\"dark\"}")
        val deleted = Audit.assertAudited(a, "tenant.setting.deleted", "ui.theme")
        assertThat(deleted.canonical).contains("\"before\":{\"value\":\"dark\"}").contains("\"after\":null")
        assertThat(Audit.entries(a).map { it.seq }).containsExactly(1L, 2L, 3L, 4L)
        Audit.assertChainValid(a)
    }

    @Test
    fun `secret-looking values never reach the audit record`() {
        val a = registry.aTenant("aud-redact")
        transactions.inTenant(a) { audit.record("test.redaction", "test", "x", after = mapOf("password" to "hunter2", "name" to "ok")) }
        val row = Audit.assertAudited(a, "test.redaction", "x")
        assertThat(row.canonical).doesNotContain("hunter2").contains("[redacted]").contains("\"name\":\"ok\"")
    }

    @Test
    fun `recording outside a read-write tenant transaction is refused`() {
        val a = registry.aTenant("aud-ctx")
        assertThatThrownBy { audit.record("test.outside", "test", null) }.isInstanceOf(AuditContextException::class.java)
        assertThatThrownBy { transactions.readInTenant(a) { audit.record("test.readonly", "test", null) } }
            .isInstanceOf(AuditContextException::class.java)
        assertThatThrownBy { TransactionTemplate(transactionManager).execute { audit.record("test.tenantless", "test", null) } }
            .isInstanceOf(AuditContextException::class.java)
    }

    @Test
    fun `recording without an actor is refused`() {
        val a = registry.aTenant("aud-actor")
        var failure: Throwable? = null
        // A thread that no entry point set up: no actor bound.
        Thread
            .ofVirtual()
            .start { failure = runCatching { transactions.inTenant(a) { audit.record("test.noactor", "test", null) } }.exceptionOrNull() }
            .join()
        assertThat(failure).isInstanceOf(AuditContextException::class.java)
    }

    @Test
    fun `the app role cannot update or delete audit rows and the trigger stops the owner`() {
        val a = registry.aTenant("aud-ro")
        AppRoleJdbc.inTransaction(a) { c ->
            assertThatThrownBy { c.update("update audit_event set action = 'x.y'") }.isInstanceOf(SQLException::class.java)
        }
        AppRoleJdbc.inTransaction(a) { c ->
            assertThatThrownBy { c.update("delete from audit_event") }.isInstanceOf(SQLException::class.java)
        }
        OwnerJdbc.connect { c ->
            c.autoCommit = false
            try {
                // FORCE RLS applies to the owner too: scope to the tenant so the row (and its trigger) is reached.
                c.prepareStatement("select set_config('app.tenant_id', ?, true)").use {
                    it.setString(1, a.toString())
                    it.execute()
                }
                assertThatThrownBy { c.update("update audit_event set action = 'x.y' where tenant_id = ?", a.value) }
                    .hasMessageContaining("append-only")
            } finally {
                c.rollback()
            }
        }
        assertThat(Audit.entries(a).single().action).isEqualTo("tenant.created")
    }
}
