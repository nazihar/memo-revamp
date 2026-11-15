package com.memo.memo.internal.rendition

import org.apache.pdfbox.Loader
import org.apache.pdfbox.io.IOUtils
import org.apache.pdfbox.multipdf.PDFMergerUtility
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.PageMode
import org.apache.pdfbox.pdmodel.common.PDPageLabelRange
import org.apache.pdfbox.pdmodel.common.PDPageLabels
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitWidthDestination
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import org.apache.pdfbox.rendering.ImageType
import org.apache.pdfbox.rendering.PDFRenderer
import org.springframework.stereotype.Component
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO

/** One part of a record, in order, with its bookmark title and page-label prefix (for example `A1-`). */
sealed interface RecordPart {
    val title: String
    val label: String

    /** Pages we draw (cover, approvals, note sheet, placeholders). */
    data class Generated(
        override val title: String,
        override val label: String,
        val document: PageDocument,
    ) : RecordPart

    /** A PDF: the converter's PDF/A of a document, or an uploaded PDF. [fallback] stands in when it cannot be opened. */
    data class Pdf(
        override val title: String,
        override val label: String,
        val file: Path,
        val fallback: PageDocument,
    ) : RecordPart

    /** A PNG or JPEG, one page fitted to A4. */
    data class Image(
        override val title: String,
        override val label: String,
        val file: Path,
        val fallback: PageDocument,
    ) : RecordPart
}

/** How a part went in. */
enum class PartMode { GENERATED, NORMALISED, RASTERISED, IMAGE, PLACEHOLDER }

data class AssembledPart(
    val title: String,
    val firstPage: Int,
    val pages: Int,
    val mode: PartMode,
)

data class Assembly(
    val pages: Int,
    val parts: List<AssembledPart>,
    val missingGlyphs: Int,
) {
    val rasterisedPages: Int get() = parts.filter { it.mode == PartMode.RASTERISED }.sumOf { it.pages }
}

/**
 * Builds one PDF/A-2u file from its parts (ADR-0053): each part is made a PDF of its own in a spool directory, uploaded
 * PDFs are normalised and checked by the validator, and a part that still fails is rasterised; then the parts are merged
 * under one output intent, our metadata, an outline with one bookmark per part, and page labels per part. The caller
 * validates the result. Works in temp files, never the whole record in memory.
 */
@Component
class RecordAssembler(
    private val renderer: PageRenderer,
    private val validator: PdfaValidator,
) {
    fun assemble(
        parts: List<RecordPart>,
        metadata: RecordMetadata,
        out: Path,
    ): Assembly {
        val spool = Files.createTempDirectory("record-")
        try {
            var missing = 0
            val made =
                parts.mapIndexed { index, part ->
                    val file = spool.resolve("part-$index.pdf")
                    val (mode, glyphs) = make(part, file, metadata)
                    missing += glyphs
                    Triple(part, file, mode)
                }
            // The merger may read the parts' streams until the record is saved, so they stay open until then.
            val sources = mutableListOf<PDDocument>()
            try {
                PDDocument(IOUtils.createTempFileOnlyStreamCache()).use { record ->
                    val placed = merge(record, made, sources)
                    finish(record, placed, made.map { it.first }, metadata)
                    record.save(out.toFile())
                    return Assembly(record.numberOfPages, placed, missing)
                }
            } finally {
                sources.forEach(PDDocument::close)
            }
        } finally {
            spool.toFile().deleteRecursively()
        }
    }

    /** The part as a PDF file of its own; returns how it went in and the characters drawn as replacements. */
    private fun make(
        part: RecordPart,
        file: Path,
        metadata: RecordMetadata,
    ): Pair<PartMode, Int> =
        when (part) {
            is RecordPart.Generated -> PartMode.GENERATED to render(part.document, file)
            is RecordPart.Image -> image(part, file)
            is RecordPart.Pdf -> pdf(part, file, metadata)
        }

    private fun render(
        document: PageDocument,
        file: Path,
    ): Int = Files.newOutputStream(file).use { renderer.render(document, it).missingGlyphs }

    private fun image(
        part: RecordPart.Image,
        file: Path,
    ): Pair<PartMode, Int> {
        val picture =
            runCatching { ImageIO.read(part.file.toFile()) }.getOrNull() ?: return PartMode.PLACEHOLDER to render(part.fallback, file)
        PDDocument().use { doc ->
            val page = PDPage(PDRectangle.A4)
            doc.addPage(page)
            val image = LosslessFactory.createFromImage(doc, picture)
            draw(doc, page, image)
            doc.save(file.toFile())
        }
        return PartMode.IMAGE to 0
    }

    /**
     * An uploaded or converted PDF: opened (an encrypted or broken one becomes its placeholder), normalised, and checked
     * on its own with the record's metadata; a part that still fails is rasterised.
     */
    private fun pdf(
        part: RecordPart.Pdf,
        file: Path,
        metadata: RecordMetadata,
    ): Pair<PartMode, Int> {
        val opened =
            try {
                Loader.loadPDF(part.file.toFile(), IOUtils.createTempFileOnlyStreamCache())
            } catch (_: IOException) {
                null
            }
        if (opened == null || opened.isEncrypted) {
            opened?.close()
            return PartMode.PLACEHOLDER to render(part.fallback, file)
        }
        val mode =
            opened.use { doc ->
                PdfNormaliser.normalise(doc)
                PdfaMetadata.apply(doc, metadata)
                doc.save(file.toFile())
                if (validator.validate(file).compliant) {
                    PartMode.NORMALISED
                } else {
                    rasterise(doc, file)
                    PartMode.RASTERISED
                }
            }
        return mode to 0
    }

    /** Every page as an sRGB picture at [RASTER_DPI], on a page of the same size. */
    private fun rasterise(
        source: PDDocument,
        file: Path,
    ) {
        val images = PDFRenderer(source)
        PDDocument(IOUtils.createTempFileOnlyStreamCache()).use { doc ->
            for (index in 0 until source.numberOfPages) {
                val picture = images.renderImageWithDPI(index, RASTER_DPI, ImageType.RGB)
                val page = PDPage(source.getPage(index).mediaBox)
                doc.addPage(page)
                draw(doc, page, JPEGFactory.createFromImage(doc, picture, JPEG_QUALITY))
            }
            doc.save(file.toFile())
        }
    }

    /** [image] fitted inside the page, centred, keeping its proportions. */
    private fun draw(
        doc: PDDocument,
        page: PDPage,
        image: PDImageXObject,
    ) {
        val box = page.mediaBox
        val scale = minOf(box.width / image.width, box.height / image.height) * FILL
        val width = image.width * scale
        val height = image.height * scale
        PDPageContentStream(doc, page).use { it.drawImage(image, (box.width - width) / 2, (box.height - height) / 2, width, height) }
    }

    private fun merge(
        record: PDDocument,
        made: List<Triple<RecordPart, Path, PartMode>>,
        sources: MutableList<PDDocument>,
    ): List<AssembledPart> {
        val merger = PDFMergerUtility()
        return made.map { (part, file, mode) ->
            val first = record.numberOfPages
            val source = Loader.loadPDF(file.toFile(), IOUtils.createTempFileOnlyStreamCache())
            sources += source
            merger.appendDocument(record, source)
            AssembledPart(part.title, first, record.numberOfPages - first, mode)
        }
    }

    /** One output intent, our metadata, no forms or actions, one bookmark per part and page labels per part. */
    private fun finish(
        record: PDDocument,
        placed: List<AssembledPart>,
        parts: List<RecordPart>,
        metadata: RecordMetadata,
    ) {
        PdfNormaliser.normalise(record)
        PdfaMetadata.apply(record, metadata)
        val catalog = record.documentCatalog
        val outline = PDDocumentOutline()
        val labels = PDPageLabels(record)
        placed.zip(parts).filter { (at, _) -> at.pages > 0 }.forEach { (at, part) ->
            val item = PDOutlineItem()
            item.title = part.title
            item.destination = PDPageFitWidthDestination().apply { page = record.getPage(at.firstPage) }
            outline.addLast(item)
            labels.setLabelItem(
                at.firstPage,
                PDPageLabelRange().apply {
                    style = PDPageLabelRange.STYLE_DECIMAL
                    prefix = part.label
                },
            )
        }
        outline.openNode()
        catalog.documentOutline = outline
        catalog.pageLabels = labels
        catalog.pageMode = PageMode.USE_OUTLINES
    }

    private companion object {
        const val RASTER_DPI = 150f
        const val JPEG_QUALITY = 0.85f

        /** Pictures fill at most this share of the page, leaving a margin. */
        const val FILL = 0.9f
    }
}
