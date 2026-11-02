package com.memo.files.internal

import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.StreamingAead
import com.google.crypto.tink.TinkProtoKeysetFormat
import com.google.crypto.tink.streamingaead.AesGcmHkdfStreamingParameters
import com.google.crypto.tink.streamingaead.StreamingAeadConfig2026
import com.memo.audit.AuditLog
import com.memo.common.cache.TenantCache
import com.memo.common.cache.TenantCaches
import com.memo.common.crypto.KeyProvider
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantDsl
import com.memo.common.tenant.TenantId
import com.memo.common.tenant.TenantTransactions
import com.memo.jooq.tables.references.FILES_TENANT_KEYSET
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.nio.channels.WritableByteChannel
import java.time.Clock
import java.time.Duration
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/** The tenant's streaming AEAD with the id of the primary key, which new ciphertexts use. */
internal class TenantCipher(
    val aead: StreamingAead,
    val primaryKeyId: Long,
)

/**
 * One Tink streaming keyset per tenant, wrapped by the key provider's KEK with the tenant id as associated data (ADR-0028).
 * Unwrapped keysets are cached for [TTL]; file content never is.
 */
@Component
internal class TenantKeysets(
    private val transactions: TenantTransactions,
    private val tenantDsl: TenantDsl,
    private val keys: KeyProvider,
    private val audit: AuditLog,
    private val clock: Clock,
    caches: TenantCaches,
) {
    private val cache: TenantCache<String, TenantCipher> = caches.register(CACHE, TTL)

    /**
     * The tenant's cipher, creating the keyset on first use. Creation commits in its own transaction before the keyset is
     * cached or used, so call it outside a transaction when the tenant may not have a keyset yet (the upload path does).
     */
    fun cipher(tenant: TenantId): TenantCipher =
        TenantContext.call(tenant) {
            cache.get(KEY) { transactions.readInTenant(tenant) { load(tenant) } ?: create(tenant) }
        }

    private fun load(tenant: TenantId): TenantCipher? =
        tenantDsl
            .current()
            .select(FILES_TENANT_KEYSET.ENCRYPTED_KEYSET, FILES_TENANT_KEYSET.KEK_ID)
            .from(FILES_TENANT_KEYSET)
            .fetchOne()
            ?.let { (encrypted, kekId) -> unwrap(tenant, encrypted!!, kekId!!) }

    private fun create(tenant: TenantId): TenantCipher {
        check(!TransactionSynchronizationManager.isActualTransactionActive()) {
            "a tenant keyset is created in its own transaction; call cipher() before opening one"
        }
        val handle = KeysetHandle.generateNew(PARAMETERS, StreamingAeadConfig2026.get())
        val kekId = keys.currentKekId()
        val wrapped =
            TinkProtoKeysetFormat.serializeEncryptedKeyset(handle, keys.kek(kekId), associatedData(tenant), StreamingAeadConfig2026.get())
        val primaryKeyId = handle.primary.id.toLong()
        transactions.inTenant(tenant) {
            val inserted =
                tenantDsl
                    .current()
                    .insertInto(FILES_TENANT_KEYSET)
                    .set(FILES_TENANT_KEYSET.TENANT_ID, tenant.value)
                    .set(FILES_TENANT_KEYSET.ENCRYPTED_KEYSET, wrapped)
                    .set(FILES_TENANT_KEYSET.KEK_ID, kekId)
                    .set(FILES_TENANT_KEYSET.PRIMARY_KEY_ID, primaryKeyId)
                    .set(FILES_TENANT_KEYSET.CREATED_AT, OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC))
                    .onConflictDoNothing()
                    .execute()
            if (inserted == 1) {
                audit.record(
                    "files.keyset.created",
                    "files.keyset",
                    tenant.toString(),
                    after = mapOf("kekId" to kekId, "primaryKeyId" to primaryKeyId),
                )
            }
        }
        // Another node may have won the insert: everyone uses the stored keyset.
        return transactions.readInTenant(tenant) { load(tenant) } ?: error("tenant keyset missing after insert")
    }

    private fun unwrap(
        tenant: TenantId,
        encrypted: ByteArray,
        kekId: String,
    ): TenantCipher {
        val handle =
            TinkProtoKeysetFormat.parseEncryptedKeyset(encrypted, keys.kek(kekId), associatedData(tenant), StreamingAeadConfig2026.get())
        return TenantCipher(handle.getPrimitive(StreamingAeadConfig2026.get(), StreamingAead::class.java), handle.primary.id.toLong())
    }

    companion object {
        const val CACHE = "files.keysets"
        private const val KEY = "keyset"
        private val TTL: Duration = Duration.ofMinutes(10)
        private const val KEY_BYTES = 32
        const val SEGMENT_BYTES = 64 * 1024

        internal val PARAMETERS: AesGcmHkdfStreamingParameters =
            AesGcmHkdfStreamingParameters
                .builder()
                .setKeySizeBytes(KEY_BYTES)
                .setDerivedAesGcmKeySizeBytes(KEY_BYTES)
                .setHkdfHashType(AesGcmHkdfStreamingParameters.HashType.SHA256)
                .setCiphertextSegmentSizeBytes(SEGMENT_BYTES)
                .build()

        private fun associatedData(tenant: TenantId) = "memo/files/keyset/v1/$tenant".toByteArray(Charsets.UTF_8)
    }
}

/** Encrypts and decrypts file content with the tenant's keyset; the associated data binds a ciphertext to its version. */
@Component
internal class FileCrypto(
    private val keysets: TenantKeysets,
) {
    /** The id of the key new ciphertexts of [tenant] use (stored with the version). */
    fun primaryKeyId(tenant: TenantId): Long = keysets.cipher(tenant).primaryKeyId

    fun encrypting(
        tenant: TenantId,
        file: UUID,
        version: UUID,
        ciphertext: WritableByteChannel,
    ): WritableByteChannel = keysets.cipher(tenant).aead.newEncryptingChannel(ciphertext, associatedData(tenant, file, version))

    fun decrypting(
        tenant: TenantId,
        file: UUID,
        version: UUID,
        ciphertext: SeekableByteChannel,
    ): SeekableByteChannel {
        val channel = keysets.cipher(tenant).aead.newSeekableDecryptingChannel(ciphertext, associatedData(tenant, file, version))
        // A keyset's channel picks the matching key on its first read and cannot tell its size before: read once, rewind.
        channel.read(ByteBuffer.allocate(1))
        return channel.position(0)
    }

    companion object {
        fun associatedData(
            tenant: TenantId,
            file: UUID,
            version: UUID,
        ): ByteArray = "memo/file/v1/$tenant/$file/$version".toByteArray(Charsets.UTF_8)
    }
}
