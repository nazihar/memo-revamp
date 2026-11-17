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
 * Generated pages on PDFBox with Noto Sans (ADR-0052): A4 portrait, 20 mm margins, a running header with the memo's
 * number and subject, and a footer with the record's name and "page n of N". Deterministic: nothing depends on the clock
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
            val layout = PageLayout(fonts, CONTENT_WIDTH, CONTENT_TOP, CONTENT_BOTTOM)
            document.blocks.forEach(layout::place)
            val pages = layout.pages()
            val header = fonts.clean(document.header)
            val footer = fonts.clean(document.footer)
            pages.forEachIndexed { index, draws ->
                val page = PDPage(PDRectangle.A4)
                pdf.addPage(page)
                PDPageContentStream(pdf, page).use { stream ->
                    val canvas = Canvas(stream)
                    frameOf(canvas, fonts, header, footer, index + 1, pages.size)
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
        header: String,
        footer: String,
        page: Int,
        pages: Int,
    ) {
        val top = PAGE.height - MARGIN
        canvas.text(MARGIN, top - SMALL, fonts.regular, SMALL, fit(fonts.regular, header, CONTENT_WIDTH), grey = true)
        canvas.line(MARGIN, top - SMALL - RULE_GAP, PAGE.width - MARGIN, top - SMALL - RULE_GAP)
        canvas.line(MARGIN, MARGIN + SMALL + RULE_GAP, PAGE.width - MARGIN, MARGIN + SMALL + RULE_GAP)
        canvas.text(MARGIN, MARGIN, fonts.regular, SMALL, footer, grey = true)
        val count = "$page / $pages"
        val width = fonts.regular.getStringWidth(count) / EM * SMALL
        canvas.text(PAGE.width - MARGIN - width, MARGIN, fonts.regular, SMALL, count, grey = true)
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

        /** 20 mm in points. */
        const val MARGIN = 56.69f
        const val SMALL = 8.5f
        const val EM = 1000f
        const val RULE_GAP = 4f
        private const val HEADER_SPACE = 26f
        val CONTENT_WIDTH: Float = PAGE.width - 2 * MARGIN
        val CONTENT_TOP: Float = PAGE.height - MARGIN - HEADER_SPACE
        const val CONTENT_BOTTOM: Float = MARGIN + HEADER_SPACE
    }
}
