package com.memo.common.crypto

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.Base64

class SoftwareKeyProviderTest {
    private fun aSecret() = Base64.getEncoder().encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })

    private val secret = aSecret()
    private val provider = SoftwareKeyProvider(secret)

    @Test
    fun `the KEK is derived from the secret, so the same secret unwraps and another does not`() {
        val wrapped = provider.kek(provider.currentKekId()).encrypt("a tenant keyset".toByteArray(), "tenant-a".toByteArray())

        assertThat(SoftwareKeyProvider(secret).kek(SoftwareKeyProvider.ID).decrypt(wrapped, "tenant-a".toByteArray()))
            .isEqualTo("a tenant keyset".toByteArray())
        assertThatThrownBy { provider.kek(SoftwareKeyProvider.ID).decrypt(wrapped, "tenant-b".toByteArray()) }
            .isInstanceOf(GeneralSecurityException::class.java)
        assertThatThrownBy { SoftwareKeyProvider(aSecret()).kek(SoftwareKeyProvider.ID).decrypt(wrapped, "tenant-a".toByteArray()) }
            .isInstanceOf(GeneralSecurityException::class.java)
    }

    @Test
    fun `the KEK is not the key DataKeys derives from the same secret`() {
        val sealed = DataKeys(secret).encrypt("a TOTP secret".toByteArray(), "ctx")

        // Same layout as Tink's AES-GCM (12-byte nonce, ciphertext, tag) after DataKeys' version byte.
        assertThatThrownBy { provider.kek(SoftwareKeyProvider.ID).decrypt(sealed.copyOfRange(1, sealed.size), "ctx".toByteArray()) }
            .isInstanceOf(GeneralSecurityException::class.java)
    }

    @Test
    fun `an unknown KEK id or a missing secret fails`() {
        assertThatThrownBy { provider.kek("kms-1") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { SoftwareKeyProvider("short").kek(SoftwareKeyProvider.ID) }.hasMessageContaining("MEMO_DATA_KEY")
    }
}
