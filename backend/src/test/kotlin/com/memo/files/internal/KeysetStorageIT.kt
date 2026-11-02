package com.memo.files.internal

import com.google.crypto.tink.TinkProtoKeysetFormat
import com.google.crypto.tink.streamingaead.StreamingAeadConfig2026
import com.memo.audit.AuditLog
import com.memo.common.cache.TenantCaches
import com.memo.common.context.ActorContext
import com.memo.common.crypto.KeyProvider
import com.memo.common.crypto.SoftwareKeyProvider
import com.memo.common.tenant.TenantDsl
import com.memo.common.tenant.TenantTransactions
import com.memo.tenancy.TenantRegistry
import com.memo.testsupport.AppRoleJdbc
import com.memo.testsupport.AppRoleJdbc.long
import com.memo.testsupport.Audit
import com.memo.testsupport.IntegrationTest
import com.memo.testsupport.aTenant
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.security.GeneralSecurityException
import java.time.Clock
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** ADR-0028: one keyset per tenant, created once across nodes, stored only wrapped, never usable by another tenant. */
class KeysetStorageIT : IntegrationTest() {
    @Autowired lateinit var registry: TenantRegistry

    @Autowired lateinit var transactions: TenantTransactions

    @Autowired lateinit var tenantDsl: TenantDsl

    @Autowired lateinit var keys: KeyProvider

    @Autowired lateinit var audit: AuditLog

    @Autowired lateinit var clock: Clock

    @Autowired internal lateinit var crypto: FileCrypto

    /** A second "node": its own cache, so it really goes to the database. */
    private fun aNode() = TenantKeysets(transactions, tenantDsl, keys, audit, clock, TenantCaches())

    @Test
    fun `two nodes racing on first use end up with one keyset, created and audited once`() {
        val tenant = registry.aTenant("keys")
        val nodes = List(4) { aNode() }
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(nodes.size)
        try {
            val ids =
                nodes
                    .map { node ->
                        pool.submit<Long> {
                            start.await()
                            ActorContext.asSystem("test") { node.cipher(tenant).primaryKeyId }
                        }
                    }.also { start.countDown() }
                    .map { it.get(60, TimeUnit.SECONDS) }

            assertThat(ids.toSet()).hasSize(1)
        } finally {
            pool.shutdownNow()
        }
        assertThat(AppRoleJdbc.inTransaction(tenant) { it.long("select count(*) from files_tenant_keyset") }).isEqualTo(1)
        assertThat(Audit.entries(tenant).filter { it.action == "files.keyset.created" }).hasSize(1)
        assertThat(Audit.entries(tenant).single { it.action == "files.keyset.created" }.canonical)
            .contains(SoftwareKeyProvider.ID)
            .doesNotContain("keyValue")
    }

    @Test
    fun `the stored keyset opens only with the KEK and its own tenant id`() {
        val tenant = registry.aTenant("wrap")
        crypto.primaryKeyId(tenant)
        val stored =
            AppRoleJdbc.inTransaction(tenant) { c ->
                c.prepareStatement("select encrypted_keyset from files_tenant_keyset").use { st ->
                    st.executeQuery().use {
                        it.next()
                        it.getBytes(1)
                    }
                }
            }

        assertThatThrownBy {
            TinkProtoKeysetFormat.parseKeysetWithoutSecret(stored, StreamingAeadConfig2026.get())
        }.isInstanceOf(GeneralSecurityException::class.java)
        assertThatThrownBy {
            TinkProtoKeysetFormat.parseEncryptedKeyset(
                stored,
                keys.kek(keys.currentKekId()),
                "memo/files/keyset/v1/${UUID.randomUUID()}".toByteArray(),
                StreamingAeadConfig2026.get(),
            )
        }.isInstanceOf(GeneralSecurityException::class.java)
    }

    @Test
    fun `tenant B's keyset cannot open tenant A's ciphertext, even under A's ids`() {
        val a = registry.aTenant("a")
        val b = registry.aTenant("b")
        val file = UUID.randomUUID()
        val version = UUID.randomUUID()
        val out = ByteArrayOutputStream()
        crypto.encrypting(a, file, version, Channels.newChannel(out)).use { it.write(ByteBuffer.wrap("tenant A memo".toByteArray())) }

        val readBack = ByteBuffer.allocate(64)
        crypto.decrypting(a, file, version, SeekableInMemoryByteChannel(out.toByteArray())).use { it.read(readBack) }
        assertThat(String(readBack.array(), 0, readBack.position())).isEqualTo("tenant A memo")

        assertThatThrownBy {
            crypto.decrypting(b, file, version, SeekableInMemoryByteChannel(out.toByteArray())).use { it.read(ByteBuffer.allocate(64)) }
        }.isInstanceOfAny(java.io.IOException::class.java, GeneralSecurityException::class.java)
    }

    @Test
    fun `creating a keyset inside a transaction is refused, so an uncommitted keyset is never cached`() {
        val tenant = registry.aTenant("tx")

        assertThatThrownBy { transactions.inTenant(tenant) { aNode().cipher(tenant) } }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("own transaction")
    }
}
