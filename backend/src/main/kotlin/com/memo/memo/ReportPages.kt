package com.memo.memo

import java.io.OutputStream

/** One section of a report: a heading, then paragraphs, then label and value lines. */
data class ReportSection(
    val heading: String,
    val paragraphs: List<String> = emptyList(),
    val lines: List<Pair<String, String>> = emptyList(),
)

/**
 * A plain report to draw as PDF pages: a [title], a running [header] and a [footer] on every page, and [sections]. Text is
 * drawn exactly as given (no markup); characters the font lacks are drawn as a replacement and counted.
 */
data class ReportDocument(
    val title: String,
    val header: String,
    val footer: String,
    val sections: List<ReportSection>,
)

/** How drawing went: the pages written, and the characters the font lacks. */
data class ReportRendered(
    val pages: Int,
    val missingGlyphs: Int,
)

/**
 * Draws simple reports through ADR-0052's renderer (PDFBox with Noto Sans, English only), for modules that need a PDF
 * summary of their own, such as the legacy import's reconciliation report (design E06 §8). The renderer itself stays
 * internal to the memo module. The pages do not depend on the clock (the PDF file identifier PDFBox writes does).
 */
interface ReportPages {
    fun render(
        document: ReportDocument,
        out: OutputStream,
    ): ReportRendered
}
