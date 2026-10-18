package com.memo.audit

import com.memo.audit.internal.AnchorKeys
import com.memo.audit.internal.AuditIntegrity
import com.memo.common.tenant.TenantTransactions
import com.memo.tenancy.TenantRegistry
import com.memo.tenancy.TenantSettings
import com.memo.testsupport.IntegrationTest
import com.memo.testsupport.OwnerJdbc
import com.memo.testsupport.TestObjectStorage
import com.memo.testsupport.aTenant
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import software.amazon.awssdk.services.s3.model.S3Exception
import java.sql.Connection

/** ADR-0019: chain heads are anchored in the object-locked bucket; a consistent rewrite of the tail is caught by the anchor. */
class AuditAnchorIT : IntegrationTest() {
    @Autowired lateinit var registry: TenantRegistry

    @Autowired lateinit var settings: TenantSettings

    @Autowired lateinit var transactions: TenantTransactions

    @Autowired internal lateinit var integrity: AuditIntegrity

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun storage(registry: DynamicPropertyRegistry) {
            registry.add("memo.storage.endpoint") { TestObjectStorage.endpoint.toString() }
            registry.add("memo.storage.access-key") { TestObjectStorage.appKey }
            registry.add("memo.storage.secret-key") { TestObjectStorage.appSecret }
            registry.add("memo.audit.anchor-retention") { "PT2H" }
            registry.add("memo.audit.anchor-retention-mode") { "GOVERNANCE" }
        }
    }

    @Test
    fun `the head is anchored once and the anchor cannot be deleted`() {
        val a = registry.aTenant("anchor-a")
        transactions.inTenant(a) { settings.put("anchor.k", "v") }
        val anchored = integrity.anchor(a.value)!!
        assertThat(anchored.seq).isEqualTo(2)
        assertThat(integrity.anchor(a.value)).describedAs("nothing new to anchor").isNull()
        val key = AnchorKeys.key(a.value, 2)
        val s3 = TestObjectStorage.appClient()
        val body = s3.getObjectAsBytes { it.bucket("records").key(key) }.asUtf8String()
        assertThat(body).contains("\"seq\":2").contains("\"chain\":\"$a\"")
        val version = s3.headObject { it.bucket("records").key(key) }.versionId()
        assertThatThrownBy { s3.deleteObject { it.bucket("records").key(key).versionId(version) } }.isInstanceOf(S3Exception::class.java)
        assertThat(integrity.verify(a.value, full = true)).isInstanceOf(AuditVerification.Ok::class.java)
    }

    @Test
    fun `a tail deleted together with a consistent head rewrite is caught by the anchor`() {
        val a = registry.aTenant("anchor-rewrite")
        (1..3).forEach { i -> transactions.inTenant(a) { settings.put("anchor.k$i", "v") } }
        integrity.anchor(a.value)
        // The owner removes the last entry and moves the head back so entries and head agree again.
        OwnerJdbc.connect { c: Connection ->
            c.autoCommit = false
            c.createStatement().use { it.execute("alter table audit_event disable trigger audit_event_no_update_or_delete") }
            c.prepareStatement("select set_config('app.tenant_id', ?, true)").use {
                it.setString(1, a.toString())
                it.execute()
            }
            c.prepareStatement("delete from audit_event where tenant_id = ? and seq = 4").use {
                it.setObject(1, a.value)
                it.executeUpdate()
            }
            c
                .prepareStatement(
                    "update audit_chain_head set last_seq = 3, " +
                        "last_hash = (select hash from audit_event where tenant_id = ? and seq = 3) where tenant_id = ?",
                ).use {
                    it.setObject(1, a.value)
                    it.setObject(2, a.value)
                    it.executeUpdate()
                }
            c.createStatement().use { it.execute("alter table audit_event enable trigger audit_event_no_update_or_delete") }
            c.commit()
        }
        val result = integrity.verify(a.value, full = true) as AuditVerification.Broken
        assertThat(result.seq).isEqualTo(4)
        assertThat(result.reason).contains("beyond the chain head")
    }
}
