package com.memo.identity.password

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat

class PasswordPolicyTest {
    private val policy = PasswordPolicy(BreachedPasswords(""))

    private fun violations(
        password: String,
        minLength: Int = PasswordPolicy.DEFAULT_MIN,
    ) = policy.violations(password, "nadia.karim@bank.example", "Nadia Karim", minLength)

    @Test
    fun `a long passphrase without composition rules is accepted`() {
        assertThat(violations("violet harbour lantern sixteen")).isEmpty()
        assertThat(violations("alllowercaseletters")).isEmpty()
    }

    @Test
    fun `length is counted in code points after NFC, between the tenant minimum and 128`() {
        assertThat(violations("short-pass1")).containsExactly(PasswordViolation.TOO_SHORT)
        // 12 code points, 13 UTF-16 units: the emoji counts once.
        assertThat(violations("violet🐟harbo")).isEmpty()
        // Decomposed é (e + combining acute) normalises to one code point: 11 after NFC.
        assertThat(violations("violetharbe\u0301")).containsExactly(PasswordViolation.TOO_SHORT)
        assertThat(violations("x".repeat(129))).containsExactly(PasswordViolation.TOO_LONG)
    }

    @Test
    fun `a tenant minimum below 8 is raised to 8`() {
        assertThat(violations("ql8vant", minLength = 4)).containsExactly(PasswordViolation.TOO_SHORT)
        assertThat(violations("ql8vantz", minLength = 4)).isEmpty()
    }

    @Test
    fun `common breached passwords are rejected regardless of case`() {
        assertThat(violations("qwerty123456")).containsExactly(PasswordViolation.BREACHED)
        assertThat(violations("QWERTY123456")).containsExactly(PasswordViolation.BREACHED)
        assertThat(violations("1qaz2wsx3edc")).containsExactly(PasswordViolation.BREACHED)
    }

    @Test
    fun `passwords containing the email or name are rejected`() {
        assertThat(violations("nadia.karim-violet")).contains(PasswordViolation.CONTAINS_IDENTITY)
        assertThat(violations("violet NADIA KARIM")).contains(PasswordViolation.CONTAINS_IDENTITY)
    }

    @Test
    fun `the optional range files reject a password the bundled filter does not know`(
        @TempDir dir: Path,
    ) {
        val password = "violet harbour lantern sixteen"
        val sha1 = HexFormat.of().withUpperCase().formatHex(MessageDigest.getInstance("SHA-1").digest(password.toByteArray()))
        Files.writeString(dir.resolve("${sha1.take(5)}.txt"), "0000000000000000000000000000000000A:3\n${sha1.drop(5)}:42\n")

        val withRanges = PasswordPolicy(BreachedPasswords(dir.toString()))
        assertThat(withRanges.violations(password, "a@b.example", "A B")).containsExactly(PasswordViolation.BREACHED)
        assertThat(withRanges.violations("another unknown passphrase", "a@b.example", "A B")).isEmpty()
    }
}
