package com.memo.demo

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import java.io.ByteArrayOutputStream

/** One-page PDFs for the demo memos' attachments (Phase 3 E02 T8): plain text, no scripts, forms or embedded files. */
object DemoPdf {
    private const val MARGIN = 72f
    private const val TITLE_SIZE = 16f
    private const val BODY_SIZE = 11f
    private const val LEADING = 16f

    fun of(
        title: String,
        lines: List<String>,
    ): ByteArray =
        PDDocument().use { document ->
            val page = PDPage(PDRectangle.A4)
            document.addPage(page)
            PDPageContentStream(document, page).use { text ->
                text.beginText()
                text.newLineAtOffset(MARGIN, page.mediaBox.height - MARGIN)
                text.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD), TITLE_SIZE)
                text.showText(title)
                text.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), BODY_SIZE)
                lines.forEach { line ->
                    text.newLineAtOffset(0f, -LEADING)
                    text.showText(line)
                }
                text.endText()
            }
            ByteArrayOutputStream().also { document.save(it) }.toByteArray()
        }
}
