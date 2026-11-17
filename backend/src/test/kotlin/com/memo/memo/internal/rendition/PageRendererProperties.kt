package com.memo.memo.internal.rendition

import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.constraints.StringLength
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.assertj.core.api.Assertions.assertThat
import java.io.ByteArrayOutputStream

class PageRendererProperties {
    private val renderer = PdfBoxPageRenderer()

    @Suppress("ktlint:standard:function-naming") // jqwik properties are tests: descriptive names, like @Test methods
    @Property(tries = 40)
    fun `any text renders without error, and the words the font has come back`(
        @ForAll @StringLength(max = 600) text: String,
    ) {
        val out = ByteArrayOutputStream()
        val result = renderer.render(PageDocument(text.take(80), "Final record v1", listOf(Block.Paragraph(text))), out)
        assertThat(result.pages).isPositive()
        val plain = text.filter { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' }
        Loader.loadPDF(out.toByteArray()).use { doc ->
            val extracted = PDFTextStripper().getText(doc).filter { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' }
            assertThat(extracted).contains(plain)
        }
    }
}
