package com.memo.testsupport

import com.memo.files.FileType
import com.memo.files.RenditionConverter
import com.memo.files.RenditionInput
import com.memo.files.RenditionOutcome
import com.memo.files.TextExtractor
import com.memo.memo.internal.rendition.Block
import com.memo.memo.internal.rendition.PageDocument
import com.memo.memo.internal.rendition.PdfBoxPageRenderer
import com.memo.memo.internal.rendition.PdfaMetadata
import com.memo.memo.internal.rendition.RecordMetadata
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.springframework.context.annotation.Primary
import org.springframework.stereotype.Component
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Stand-ins for the document server's conversion and the text extractor (ADR-0045), scripted by the file name so that
 * whichever scheduler thread runs a rendition gets the same answer: `final-…` fails for good, `retry-once-…` fails once
 * then works, `always-retry-…` keeps failing; anything else becomes a one-page PDF and a line of text. In every test
 * context, because the scheduler of any context may run a rendition task scheduled by another (they share the database).
 */
@Component
@Primary
class FakeRenditionServices :
    RenditionConverter,
    TextExtractor {
    private val calls = ConcurrentHashMap<String, AtomicInteger>()

    override val pdfTypes = setOf(FileType.DOCX, FileType.XLSX, FileType.TEXT)
    override val textTypes = setOf(FileType.DOCX, FileType.XLSX, FileType.TEXT, FileType.PDF)

    override fun toPdf(
        input: RenditionInput,
        key: String,
        out: OutputStream,
    ): RenditionOutcome = scripted(input, "pdf") { out.write(PDF) }

    /** The final record's conversion (ADR-0053): a one-page PDF/A-2u naming the file, scripted like [toPdf]. */
    override fun toPdfA(
        input: RenditionInput,
        key: String,
        out: OutputStream,
    ): RenditionOutcome = scripted(input, "pdfa") { out.write(pdfa(input.name)) }

    override fun text(
        input: RenditionInput,
        maxChars: Long,
        out: OutputStream,
    ): RenditionOutcome = scripted(input, "text") { out.write(textOf(input.name).toByteArray()) }

    /** One line, or for `large-…` about 1 MB of numbered lines that name the file in every 500th (performance budget). */
    private fun textOf(name: String): String =
        if (name.startsWith("large-")) {
            (1..LARGE_LINES).joinToString("\n") { i ->
                if (i % 500 == 0) "Line $i of $name" else "Line $i: the branch budget, the timeline and the risks."
            }
        } else {
            "Text of $name"
        }

    fun calls(
        name: String,
        kind: String,
    ): Int = calls["$name/$kind"]?.get() ?: 0

    private fun scripted(
        input: RenditionInput,
        kind: String,
        write: () -> Unit,
    ): RenditionOutcome {
        val call = calls.computeIfAbsent("${input.name}/$kind") { AtomicInteger() }.incrementAndGet()
        // Read the whole input, as a real service would: proves the original decrypts.
        input.open().use { it.readAllBytes() }
        return when {
            input.name.startsWith("final-") -> RenditionOutcome.Final("SIZE")
            input.name.startsWith("always-retry-") -> RenditionOutcome.Retry("UNREACHABLE")
            input.name.startsWith("retry-once-") && call == 1 -> RenditionOutcome.Retry("UNREACHABLE")
            else -> RenditionOutcome.Written().also { write() }
        }
    }

    companion object {
        private const val LARGE_LINES = 18_000

        /** "Converted <name>" on one page, with the PDF/A metadata, so the record's text check and veraPDF see a real part. */
        fun pdfa(name: String): ByteArray {
            val raw = ByteArrayOutputStream()
            PdfBoxPageRenderer().render(PageDocument(name, "Converted", listOf(Block.Paragraph("Converted $name"))), raw)
            return Loader.loadPDF(raw.toByteArray()).use { doc ->
                PdfaMetadata.apply(
                    doc,
                    RecordMetadata(name, name, "Converted $name", "test", "test", Instant.parse("2026-01-01T00:00:00Z")),
                )
                ByteArrayOutputStream().also { doc.save(it) }.toByteArray()
            }
        }

        val PDF: ByteArray =
            PDDocument().use { document ->
                document.addPage(PDPage())
                ByteArrayOutputStream().also { document.save(it) }.toByteArray()
            }
    }
}
