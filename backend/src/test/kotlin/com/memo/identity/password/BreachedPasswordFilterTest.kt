package com.memo.identity.password

import com.memo.testsupport.repoRoot
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledIfSystemProperty
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import java.util.UUID

/**
 * The bundled common-password Bloom filter (ADR-0023).
 *  - normal run: the committed filter loads, holds well-known passwords and has a false-positive rate near its target;
 *  - `./gradlew :backend:updateBreachedPasswords -Pbreached.source=<path to Pwdb_top-1000000.txt>`: rebuilds it from
 *    SecLists `Passwords/Common-Credentials/Pwdb_top-1000000.txt` at commit [SOURCE_COMMIT] (MIT), checked against
 *    [SOURCE_SHA256] so the filter is reproducible.
 */
class BreachedPasswordFilterTest {
    private val committed: Path = repoRoot.resolve("backend/src/main/resources/identity/breached-passwords.bloom")

    @Test
    @DisabledIfSystemProperty(named = "breached.update", matches = "true")
    fun `the committed filter holds common passwords and few false positives`() {
        val filter = Files.newInputStream(committed).use { BloomFilter.read(it) }

        assertThat(listOf("123456", "password", "qwerty", "iloveyou", "Password", "PASSWORD")).allMatch { filter.mightContain(it) }
        val falsePositives = (1..SAMPLES).count { filter.mightContain("memo-${UUID.randomUUID()}") }
        assertThat(falsePositives.toDouble() / SAMPLES).isLessThan(MAX_OBSERVED_FPP)
    }

    @Test
    @EnabledIfSystemProperty(named = "breached.update", matches = "true")
    fun `rebuild the filter from the pinned source list`() {
        val source = Path.of(requireNotNull(System.getProperty("breached.source")) { "pass -Pbreached.source=<path>" })
        val sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(source)))
        check(sha == SOURCE_SHA256) { "unexpected source list $sha; update SOURCE_COMMIT and SOURCE_SHA256 deliberately" }

        val passwords =
            Files
                .readAllLines(source)
                .map { BloomFilter.normalise(it) }
                .filter { it.isNotEmpty() }
                .toSet()
        val filter = BloomFilter.create(passwords.size.toLong(), TARGET_FPP)
        passwords.forEach { filter.add(it) }
        Files.newOutputStream(committed).use { filter.write(it) }
    }

    private companion object {
        const val SOURCE_COMMIT = "12274c98fdebe98c7a7284914436a472ed469aed"
        const val SOURCE_SHA256 = "e9a88f67aafe65496682dc374559ee714e978bee50314767494c3e37a18c9fc8"
        const val TARGET_FPP = 0.001
        const val MAX_OBSERVED_FPP = 0.005
        const val SAMPLES = 20_000
    }
}
