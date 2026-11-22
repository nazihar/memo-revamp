package com.memo.memo.internal.rendition

import java.io.OutputStream

/** A piece of text with its style; drawn exactly as given (ADR-0052: no markup anywhere in the path). */
data class Run(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
)

/** What generated pages are made of. Callers build blocks; only a [PageRenderer] draws. */
sealed interface Block {
    data class Title(
        val text: String,
    ) : Block

    data class Heading(
        val text: String,
    ) : Block

    /** Wrapped text; [struck] draws it grey and crossed out (a corrected note); [small] is the secondary size. */
    data class Paragraph(
        val runs: List<Run>,
        val struck: Boolean = false,
        val small: Boolean = false,
        val indent: Int = 0,
    ) : Block {
        constructor(text: String, small: Boolean = false) : this(listOf(Run(text)), small = small)
    }

    /** Label and value rows, the label in its own column. */
    data class KeyValues(
        val rows: List<Pair<String, String>>,
    ) : Block

    /** A framed group kept on one page when it fits (an approval stamp, a note). */
    data class Box(
        val blocks: List<Block>,
    ) : Block

    data object Rule : Block

    data object PageBreak : Block

    /**
     * A table of text cells at the small size (a report's rows, E08 T3): the columns share the width by their weights, the
     * header row is bold and repeated at the top of every page the table runs onto, a row is kept on one page when it fits
     * on one and otherwise continues on the next, and a short row is padded with empty cells.
     */
    data class Table(
        val columns: List<Column>,
        val rows: List<List<String>>,
    ) : Block {
        init {
            require(columns.isNotEmpty() && columns.all { it.weight > 0f }) { "a table has columns of positive weight" }
        }
    }

    /** A table's column: its heading and its share of the width relative to the others. */
    data class Column(
        val name: String,
        val weight: Float = 1f,
    )
}

/**
 * Generated pages: their blocks, the running header (the memo's number and subject) and the footer text. [landscape] turns
 * the A4 page on its side (wide tables); [pagesInWords] numbers the pages "Page n of N" instead of "n / N".
 */
data class PageDocument(
    val header: String,
    val footer: String,
    val blocks: List<Block>,
    val landscape: Boolean = false,
    val pagesInWords: Boolean = false,
)

/** How rendering went: the pages written, and the characters drawn as U+FFFD because the font lacks them. */
data class RenderResult(
    val pages: Int,
    val missingGlyphs: Int,
)

/**
 * Draws generated pages (ADR-0052). The English implementation is PDFBox with Noto Sans; a shaping engine for complex
 * scripts can implement the same port later. Writes a PDF with embedded fonts and nothing else: the PDF/A metadata is
 * added by the assembler.
 */
interface PageRenderer {
    /** The engine and font, for the record's cover and metadata. */
    val name: String

    fun render(
        document: PageDocument,
        out: OutputStream,
    ): RenderResult
}
