package com.memo.meeting.internal.ical

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * Line folding and the format checks of RFC 5545 §3.1: content lines end with CRLF and are folded so that no line is
 * longer than 75 octets, a fold never splitting a character's UTF-8 bytes. ical4j's own folding counts characters, not
 * octets, so an Arabic title would give lines of 140 octets; the writer takes ical4j's unfolded text and folds it here.
 */
internal object IcsFolding {
    const val MAX_OCTETS = 75
    private const val CRLF = "\r\n"
    private val TZID = Regex(";TZID=\"?([^\":;]+)\"?[:;]")
    private val VTIMEZONE_ID = Regex("^TZID:(.+)$")

    /** [unfolded] content lines (CRLF or LF ends) as a folded file ending in CRLF. */
    fun fold(unfolded: String): String =
        unfolded
            .replace(CRLF, "\n")
            .trimEnd('\n')
            .split('\n')
            .joinToString(CRLF, postfix = CRLF) { fold1(it) }

    private fun fold1(line: String): String {
        if (line.toByteArray(Charsets.UTF_8).size <= MAX_OCTETS) return line
        val out = StringBuilder(line.length + line.length / MAX_OCTETS * CRLF.length)
        var budget = MAX_OCTETS
        var used = 0
        var i = 0
        while (i < line.length) {
            val codePoint = line.codePointAt(i)
            val chars = Character.charCount(codePoint)
            val octets = String(Character.toChars(codePoint)).toByteArray(Charsets.UTF_8).size
            if (used + octets > budget) {
                out.append(CRLF).append(' ')
                budget = MAX_OCTETS - 1
                used = 0
            }
            out.append(line, i, i + chars)
            used += octets
            i += chars
        }
        return out.toString()
    }

    /** What is wrong with a written file's format; empty when nothing is. Messages hold no content. */
    fun problems(file: String): List<String> {
        val problems = mutableListOf<String>()
        if (!file.endsWith(CRLF)) problems += "the file does not end with CRLF"
        val lines = file.removeSuffix(CRLF).split(CRLF)
        if (lines.any { '\r' in it || '\n' in it }) problems += "a bare CR or LF"
        lines.forEachIndexed { n, line ->
            if (line.toByteArray(Charsets.UTF_8).size > MAX_OCTETS) problems += "line ${n + 1} is longer than $MAX_OCTETS octets"
        }
        if (!validUtf8(file.toByteArray(Charsets.UTF_8)) || hasLoneSurrogate(file)) problems += "not valid UTF-8"
        val unfolded = file.replace("$CRLF ", "").split(CRLF)
        val declared = unfolded.mapNotNull { VTIMEZONE_ID.find(it)?.groupValues?.get(1) }.toSet()
        // Only the name and parameters of a line can carry TZID; a value's text (escaped) never counts.
        unfolded.flatMap { line -> TZID.findAll(head(line) + ":").map { it.groupValues[1] }.toList() }.toSet().forEach {
            if (it !in declared) problems += "no VTIMEZONE for a TZID"
        }
        return problems
    }

    /** A line's name and parameters: everything before the first colon outside double quotes. */
    private fun head(line: String): String {
        var quoted = false
        line.forEachIndexed { i, c ->
            if (c == '"') quoted = !quoted
            if (c == ':' && !quoted) return line.substring(0, i)
        }
        return line
    }

    private fun hasLoneSurrogate(text: String): Boolean =
        text.indices.any { i ->
            val c = text[i]
            (Character.isHighSurrogate(c) && (i + 1 >= text.length || !Character.isLowSurrogate(text[i + 1]))) ||
                (Character.isLowSurrogate(c) && (i == 0 || !Character.isHighSurrogate(text[i - 1])))
        }

    fun validUtf8(bytes: ByteArray): Boolean =
        try {
            Charsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
            true
        } catch (_: CharacterCodingException) {
            false
        }
}
