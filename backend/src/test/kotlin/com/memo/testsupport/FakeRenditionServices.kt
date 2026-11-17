package com.memo.testsupport

import com.memo.files.FileType
import com.memo.files.RenditionConverter
import com.memo.files.RenditionInput
import com.memo.files.RenditionOutcome
import com.memo.files.TextExtractor
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.springframework.context.annotation.Primary
import org.springframework.stereotype.Component
import java.io.ByteArrayOutputStream
import java.io.OutputStream
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

    override fun text(
        input: RenditionInput,
        maxChars: Long,
        out: OutputStream,
    ): RenditionOutcome = scripted(input, "text") { out.write("Text of ${input.name}".toByteArray()) }

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
        val PDF: ByteArray =
            PDDocument().use { document ->
                document.addPage(PDPage())
                ByteArrayOutputStream().also { document.save(it) }.toByteArray()
            }
    }
}
