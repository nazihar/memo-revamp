package com.memo.common.crypto

import com.google.crypto.tink.Aead
import com.google.crypto.tink.subtle.AesGcmJce
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import javax.crypto.KDF
import javax.crypto.spec.HKDFParameterSpec

/**
 * Source of the key-encryption keys (KEKs) that wrap tenant data keys (ADR-0028). Wrapped keys store the id of the KEK
 * that wrapped them, so a provider can rotate KEKs and still unwrap old keys. 1a has the [SoftwareKeyProvider]; 1b adds
 * KMS/HSM providers, where the KEK never leaves the device and [kek] returns a remote AEAD.
 */
interface KeyProvider {
    /** The KEK that wraps new keys. */
    fun currentKekId(): String

    /** The KEK [id] as an AEAD; throws [IllegalArgumentException] for an id this provider does not know. */
    fun kek(id: String): Aead
}

/**
 * KEK derived in memory from `memo.security.data-key` (`MEMO_DATA_KEY`) with HKDF-SHA256 under its own label, so it never
 * equals the key [DataKeys] derives from the same secret. AES-256-GCM.
 */
@Component
class SoftwareKeyProvider(
    @param:Value("\${memo.security.data-key:}") private val secret: String,
) : KeyProvider {
    private val aead: Aead by lazy {
        check(secret.length >= MIN_SECRET) { "memo.security.data-key (MEMO_DATA_KEY) must be set to at least $MIN_SECRET characters" }
        val key =
            KDF
                .getInstance("HKDF-SHA256")
                .deriveData(
                    HKDFParameterSpec
                        .ofExtract()
                        .addIKM(secret.toByteArray(Charsets.UTF_8))
                        .addSalt(SALT)
                        .thenExpand(INFO, KEY_BYTES),
                )
        try {
            AesGcmJce(key)
        } finally {
            key.fill(0)
        }
    }

    override fun currentKekId(): String = ID

    override fun kek(id: String): Aead {
        require(id == ID) { "unknown key-encryption key '$id'" }
        return aead
    }

    companion object {
        const val ID = "software-v1"
        private const val MIN_SECRET = 32
        private const val KEY_BYTES = 32
        private val SALT = "memo key-encryption keys".toByteArray(Charsets.UTF_8)
        private val INFO = "memo/v1/kek".toByteArray(Charsets.UTF_8)
    }
}
