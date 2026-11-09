package com.memo.files.internal

import java.text.Normalizer

/**
 * The original file name as metadata only (ADR-0029): it never reaches object keys, paths or logs. NFC-normalised,
 * without path parts, control or format characters, at most [MAX_LENGTH] characters, never empty.
 */
internal object FileNames {
    const val MAX_LENGTH = 255
    private val UNSAFE = Regex("[\\p{Cc}\\p{Cf}\\p{Zl}\\p{Zp}]")
    private val SPACES = Regex("\\s+")

    fun sanitise(
        declared: String?,
        fallbackExtension: String,
    ): String {
        val base =
            Normalizer
                .normalize(declared.orEmpty(), Normalizer.Form.NFC)
                .substringAfterLast('/')
                .substringAfterLast('\\')
                .replace(UNSAFE, "")
                .replace(SPACES, " ")
                // Together: ". ." trimmed in two steps would leave a blank name (found by fuzzing).
                .trim { it.isWhitespace() || it == '.' }
        val name = base.ifBlank { "file.$fallbackExtension" }
        return if (name.length <= MAX_LENGTH) name else shorten(name)
    }

    /** Keeps the extension when cutting a long name. Cuts on code point boundaries. */
    private fun shorten(name: String): String {
        val extension =
            extension(name)
                ?.let { ".$it" }
                .orEmpty()
                .takeIf { it.length < MAX_LENGTH / 2 }
                .orEmpty()
        val stem = name.removeSuffix(extension)
        val keep = MAX_LENGTH - extension.length
        val end = stem.offsetByCodePoints(0, minOf(keep, stem.codePointCount(0, stem.length)))
        var cut = stem.substring(0, end)
        while (cut.length > keep) cut = cut.substring(0, cut.offsetByCodePoints(0, cut.codePointCount(0, cut.length) - 1))
        return cut + extension
    }

    /** The lower-case extension after the last dot, or null. */
    fun extension(name: String): String? =
        name
            .substringAfterLast('.', "")
            .lowercase()
            .takeIf { it.isNotEmpty() && it.length <= MAX_EXTENSION && '.' !in it }

    private const val MAX_EXTENSION = 10
}
