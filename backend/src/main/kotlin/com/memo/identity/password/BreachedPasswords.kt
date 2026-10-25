package com.memo.identity.password

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat

/**
 * Offline breached-password check (ADR-0023, NIST SP 800-63B):
 * - a bundled Bloom filter of the one million most common breached passwords (SecLists `Pwdb_top-1000000`, MIT);
 * - optionally, the full Have I Been Pwned corpus as range files (`<5 hex>.txt`, lines `SUFFIX:COUNT`) in
 *   [rangeDir], mounted by the customer. No network calls.
 */
@Component
class BreachedPasswords(
    @param:Value("\${memo.security.breached-passwords-dir:}") private val rangeDir: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    init {
        if (rangeDir.isNotBlank()) log.info("Also checking the Have I Been Pwned range files in {}", rangeDir)
    }

    fun isBreached(password: String): Boolean = BUNDLED.mightContain(password) || inRangeFiles(password)

    private fun inRangeFiles(password: String): Boolean {
        val file = if (rangeDir.isBlank()) null else Path.of(rangeDir, "${sha1(password).take(PREFIX)}.txt")
        val suffix = sha1(password).drop(PREFIX)
        return file != null && Files.isRegularFile(file) && Files.readAllLines(file).any { it.startsWith("$suffix:") }
    }

    private companion object {
        const val RESOURCE = "/identity/breached-passwords.bloom"
        const val PREFIX = 5

        /** Loaded once per JVM (about 1.7 MB); a missing or corrupt file fails startup. */
        val BUNDLED: BloomFilter =
            requireNotNull(BreachedPasswords::class.java.getResource(RESOURCE)) { "missing $RESOURCE" }
                .readBytes()
                .let { BloomFilter.read(ByteArrayInputStream(it)) }

        // SHA-1 is the HIBP corpus format: it looks a password up, it does not protect one.
        // nosemgrep: kotlin.lang.security.use-of-sha1.use-of-sha1
        fun sha1(password: String): String =
            HexFormat.of().withUpperCase().formatHex(MessageDigest.getInstance("SHA-1").digest(password.toByteArray(Charsets.UTF_8)))
    }
}
