package com.memo.perf

import java.nio.ByteBuffer
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** RFC 6238 codes (SHA-1, 30-second steps, 6 digits) for load users enrolled with a known Base32 secret. */
object Totp {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
    private const val STEP_SECONDS = 30L
    private const val DIGITS_MOD = 1_000_000
    private const val BITS_PER_CHAR = 5
    private const val BYTE_BITS = 8
    private const val BYTE_MASK = 0xff
    private const val OFFSET_MASK = 0x0f
    private const val SIGN_MASK = 0x7fffffff

    fun code(
        base32Secret: String,
        epochSeconds: Long,
    ): String {
        val mac = Mac.getInstance("HmacSHA1").apply { init(SecretKeySpec(decode(base32Secret), "HmacSHA1")) }
        val hash = mac.doFinal(ByteBuffer.allocate(Long.SIZE_BYTES).putLong(epochSeconds / STEP_SECONDS).array())
        val offset = hash.last().toInt() and OFFSET_MASK
        val value = (ByteBuffer.wrap(hash, offset, Int.SIZE_BYTES).int and SIGN_MASK) % DIGITS_MOD
        return value.toString().padStart(DIGITS_MOD.toString().length - 1, '0')
    }

    private fun decode(base32: String): ByteArray {
        var buffer = 0
        var bits = 0
        val out = mutableListOf<Byte>()
        for (char in base32.trimEnd('=').uppercase()) {
            buffer = (buffer shl BITS_PER_CHAR) or ALPHABET.indexOf(char).also { require(it >= 0) { "not Base32" } }
            bits += BITS_PER_CHAR
            if (bits >= BYTE_BITS) {
                bits -= BYTE_BITS
                out.add(((buffer shr bits) and BYTE_MASK).toByte())
            }
        }
        return out.toByteArray()
    }
}
