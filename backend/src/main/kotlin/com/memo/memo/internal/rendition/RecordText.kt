package com.memo.memo.internal.rendition

import org.apache.pdfbox.Loader
import org.apache.pdfbox.io.IOUtils
import org.apache.pdfbox.text.PDFTextStripper
import java.nio.file.Path
import java.text.Normalizer

/**
 * The record's text check (ADR-0053): the key items (number, subject, words of the approved document, each note and
 * stamp) must come back from the file's text, in order. Whitespace and Unicode compatibility forms are ignored, because
 * wrapping breaks lines inside long strings and converters write ligatures. Rasterised pages have no text; leave their
 * items out.
 */
object RecordText {
    /** The [items] not found after the previous one, in order; empty when all are there. */
    fun missing(
        file: Path,
        items: List<String>,
    ): List<String> {
        val text =
            Loader.loadPDF(file.toFile(), IOUtils.createTempFileOnlyStreamCache()).use { doc ->
                normalised(PDFTextStripper().apply { sortByPosition = true }.getText(doc))
            }
        var from = 0
        return items.filter { item ->
            val at = text.indexOf(normalised(item), from)
            if (at >= 0) from = at + 1
            at < 0
        }
    }

    private fun normalised(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFKC).replace(Regex("\\s+"), "")
}
