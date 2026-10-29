package com.memo.identity.password

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class PasswordEncodingTest {
    private val password = "violet harbour lantern sixteen"

    @Test
    fun `argon2id is the default, with the OWASP parameters`() {
        val hash = PasswordEncoding().passwordEncoder("argon2").encode(password)!!

        assertThat(hash).startsWith("{argon2}\$argon2id\$v=19\$m=19456,t=2,p=1\$")
    }

    @Test
    fun `pbkdf2 deployments still verify argon2 hashes and upgrade them`() {
        val argon2Hash = PasswordEncoding().passwordEncoder("argon2").encode(password)!!
        val fips = PasswordEncoding().passwordEncoder("pbkdf2")

        assertThat(fips.matches(password, argon2Hash)).isTrue()
        assertThat(fips.upgradeEncoding(argon2Hash)).isTrue()
        val pbkdf2Hash = fips.encode(password)!!
        assertThat(pbkdf2Hash).startsWith("{pbkdf2}")
        assertThat(fips.matches(password, pbkdf2Hash)).isTrue()
        assertThat(fips.matches("violet harbour lantern seventeen", pbkdf2Hash)).isFalse()
    }

    @Test
    fun `an unknown encoding fails at startup`() {
        assertThatThrownBy { PasswordEncoding().passwordEncoder("md5") }.hasMessageContaining("password-encoding")
    }
}
