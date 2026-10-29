package com.memo.common.crypto

import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.stereotype.Component
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KDF
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.HKDFParameterSpec

/**
 * Application-level encryption of small secrets at rest (ADR-0024: TOTP secrets): AES-256-GCM with a random 96-bit
 * nonce. Every ciphertext is bound to its context ([aad], for example tenant and user), so a value copied to another row
 * does not decrypt.
 *
 * 1a uses a software key: HKDF-SHA256 over `memo.security.data-key` (`MEMO_DATA_KEY`, at least 32 characters,
 * generated per install). 1b replaces the key source with a KMS/HSM key provider; the version byte leaves room for that.
 * Format: version (1) | nonce (12) | ciphertext + tag (16).
 */
@Component
class DataKeys(
    @param:Value("\${memo.security.data-key:}") private val secret: String,
) {
    private val random = SecureRandom()

    private val key: SecretKey by lazy {
        check(secret.length >= MIN_SECRET) { "memo.security.data-key (MEMO_DATA_KEY) must be set to at least $MIN_SECRET characters" }
        KDF
            .getInstance("HKDF-SHA256")
            .deriveKey(
                "AES",
                HKDFParameterSpec
                    .ofExtract()
                    .addIKM(secret.toByteArray(Charsets.UTF_8))
                    .addSalt(SALT)
                    .thenExpand(INFO, KEY_BYTES),
            )
    }

    /** Fails fast when the key is missing or too short (called at application start). */
    fun verifyConfigured() {
        key
    }

    fun encrypt(
        plaintext: ByteArray,
        aad: String,
    ): ByteArray {
        val nonce = ByteArray(NONCE_BYTES).also { random.nextBytes(it) }
        val cipher = cipher(Cipher.ENCRYPT_MODE, nonce, aad)
        val sealed = cipher.doFinal(plaintext)
        return ByteBuffer
            .allocate(1 + NONCE_BYTES + sealed.size)
            .put(VERSION)
            .put(nonce)
            .put(sealed)
            .array()
    }

    fun decrypt(
        ciphertext: ByteArray,
        aad: String,
    ): ByteArray {
        require(ciphertext.size > 1 + NONCE_BYTES && ciphertext[0] == VERSION) { "unsupported ciphertext" }
        val nonce = ciphertext.copyOfRange(1, 1 + NONCE_BYTES)
        return cipher(Cipher.DECRYPT_MODE, nonce, aad).doFinal(ciphertext, 1 + NONCE_BYTES, ciphertext.size - 1 - NONCE_BYTES)
    }

    private fun cipher(
        mode: Int,
        nonce: ByteArray,
        aad: String,
    ): Cipher =
        Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, key, GCMParameterSpec(TAG_BITS, nonce))
            updateAAD(aad.toByteArray(Charsets.UTF_8))
        }

    private companion object {
        const val MIN_SECRET = 32
        const val KEY_BYTES = 32
        const val NONCE_BYTES = 12
        const val TAG_BITS = 128
        const val VERSION: Byte = 1
        val SALT = "memo data keys".toByteArray(Charsets.UTF_8)
        val INFO = "memo/v1/aes-256-gcm".toByteArray(Charsets.UTF_8)
    }
}

/** Fails the start of the web application when the data key is missing or too short (migrate mode needs none). */
@Component
@ConditionalOnWebApplication
class DataKeysCheck(
    private val keys: DataKeys,
) : ApplicationRunner {
    override fun run(args: ApplicationArguments) = keys.verifyConfigured()
}
