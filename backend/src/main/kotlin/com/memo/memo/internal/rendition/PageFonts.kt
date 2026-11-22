package com.memo.memo.internal.rendition

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.font.PDType0Font

/**
 * The four Noto Sans faces of one document, embedded as subsets (ADR-0052), and the text clean-up every string goes
 * through before it is measured or drawn: control characters removed, characters the font lacks replaced by U+FFFD and
 * counted. The faces share one character set, so one check serves all four. [regularOnly] embeds the regular face alone
 * (and draws every run in it), for a single line such as a record's stamp.
 */
class PageFonts(
    document: PDDocument,
    regularOnly: Boolean = false,
) {
    val regular: PDType0Font = load(document, "Regular")
    val bold: PDType0Font = if (regularOnly) regular else load(document, "Bold")
    val italic: PDType0Font = if (regularOnly) regular else load(document, "Italic")
    val boldItalic: PDType0Font = if (regularOnly) regular else load(document, "BoldItalic")

    private val known = HashMap<Int, Boolean>()

    /** Characters drawn as U+FFFD so far. */
    var missing: Int = 0
        private set

    fun of(run: Run): PDType0Font =
        when {
            run.bold && run.italic -> boldItalic
            run.bold -> bold
            run.italic -> italic
            else -> regular
        }

    /** [text] ready to draw: tabs as spaces, other control characters dropped, unknown characters as U+FFFD. */
    fun clean(text: String): String {
        val out = StringBuilder(text.length)
        text.codePoints().forEach { cp ->
            when {
                cp == '\t'.code -> {
                    out.append(' ')
                }

                cp == '\n'.code -> {
                    out.append('\n')
                }

                Character.isISOControl(cp) || Character.getType(cp) == Character.FORMAT.toInt() -> {
                    // dropped
                }

                has(cp) -> {
                    out.appendCodePoint(cp)
                }

                else -> {
                    missing++
                    out.append(if (has(REPLACEMENT.code)) REPLACEMENT else '?')
                }
            }
        }
        return out.toString()
    }

    private fun has(cp: Int): Boolean =
        known.getOrPut(cp) {
            runCatching { regular.encode(String(Character.toChars(cp))) }.isSuccess
        }

    private companion object {
        const val REPLACEMENT = '�'

        fun load(
            document: PDDocument,
            face: String,
        ): PDType0Font {
            val stream =
                checkNotNull(PageFonts::class.java.getResourceAsStream("/fonts/noto-sans/NotoSans-$face.ttf")) {
                    "font NotoSans-$face missing"
                }
            return stream.use { PDType0Font.load(document, it, true) }
        }
    }
}
