package com.memo.common.crypto

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.AEADBadTagException

class DataKeysTest {
    private fun aSecret() = Base64.getEncoder().encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })

    private val keys = DataKeys(aSecret())
    private val plaintext = "a TOTP secret".toByteArray()

    @Test
    fun `round-trips, with a fresh nonce every time`() {
        val first = keys.encrypt(plaintext, "ctx")
        val second = keys.encrypt(plaintext, "ctx")

        assertThat(keys.decrypt(first, "ctx")).isEqualTo(plaintext)
        assertThat(first).isNotEqualTo(second)
        assertThat(String(first, Charsets.ISO_8859_1)).doesNotContain("a TOTP secret")
    }

    @Test
    fun `a ciphertext moved to another context, tampered with, or under another key does not decrypt`() {
        val sealed = keys.encrypt(plaintext, "identity.mfa:tenant-a:user-1")

        assertThatThrownBy { keys.decrypt(sealed, "identity.mfa:tenant-a:user-2") }.isInstanceOf(AEADBadTagException::class.java)
        assertThatThrownBy {
            keys.decrypt(
                sealed.copyOf().also { it[it.size - 1] = (it.last() + 1).toByte() },
                "identity.mfa:tenant-a:user-1",
            )
        }.isInstanceOf(AEADBadTagException::class.java)
        assertThatThrownBy {
            DataKeys(
                aSecret(),
            ).decrypt(sealed, "identity.mfa:tenant-a:user-1")
        }.isInstanceOf(AEADBadTagException::class.java)
    }

    @Test
    fun `a missing or short key fails fast`() {
        assertThatThrownBy { DataKeys("").verifyConfigured() }.hasMessageContaining("MEMO_DATA_KEY")
        assertThatThrownBy { DataKeys("short").verifyConfigured() }.hasMessageContaining("at least 32")
    }
}
