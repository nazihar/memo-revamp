package com.memo.identity.mfa

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.security.SecureRandom

/**
 * One-time recovery codes (ADR-0024): 10 codes of 10 characters from an alphabet without look-alikes (no 0/O, 1/I/L),
 * shown as `XXXXX-XXXXX`; 50 bits each, stored only as Argon2id hashes.
 */
object RecoveryCodes {
    const val COUNT = 10
    const val LENGTH = 10
    const val ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
    private const val GROUP = 5
    private val random = SecureRandom()

    fun generate(): List<String> = List(COUNT) { String(CharArray(LENGTH) { ALPHABET[random.nextInt(ALPHABET.length)] }) }

    fun display(code: String): String = code.chunked(GROUP).joinToString("-")

    /** What the user typed, without separators and case: the form that is hashed and compared. */
    fun normalise(input: String): String = input.uppercase().filter { it.isLetterOrDigit() }

    fun looksLikeRecoveryCode(input: String): Boolean = normalise(input).let { it.length == LENGTH && it.all { c -> c in ALPHABET } }
}

/** The enrolment QR code as a self-contained SVG (one path, no scripts, no external references). */
object QrSvg {
    private const val QUIET_ZONE = 4

    fun of(text: String): String {
        val matrix =
            QRCodeWriter().encode(
                text,
                BarcodeFormat.QR_CODE,
                0,
                0,
                mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 0),
            )
        val size = matrix.width + 2 * QUIET_ZONE
        val path =
            buildString {
                for (y in 0 until matrix.height) {
                    for (x in 0 until matrix.width) {
                        if (matrix[x, y]) append("M${x + QUIET_ZONE} ${y + QUIET_ZONE}h1v1h-1z")
                    }
                }
            }
        return """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 $size $size" shape-rendering="crispEdges">""" +
            """<rect width="$size" height="$size" fill="#fff"/><path d="$path" fill="#000"/></svg>"""
    }
}
