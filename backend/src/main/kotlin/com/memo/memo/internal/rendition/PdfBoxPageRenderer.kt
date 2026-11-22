package com.memo.memo.internal.rendition

import org.apache.pdfbox.io.IOUtils
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType0Font
import org.springframework.stereotype.Component
import java.io.OutputStream

/** One drawing step on a page: text, a line or a frame, at positions fixed by the layout. */
internal typealias Draw = (Canvas) -> Unit

/** The content stream of one page, with the few operations generated pages need (RGB only, for PDF/A's sRGB intent). */
internal class Canvas(
    private val stream: PDPageContentStream,
) {
    fun text(
        x: Float,
        baseline: Float,
        font: PDType0Font,
        size: Float,
        text: String,
        grey: Boolean = false,
    ) {
        if (text.isEmpty()) return
        val shade = if (grey) GREY else BLACK
        stream.setNonStrokingColor(shade, shade, shade)
        stream.beginText()
        stream.setFont(font, size)
        stream.newLineAtOffset(x, baseline)
        stream.showText(text)
        stream.endText()
    }

    fun line(
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        grey: Boolean = true,
    ) {
        val shade = if (grey) RULE else BLACK
        stream.setStrokingColor(shade, shade, shade)
        stream.setLineWidth(LINE_WIDTH)
        stream.moveTo(x1, y1)
        stream.lineTo(x2, y2)
        stream.stroke()
    }

    fun frame(
        x: Float,
        bottom: Float,
        width: Float,
        height: Float,
    ) {
        stream.setStrokingColor(RULE, RULE, RULE)
        stream.setLineWidth(LINE_WIDTH)
        stream.addRect(x, bottom, width, height)
        stream.stroke()
    }

    private companion object {
        const val BLACK = 0f
        const val GREY = 0.42f
        const val RULE = 0.7f
        const val LINE_WIDTH = 0.6f
    }
}

/**
 * Generated pages on PDFBox with Noto Sans (ADR-0052): A4 portrait (or landscape for a report's wide tables), 20 mm
 * margins, a running header with the memo's number and subject, and a footer with the record's name and "n / N" (or
 * "Page n of N"). Deterministic: nothing depends on the clock
 * or the platform. Large documents are built in temp files, not in memory.
 */
@Component
class PdfBoxPageRenderer : PageRenderer {
    override val name: String = "PDFBox 3.0.8 with Noto Sans 2.015"

    override fun render(
        document: PageDocument,
        out: OutputStream,
    ): RenderResult {
        PDDocument(IOUtils.createTempFileOnlyStreamCache()).use { pdf ->
            val fonts = PageFonts(pdf)
            val size = if (document.landscape) LANDSCAPE else PAGE
            val layout = PageLayout(fonts, size.width - 2 * MARGIN, size.height - MARGIN - HEADER_SPACE, CONTENT_BOTTOM)
            document.blocks.forEach(layout::place)
            val pages = layout.pages()
            // The header and footer are one line each: a line break in a memo's subject would reach the font as U+000A.
            val header = fonts.clean(document.header).replace('\n', ' ')
            val footer = fonts.clean(document.footer).replace('\n', ' ')
            pages.forEachIndexed { index, draws ->
                val page = PDPage(size)
                pdf.addPage(page)
                PDPageContentStream(pdf, page).use { stream ->
                    val canvas = Canvas(stream)
                    val count = if (document.pagesInWords) "Page ${index + 1} of ${pages.size}" else "${index + 1} / ${pages.size}"
                    frameOf(canvas, fonts, size, header, footer, count)
                    draws.forEach { it(canvas) }
                }
            }
            pdf.save(out)
            return RenderResult(pages.size, fonts.missing)
        }
    }

    /** The running header and the footer of one page. */
    private fun frameOf(
        canvas: Canvas,
        fonts: PageFonts,
        size: PDRectangle,
        header: String,
        footer: String,
        count: String,
    ) {
        val top = size.height - MARGIN
        val content = size.width - 2 * MARGIN
        canvas.text(MARGIN, top - SMALL, fonts.regular, SMALL, fit(fonts.regular, header, content), grey = true)
        canvas.line(MARGIN, top - SMALL - RULE_GAP, size.width - MARGIN, top - SMALL - RULE_GAP)
        canvas.line(MARGIN, MARGIN + SMALL + RULE_GAP, size.width - MARGIN, MARGIN + SMALL + RULE_GAP)
        val width = fonts.regular.getStringWidth(count) / EM * SMALL
        // A long footer (a report's filters) is cut short of the page count rather than drawn over it.
        canvas.text(MARGIN, MARGIN, fonts.regular, SMALL, fit(fonts.regular, footer, content - width - FOOTER_GAP), grey = true)
        canvas.text(size.width - MARGIN - width, MARGIN, fonts.regular, SMALL, count, grey = true)
    }

    /** [text] cut with an ellipsis to fit [width] at the small size. */
    private fun fit(
        font: PDType0Font,
        text: String,
        width: Float,
    ): String {
        if (font.getStringWidth(text) / EM * SMALL <= width) return text
        var cut = text
        while (cut.isNotEmpty() && font.getStringWidth("$cut…") / EM * SMALL > width) cut = cut.dropLast(1)
        return "$cut…"
    }

    internal companion object {
        val PAGE: PDRectangle = PDRectangle.A4
        val LANDSCAPE: PDRectangle = PDRectangle(PDRectangle.A4.height, PDRectangle.A4.width)

        /** 20 mm in points. */
        const val MARGIN = 56.69f
        const val SMALL = 8.5f
        const val EM = 1000f
        const val RULE_GAP = 4f
        private const val HEADER_SPACE = 26f
        private const val FOOTER_GAP = 12f
        const val CONTENT_BOTTOM: Float = MARGIN + HEADER_SPACE
    }
}
