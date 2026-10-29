package com.memo.identity.password

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder
import org.springframework.security.crypto.password.DelegatingPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.crypto.password.Pbkdf2PasswordEncoder

/**
 * Password hashing (ADR-0023): Argon2id by default (OWASP: m = 19 MiB, t = 2, p = 1); PBKDF2-HMAC-SHA256 with 600,000
 * iterations for deployments that need FIPS-approved algorithms (`memo.security.password-encoding=pbkdf2`). The `{id}`
 * prefix in each stored hash lets both coexist; logins re-hash with the configured default.
 */
@Configuration(proxyBeanMethods = false)
class PasswordEncoding {
    @Bean
    fun passwordEncoder(
        @Value("\${memo.security.password-encoding:argon2}") default: String,
    ): PasswordEncoder {
        val encoders: Map<String, PasswordEncoder> =
            mapOf(
                ARGON2 to Argon2PasswordEncoder(SALT_BYTES, HASH_BYTES, PARALLELISM, MEMORY_KIB, ITERATIONS),
                PBKDF2 to
                    Pbkdf2PasswordEncoder(
                        "",
                        SALT_BYTES,
                        PBKDF2_ITERATIONS,
                        Pbkdf2PasswordEncoder.SecretKeyFactoryAlgorithm.PBKDF2WithHmacSHA256,
                    ),
            )
        require(default in encoders) { "memo.security.password-encoding must be one of ${encoders.keys}" }
        return DelegatingPasswordEncoder(default, encoders)
    }

    companion object {
        const val ARGON2 = "argon2"
        const val PBKDF2 = "pbkdf2"
        private const val SALT_BYTES = 16
        private const val HASH_BYTES = 32
        private const val PARALLELISM = 1
        private const val MEMORY_KIB = 19_456
        private const val ITERATIONS = 2
        private const val PBKDF2_ITERATIONS = 600_000
    }
}
