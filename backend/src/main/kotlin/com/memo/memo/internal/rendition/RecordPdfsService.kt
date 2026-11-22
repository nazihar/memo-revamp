package com.memo.memo.internal.rendition

import com.memo.files.RenditionConverter
import com.memo.files.RenditionInput
import com.memo.files.RenditionOutcome
import com.memo.memo.RecordPdf
import com.memo.memo.RecordPdfFailed
import com.memo.memo.RecordPdfPart
import com.memo.memo.RecordPdfRequest
import com.memo.memo.RecordPdfRetry
import com.memo.memo.RecordPdfs
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Component
import java.nio.file.Path
import java.nio.file.Files as Paths

/** A record file made: how it was assembled, veraPDF's verdict and the text items missing from it. */
data class MadeRecord(
    val assembly: Assembly,
    val validation: PdfaValidation,
    val textMissing: List<String>,
)

/**
 * The last steps of every record (ADR-0053), shared by the memo's final record and [RecordPdfs]: the parts assembled into
 * one PDF/A-2u file, validated by veraPDF, and its text checked for the key items.
 */
@Component
class RecordFileMaker(
    private val assembler: RecordAssembler,
    private val validator: PdfaValidator,
) {
    fun make(
        parts: List<RecordPart>,
        metadata: RecordMetadata,
        textItems: List<String>,
        out: Path,
        stamp: String? = null,
    ): MadeRecord {
        val assembly = assembler.assemble(parts, metadata, out, stamp)
        val validation = validator.validate(out)
        return MadeRecord(assembly, validation, RecordText.missing(out, textItems))
    }
}

/**
 * [RecordPdfs] for other modules (ADR-0053, ADR-0066): report pages drawn by the renderer, documents converted to PDF/A by
 * the document server (a refusal fails the build, an outage asks for a retry: a record never stands in a placeholder for
 * its main document), then [RecordFileMaker]. Works in a spool directory of temp files.
 */
@Component
class RecordPdfsService(
    private val maker: RecordFileMaker,
    private val renderer: PageRenderer,
    private val converter: ObjectProvider<RenditionConverter>,
) : RecordPdfs {
    override fun build(
        request: RecordPdfRequest,
        out: Path,
    ): RecordPdf {
        val spool = Paths.createTempDirectory("record-pdfs-")
        try {
            val parts = request.parts.mapIndexed { i, part -> prepare(part, i, request.key, spool) }
            val m = request.metadata
            val description = "${m.description} · ${renderer.name}"
            val metadata = RecordMetadata(m.title, m.identifier, description, m.author, PRODUCER, m.created)
            val made = maker.make(parts, metadata, request.textItems, out, request.stamp)
            // A document that went in as its placeholder is not the record: fail rather than keep a record without it.
            if (made.assembly.parts.any { it.mode == PartMode.PLACEHOLDER }) throw RecordPdfFailed("UNREADABLE")
            return RecordPdf(
                pages = made.assembly.pages,
                compliant = made.validation.compliant,
                profile = made.validation.profile,
                validator = made.validation.validator,
                failedRules = made.validation.failedRules,
                validationError = made.validation.error,
                renderer = renderer.name,
                rasterisedPages = made.assembly.rasterisedPages,
                missingGlyphs = made.assembly.missingGlyphs,
                textMissing = made.textMissing,
            )
        } finally {
            spool.toFile().deleteRecursively()
        }
    }

    private fun prepare(
        part: RecordPdfPart,
        position: Int,
        key: String,
        spool: Path,
    ): RecordPart =
        when (part) {
            is RecordPdfPart.Pages -> {
                RecordPart.Generated(part.title, part.label, ReportPagesRenderer.pagesOf(part.document))
            }

            is RecordPdfPart.Document -> {
                val convert = converter.ifAvailable?.takeIf { part.type in it.pdfTypes } ?: throw RecordPdfFailed("NO_CONVERTER")
                val out = spool.resolve("converted-$position.pdf")
                val input = RenditionInput(part.name, part.type, Paths.size(part.file)) { Paths.newInputStream(part.file) }
                val outcome = Paths.newOutputStream(out).use { convert.toPdfA(input, "$key-$position", it) }
                when (outcome) {
                    is RenditionOutcome.Written -> Unit
                    is RenditionOutcome.Retry -> throw RecordPdfRetry("CONVERTER_${outcome.code}")
                    is RenditionOutcome.Final -> throw RecordPdfFailed("CONVERTER_${outcome.code}")
                }
                val fallback = PageDocument(part.title, "", listOf(Block.Title(part.title), Block.Paragraph(UNREADABLE)))
                RecordPart.Pdf(part.title, part.label, out, fallback)
            }
        }

    private companion object {
        const val PRODUCER = "memo"
        const val UNREADABLE = "The converted document could not be read."
    }
}
