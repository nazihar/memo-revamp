package com.memo.audit

import com.memo.audit.internal.AuditIntegrity
import com.memo.common.tenant.TenantId
import com.memo.common.tenant.TenantTransactions
import com.memo.tenancy.TenantRegistry
import com.memo.tenancy.TenantSettings
import com.memo.testsupport.AppRoleJdbc
import com.memo.testsupport.IntegrationTest
import com.memo.testsupport.OwnerJdbc
import com.memo.testsupport.aTenant
import com.memo.testsupport.query
import io.micrometer.core.instrument.MeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.sql.Connection

/**
 * TESTING_STRATEGY §12 (Phase 1a): tampering with stored audit rows, even by the schema owner with the trigger disabled,
 * is detected by verification, recorded as BROKEN (and stays so), and reported in the platform chain.
 */
class AuditTamperIT : IntegrationTest() {
    @Autowired lateinit var registry: TenantRegistry

    @Autowired lateinit var settings: TenantSettings

    @Autowired lateinit var transactions: TenantTransactions

    @Autowired internal lateinit var integrity: AuditIntegrity

    @Autowired lateinit var meters: MeterRegistry

    /** A tenant with five entries: tenant.created plus four setting changes. */
    private fun tenantWithHistory(label: String): TenantId {
        val a = registry.aTenant(label)
        (1..4).forEach { i -> transactions.inTenant(a) { settings.put("tamper.k$i", "v$i") } }
        return a
    }

    /** As the owner, in the tenant's scope, with the append-only trigger disabled for this transaction only. */
    private fun tamper(
        tenant: TenantId,
        sql: String,
        vararg params: Any,
    ) = OwnerJdbc.connect { c: Connection ->
        c.autoCommit = false
        c.createStatement().use { it.execute("alter table audit_event disable trigger audit_event_no_update_or_delete") }
        c.prepareStatement("select set_config('app.tenant_id', ?, true)").use {
            it.setString(1, tenant.toString())
            it.execute()
        }
        c.prepareStatement(sql).use { st ->
            params.forEachIndexed { i, p -> st.setObject(i + 1, p) }
            st.executeUpdate()
        }
        c.createStatement().use { it.execute("alter table audit_event enable trigger audit_event_no_update_or_delete") }
        c.commit()
    }

    private fun status(tenant: TenantId): Pair<String, Long?> =
        AppRoleJdbc
            .inTransaction(tenant) { c ->
                c.query("select status, broken_at_seq from audit_verification") { it.getString(1) to it.getObject(2) as Long? }
            }.single()

    private fun platformFailures(tenant: TenantId): List<String> =
        AppRoleJdbc.connect { c ->
            c.autoCommit = false
            try {
                c.createStatement().use { it.execute("select set_config('app.scope', 'platform', true)") }
                c.query(
                    "select canonical from audit_event where action = 'audit.chain.verification_failed' and object_id = ?",
                    tenant.toString(),
                ) {
                    it.getString(1)
                }
            } finally {
                c.rollback()
            }
        }

    @Test
    fun `an intact chain verifies and its checkpoint advances`() {
        val a = tenantWithHistory("tamper-ok")
        val first = integrity.verify(a.value, full = false) as AuditVerification.Ok
        assertThat(first.last.seq).isEqualTo(5)
        transactions.inTenant(a) { settings.put("tamper.k5", "v5") }
        val second = integrity.verify(a.value, full = false) as AuditVerification.Ok
        assertThat(second.checked).describedAs("incremental run reads only new entries").isEqualTo(1)
        assertThat(status(a)).isEqualTo("OK" to null)
    }

    @Test
    fun `an edited canonical text is reported at its seq and the chain stays broken`() {
        val a = tenantWithHistory("tamper-canon")
        val before = meters.counter(AuditIntegrity.BROKEN_METRIC).count()
        tamper(a, "update audit_event set canonical = replace(canonical, 'v3', 'vX') where tenant_id = ? and seq = 4", a.value)
        val result = integrity.verify(a.value, full = false)
        assertThat(result).isEqualTo(AuditVerification.Broken(4, "hash does not match the canonical text"))
        assertThat(status(a)).isEqualTo("BROKEN" to 4L)
        assertThat(meters.counter(AuditIntegrity.BROKEN_METRIC).count()).isGreaterThan(before)
        assertThat(platformFailures(a)).singleElement().asString().contains("\"seq\":4")
        // A later run never clears it on its own.
        assertThat(integrity.verify(a.value, full = false)).isInstanceOf(AuditVerification.Broken::class.java)
        assertThat(status(a).first).isEqualTo("BROKEN")
    }

    @Test
    fun `a column edited behind the canonical text is reported`() {
        val a = tenantWithHistory("tamper-col")
        tamper(a, "update audit_event set actor_id = 'someone-else' where tenant_id = ? and seq = 2", a.value)
        val result = integrity.verify(a.value, full = true) as AuditVerification.Broken
        assertThat(result.seq).isEqualTo(2)
        assertThat(result.reason).contains("actor")
    }

    @Test
    fun `a deleted middle entry is reported`() {
        val a = tenantWithHistory("tamper-del")
        tamper(a, "delete from audit_event where tenant_id = ? and seq = 3", a.value)
        assertThat(integrity.verify(a.value, full = true)).isEqualTo(AuditVerification.Broken(3, "expected seq 3 but found 4"))
    }

    @Test
    fun `a deleted tail is reported against the chain head`() {
        val a = tenantWithHistory("tamper-tail")
        tamper(a, "delete from audit_event where tenant_id = ? and seq = 5", a.value)
        val result = integrity.verify(a.value, full = true) as AuditVerification.Broken
        assertThat(result.seq).isEqualTo(5)
        assertThat(result.reason).contains("chain head")
    }
}
