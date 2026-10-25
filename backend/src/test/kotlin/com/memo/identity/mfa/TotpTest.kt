package com.memo.identity.mfa

import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.constraints.IntRange
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.time.Instant

class TotpTest {
    /** RFC 6238 appendix B (SHA-1), last six digits of the published eight-digit codes. */
    private val rfcSecret = "12345678901234567890".toByteArray(Charsets.US_ASCII)

    @ParameterizedTest
    @CsvSource("59, 287082", "1111111109, 081804", "1111111111, 050471", "1234567890, 005924", "2000000000, 279037", "20000000000, 353130")
    fun `matches the RFC 6238 test vectors`(
        epochSeconds: Long,
        expected: String,
    ) {
        assertThat(Totp.code(rfcSecret, Totp.step(Instant.ofEpochSecond(epochSeconds)))).isEqualTo(expected)
    }

    @Test
    fun `accepts one step of drift either way, and nothing beyond`() {
        val now = Instant.ofEpochSecond(1_700_000_000)
        val step = Totp.step(now)

        assertThat((-1L..1L).map { Totp.verify(rfcSecret, Totp.code(rfcSecret, step + it), now, 0) }).containsExactly(
            step - 1,
            step,
            step + 1,
        )
        assertThat(Totp.verify(rfcSecret, Totp.code(rfcSecret, step - 2), now, 0)).isNull()
        assertThat(Totp.verify(rfcSecret, Totp.code(rfcSecret, step + 2), now, 0)).isNull()
    }

    @Test
    fun `a used step, or an earlier one, is refused`() {
        val now = Instant.ofEpochSecond(1_700_000_000)
        val code = Totp.code(rfcSecret, Totp.step(now))

        assertThat(Totp.verify(rfcSecret, code, now, lastStep = Totp.step(now))).isNull()
        assertThat(Totp.verify(rfcSecret, Totp.code(rfcSecret, Totp.step(now) - 1), now, lastStep = Totp.step(now))).isNull()
    }

    @Test
    fun `malformed codes are refused`() {
        val now = Instant.ofEpochSecond(1_700_000_000)
        assertThat(
            listOf("", "12345", "1234567", "12a456", " 123456"),
        ).allSatisfy { assertThat(Totp.verify(rfcSecret, it, now, 0)).isNull() }
    }

    @Test
    fun `the secret round-trips through Base32 and lands in the otpauth URI`() {
        val secret = Totp.newSecret()
        val uri = Totp.uri(secret, "Bank of Example", "nadia@bank.example")

        assertThat(Totp.decodeBase32(Totp.base32(secret))).isEqualTo(secret)
        assertThat(uri)
            .startsWith("otpauth://totp/Bank%20of%20Example:nadia%40bank.example?secret=${Totp.base32(secret)}")
            .contains("issuer=Bank%20of%20Example", "digits=6", "period=30")
    }

    @Property
    fun recoveryCodesAreUniqueReadableAndNormalisable(
        @ForAll @IntRange(min = 1, max = 3) rounds: Int,
    ) {
        repeat(rounds) {
            val codes = RecoveryCodes.generate()
            assertThat(codes).hasSize(RecoveryCodes.COUNT).doesNotHaveDuplicates()
            assertThat(codes).allSatisfy { code ->
                assertThat(code).hasSize(RecoveryCodes.LENGTH)
                assertThat(code.all { it in RecoveryCodes.ALPHABET }).isTrue()
                val shown = RecoveryCodes.display(code)
                assertThat(shown).matches("[A-Z2-9]{5}-[A-Z2-9]{5}")
                assertThat(RecoveryCodes.normalise(" ${shown.lowercase()} ")).isEqualTo(code)
                assertThat(RecoveryCodes.looksLikeRecoveryCode(shown)).isTrue()
            }
        }
    }

    @Test
    fun `the QR code is a plain SVG without scripts or links`() {
        val svg = QrSvg.of(Totp.uri(Totp.newSecret(), "memo", "a@b.example"))

        assertThat(svg).startsWith("<svg xmlns=\"http://www.w3.org/2000/svg\"").endsWith("</svg>")
        assertThat(svg).doesNotContain("<script", "href", " on")
    }
}
