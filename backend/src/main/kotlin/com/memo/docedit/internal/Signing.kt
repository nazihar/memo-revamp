package com.memo.docedit.internal

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.util.Base64
import java.util.UUID
import javax.crypto.KDF
import javax.crypto.Mac
import javax.crypto.spec.HKDFParameterSpec
import javax.crypto.spec.SecretKeySpec

/** A token or link that does not verify (bad signature, wrong algorithm, expired, malformed). */
class BadSignatureException(
    reason: String,
) : RuntimeException("signature refused: $reason")

/** Refuses with [BadSignatureException]. */
fun refuse(reason: String): Nothing = throw BadSignatureException(reason)

/**
 * JWT with HS256 only (research R1): sign the editor config, verify what the document server sends. Any other `alg`
 * (including `none`) is refused; `exp`, when present, must be in the future.
 */
class Hs256(
    secret: String,
    private val json: JsonMapper,
    private val clock: Clock,
) {
    private val key = SecretKeySpec(secret.toByteArray(), ALGORITHM)

    fun sign(payload: Map<String, Any?>): String {
        val header = encode(json.writeValueAsBytes(mapOf("alg" to "HS256", "typ" to "JWT")))
        val body = encode(json.writeValueAsBytes(payload))
        return "$header.$body.${encode(mac("$header.$body"))}"
    }

    @Suppress("UNCHECKED_CAST")
    fun verify(token: String): Map<String, Any?> {
        val parts = token.split('.')
        if (parts.size != JWT_PARTS) refuse("malformed")
        val header = runCatching { json.readValue(decode(parts[0]), Map::class.java) as Map<String, Any?> }.getOrNull()
        if (header?.get("alg") != "HS256") refuse("algorithm")
        val expected = mac("${parts[0]}.${parts[1]}")
        if (!MessageDigest.isEqual(expected, runCatching { decode(parts[2]) }.getOrDefault(ByteArray(0)))) {
            refuse("signature")
        }
        val payload =
            runCatching { json.readValue(decode(parts[1]), Map::class.java) as Map<String, Any?> }.getOrNull()
                ?: refuse("payload")
        val exp = (payload["exp"] as? Number)?.toLong()
        if (exp != null && Instant.ofEpochSecond(exp).isBefore(clock.instant())) refuse("expired")
        return payload
    }

    private fun mac(text: String): ByteArray = Mac.getInstance(ALGORITHM).apply { init(key) }.doFinal(text.toByteArray())

    private companion object {
        const val ALGORITHM = "HmacSHA256"
        const val JWT_PARTS = 3

        fun encode(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

        fun decode(text: String): ByteArray = Base64.getUrlDecoder().decode(text)
    }
}

/**
 * Document keys and signed links (research R8.1, R8.4), keyed by a secret derived from the installation's data key
 * (HKDF, its own label), so they cannot be forged or collide across tenants.
 */
@Component
class DocumentKeys(
    @param:Value("\${memo.security.data-key:}") private val dataKey: String,
    private val clock: Clock,
) {
    private val key by lazy {
        val derived =
            KDF.getInstance("HKDF-SHA256").deriveData(
                HKDFParameterSpec
                    .ofExtract()
                    .addIKM(dataKey.toByteArray())
                    .addSalt("memo-docedit".toByteArray())
                    .thenExpand("docedit-link-v1".toByteArray(), KEY_BYTES),
            )
        SecretKeySpec(derived, ALGORITHM)
    }

    /** The editor's document key for one file version: a new version gets a new key (R8.1). */
    fun documentKey(
        tenant: UUID,
        file: UUID,
        version: UUID,
    ): String = "m" + base32(mac("key|$tenant|$file|$version")).take(KEY_LENGTH)

    /** Signature of a link to one session's document or callback, valid until [expires] (null: the callback). */
    fun linkSignature(
        tenant: UUID,
        session: UUID,
        purpose: String,
        expires: Long?,
    ): String = Base64.getUrlEncoder().withoutPadding().encodeToString(mac("link|$purpose|$tenant|$session|${expires ?: "-"}"))

    fun verifyLink(
        tenant: UUID,
        session: UUID,
        purpose: String,
        expires: Long?,
        signature: String,
    ) {
        if (expires != null && Instant.ofEpochSecond(expires).isBefore(clock.instant())) refuse("expired")
        val expected = linkSignature(tenant, session, purpose, expires).toByteArray()
        if (!MessageDigest.isEqual(expected, signature.toByteArray())) refuse("link")
    }

    private fun mac(text: String): ByteArray = Mac.getInstance(ALGORITHM).apply { init(key) }.doFinal(text.toByteArray())

    private companion object {
        const val ALGORITHM = "HmacSHA256"
        const val KEY_BYTES = 32
        const val KEY_LENGTH = 40
        const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

        // RFC 4648 base32: 8-bit bytes regrouped into 5-bit symbols.
        @Suppress("MagicNumber")
        fun base32(bytes: ByteArray): String {
            val out = StringBuilder()
            var buffer = 0
            var bits = 0
            for (b in bytes) {
                buffer = (buffer shl 8) or (b.toInt() and 0xff)
                bits += 8
                while (bits >= 5) {
                    out.append(ALPHABET[(buffer shr (bits - 5)) and 0x1f])
                    bits -= 5
                }
            }
            if (bits > 0) out.append(ALPHABET[(buffer shl (5 - bits)) and 0x1f])
            return out.toString()
        }
    }
}
