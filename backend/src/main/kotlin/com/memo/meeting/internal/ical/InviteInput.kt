package com.memo.meeting.internal.ical

import java.net.URI
import java.net.URISyntaxException

/**
 * Input hygiene for invitation files (design E05 §1.2.1, threat T3). Text values lose control characters (a one-line
 * value also its line breaks and tabs) and unpaired surrogates before ical4j escapes them, so CR or LF never reach a
 * property line; parameter values (CN) also lose double quotes and carets, which RFC 6868 would otherwise encode.
 * Addresses and links must be well formed or the invitation is refused.
 */
internal object InviteInput {
    private const val MAX_EMAIL = 320
    private const val MAX_URL = 2000
    private val WHITESPACE = Regex("\\s+")
    private val NO_SPACE_OR_CONTROL = Regex("^[^\\s\\p{Cc}]+$")

    /** One line: every control or line break becomes a space, runs of spaces one space, trimmed. */
    fun line(text: String): String = clean(text) { if (isBreak(it) || it == '\t'.code) " " else null }.replace(WHITESPACE, " ").trim()

    /** A block of lines: CRLF, CR and the Unicode separators become LF; tabs stay; other controls go. */
    fun block(text: String): String =
        clean(text.replace("\r\n", "\n")) {
            if (isBreak(it)) {
                "\n"
            } else if (it == '\t'.code) {
                "\t"
            } else {
                null
            }
        }.lines()
            .joinToString("\n") { it.trimEnd() }
            .trim()

    /**
     * A parameter value such as CN: one line, without the characters RFC 6868 escapes, and without backslashes, which
     * parsers (ical4j among them, the E05 T1 property tests found) read as escaping the closing quote of a quoted value.
     */
    fun name(text: String): String = line(text).replace('"', '\'').replace("^", "").replace("\\", "")

    fun mailto(email: String): URI {
        val trimmed = email.trim()
        val oneAt = trimmed.indexOf('@').let { it > 0 && it == trimmed.lastIndexOf('@') && it < trimmed.length - 1 }
        refuseUnless(oneAt && trimmed.length <= MAX_EMAIL && NO_SPACE_OR_CONTROL.matches(trimmed)) { "not an email address" }
        return try {
            URI("mailto", trimmed, null)
        } catch (_: URISyntaxException) {
            throw InviteRefusedException("not an email address")
        }
    }

    /** An absolute http(s) link with a host. */
    fun link(url: String): URI {
        val trimmed = url.trim()
        refuseUnless(trimmed.length <= MAX_URL && NO_SPACE_OR_CONTROL.matches(trimmed)) { "not a link" }
        val uri =
            try {
                URI(trimmed)
            } catch (_: URISyntaxException) {
                throw InviteRefusedException("not a link")
            }
        refuseUnless(uri.scheme?.lowercase() in setOf("https", "http") && !uri.host.isNullOrEmpty()) { "not an http(s) link" }
        return uri
    }

    private fun refuseUnless(
        condition: Boolean,
        message: () -> String,
    ) {
        if (!condition) throw InviteRefusedException(message())
    }

    private fun isBreak(codePoint: Int) = codePoint in BREAKS

    /** C0, DEL, C1 and the line and paragraph separators. */
    private fun isControl(codePoint: Int) = codePoint < SPACE || codePoint == DEL || codePoint in C1 || codePoint in BREAKS

    /** Keeps letters and marks; a control or break is replaced by [replace]'s answer, or dropped when it answers null. */
    private fun clean(
        text: String,
        replace: (Int) -> String?,
    ): String {
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            val pairedHigh = Character.isHighSurrogate(ch) && i + 1 < text.length && Character.isLowSurrogate(text[i + 1])
            when {
                pairedHigh -> out.append(ch).append(text[i + 1]).also { i++ }
                Character.isSurrogate(ch) -> Unit
                isControl(ch.code) -> replace(ch.code)?.let(out::append)
                else -> out.append(ch)
            }
            i++
        }
        return out.toString()
    }

    private const val LF = 0x0A
    private const val CR = 0x0D
    private const val NEL = 0x85
    private const val LS = 0x2028
    private const val PS = 0x2029
    private val BREAKS = setOf(LF, CR, NEL, LS, PS)
    private const val SPACE = 0x20
    private const val DEL = 0x7F
    private val C1 = 0x80..0x9F
}
