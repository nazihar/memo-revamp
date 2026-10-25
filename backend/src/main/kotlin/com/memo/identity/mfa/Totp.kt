package com.memo.identity.mfa

import org.bouncycastle.util.encoders.Base32
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * RFC 6238 TOTP (ADR-0024): HMAC-SHA1, 6 digits, 30-second steps, ±1 step tolerance. [verify] returns the matched time
 * step so the caller can refuse it next time (replay protection).
 */
object Totp {
    const val DIGITS = 6
    const val STEP_SECONDS = 30L
    const val SECRET_BYTES = 20
    private const val WINDOW = 1
    private const val MOD = 1_000_000
    private const val LOW_NIBBLE = 0x0f
    private const val NO_SIGN = 0x7fffffff
    private val random = SecureRandom()

    fun newSecret(): ByteArray = ByteArray(SECRET_BYTES).also { random.nextBytes(it) }

    fun step(at: Instant): Long = Math.floorDiv(at.epochSecond, STEP_SECONDS)

    fun code(
        secret: ByteArray,
        step: Long,
    ): String {
        val mac = Mac.getInstance("HmacSHA1").apply { init(SecretKeySpec(secret, "HmacSHA1")) }
        val hash = mac.doFinal(ByteBuffer.allocate(Long.SIZE_BYTES).putLong(step).array())
        val offset = hash.last().toInt() and LOW_NIBBLE
        val binary = ByteBuffer.wrap(hash, offset, Int.SIZE_BYTES).int and NO_SIGN
        return (binary % MOD).toString().padStart(DIGITS, '0')
    }

    /** The step whose code equals [code] within ±1 step of [at] and later than [lastStep], or null. */
    fun verify(
        secret: ByteArray,
        code: String,
        at: Instant,
        lastStep: Long,
    ): Long? {
        if (code.length != DIGITS || !code.all { it.isDigit() }) return null
        val now = step(at)
        // Every candidate is computed and compared in constant time, so timing does not reveal which step matched.
        return (now - WINDOW..now + WINDOW)
            .map { it to MessageDigest.isEqual(code(secret, it).toByteArray(), code.toByteArray()) }
            .filter { (candidate, matches) -> matches && candidate > lastStep }
            .maxOfOrNull { it.first }
    }

    fun base32(secret: ByteArray): String = Base32.toBase32String(secret).trimEnd('=')

    /** Accepts unpadded input (as authenticator apps show it): pads to a whole 8-character Base32 block. */
    fun decodeBase32(value: String): ByteArray {
        val padded = (value.length + BASE32_BLOCK - 1) / BASE32_BLOCK * BASE32_BLOCK
        return Base32.decode(value.uppercase().padEnd(padded, '='))
    }

    private const val BASE32_BLOCK = 8

    /** The `otpauth://` URI authenticator apps scan (Key Uri Format). */
    fun uri(
        secret: ByteArray,
        issuer: String,
        account: String,
    ): String {
        fun enc(value: String) = URLEncoder.encode(value, Charsets.UTF_8).replace("+", "%20")
        return "otpauth://totp/${enc(issuer)}:${enc(account)}?secret=${base32(secret)}&issuer=${enc(issuer)}" +
            "&algorithm=SHA1&digits=$DIGITS&period=$STEP_SECONDS"
    }
}
