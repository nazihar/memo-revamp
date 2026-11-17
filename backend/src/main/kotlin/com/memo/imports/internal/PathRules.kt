package com.memo.imports.internal

import java.text.Normalizer
import java.util.Locale

/**
 * Relative paths of a batch's originals (design E06 §5.1), as the client sends them after picking a folder: NFC, `/`
 * separators, no leading `/`, no empty, `.` or `..` segment, no `\`, no control or format characters, at most
 * [MAX_LENGTH] characters and [MAX_SEGMENTS] segments. A valid path always names a file inside the picked folder.
 */
object PathRules {
    const val MAX_LENGTH = 1024
    const val MAX_SEGMENTS = 32
    private const val BACKSLASH = '\\'.code

    /**
     * Control characters (C0, DEL, C1), format characters (bidirectional controls, zero-width), line and paragraph
     * separators, lone surrogates and unassigned code points.
     */
    private val FORBIDDEN_TYPES =
        setOf(
            Character.CONTROL,
            Character.FORMAT,
            Character.LINE_SEPARATOR,
            Character.PARAGRAPH_SEPARATOR,
            Character.SURROGATE,
            Character.UNASSIGNED,
        )

    /** The path as stored (NFC), or null when [raw] is not a valid path. Normalising a valid path gives it back unchanged. */
    fun normalise(raw: String): String? {
        val path = Normalizer.normalize(raw, Normalizer.Form.NFC)
        val segments = path.split('/')
        val valid =
            path.isNotEmpty() &&
                path.length <= MAX_LENGTH &&
                segments.size <= MAX_SEGMENTS &&
                segments.none { it.isEmpty() || it == "." || it == ".." } &&
                path.codePoints().noneMatch { it == BACKSLASH || Character.getType(it).toByte() in FORBIDDEN_TYPES }
        return path.takeIf { valid }
    }

    /** For the case-insensitive match of a Files row to an original (design E06 §5.2). */
    fun fold(path: String): String = path.uppercase(Locale.ROOT).lowercase(Locale.ROOT)

    /** Paths among [paths] that differ from another only in case (both are kept and flagged `PATH_CASE_ONLY`). */
    fun caseOnly(paths: Collection<String>): Set<String> =
        paths
            .distinct()
            .groupBy(::fold)
            .values
            .filter { it.size > 1 }
            .flatten()
            .toSet()
}
