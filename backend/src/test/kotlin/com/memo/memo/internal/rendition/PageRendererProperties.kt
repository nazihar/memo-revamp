package com.memo.memo.internal.rendition

import net.jqwik.api.Example
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
        rendersAndComesBack(text)
    }

    /** The stripper gives a ligature back as its letters (the nightly's 1,000 tries met one between two words). */
    @Suppress("ktlint:standard:function-naming")
    @Example
    fun `the words beside a ligature come back`() {
        rendersAndComesBack("0ﬂD")
    }

    private fun rendersAndComesBack(text: String) {
        val out = ByteArrayOutputStream()
        val result = renderer.render(PageDocument(text.take(80), "Final record v1", listOf(Block.Paragraph(text))), out)
        assertThat(result.pages).isPositive()
        Loader.loadPDF(out.toByteArray()).use { doc ->
            val extracted = plain(PDFTextStripper().getText(doc))
            // A character outside ASCII may be missing from the font, or come back as other letters (the stripper gives
            // "ﬂ" back as "fl"), so each ASCII run between such characters must come back whole, in order.
            var from = 0
            for (run in text.split(Regex("""[^\x00-\x7F]+""")).map(::plain).filter { it.isNotEmpty() }) {
                val at = extracted.indexOf(run, from)
                assertThat(at).describedAs("%s comes back after position %d of %s", run, from, extracted).isNotNegative()
                from = at + run.length
            }
        }
    }

    /** The letters and digits of [text]: the layout adds and drops spaces and punctuation. */
    private fun plain(text: String): String = text.filter { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' }
}
