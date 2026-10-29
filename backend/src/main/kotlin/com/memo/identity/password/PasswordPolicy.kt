package com.memo.identity.password

import org.springframework.stereotype.Component
import java.text.Normalizer

/** Why a password was rejected; the web client maps each code to a message. */
enum class PasswordViolation { TOO_SHORT, TOO_LONG, BREACHED, CONTAINS_IDENTITY }

/**
 * Password rules (ADR-0023, NIST SP 800-63B rev. 4): length only (no composition rules), Unicode NFC, not a known
 * breached password, not the user's email or name. [minLength] comes from the tenant setting and is never below 8.
 */
@Component
class PasswordPolicy(
    private val breached: BreachedPasswords,
) {
    fun violations(
        password: String,
        email: String,
        displayName: String,
        minLength: Int = DEFAULT_MIN,
    ): List<PasswordViolation> {
        val normalised = normalise(password)
        val length = normalised.codePointCount(0, normalised.length)
        val lower = normalised.lowercase()
        val identity =
            listOf(email.lowercase(), email.substringBefore('@').lowercase(), displayName.lowercase()).filter {
                it.length >=
                    MIN_IDENTITY
            }
        return buildList {
            if (length < maxOf(ABSOLUTE_MIN, minLength)) add(PasswordViolation.TOO_SHORT)
            if (length > MAX) add(PasswordViolation.TOO_LONG)
            if (identity.any { lower == it || lower.contains(it) }) add(PasswordViolation.CONTAINS_IDENTITY)
            if (breached.isBreached(normalised)) add(PasswordViolation.BREACHED)
        }
    }

    companion object {
        const val DEFAULT_MIN = 12
        const val ABSOLUTE_MIN = 8
        const val MAX = 128
        private const val MIN_IDENTITY = 4

        /** Every password is NFC-normalised before policy checks and hashing, so equivalent input always matches. */
        fun normalise(password: String): String = Normalizer.normalize(password, Normalizer.Form.NFC)
    }
}
