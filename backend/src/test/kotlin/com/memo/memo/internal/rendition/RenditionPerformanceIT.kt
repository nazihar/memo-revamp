package com.memo.memo.internal.rendition

import com.memo.testsupport.Latency
import org.apache.pdfbox.Loader
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant

/**
 * The record's performance budget (design E04 §9), without the converter (measured on dev in the spike): generated pages
 * for 30 notes and 10 stamps, assembling a 60-page record from a 10-page document, the pages and 5 attachments, and
 * veraPDF on 200 pages. Budgets have 2x headroom for shared CI runners; the measured times are printed for the log.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RenditionPerformanceIT {
    private val renderer = PdfBoxPageRenderer()
    private val validator = VeraPdfValidator(Duration.ofSeconds(VALIDATION_LIMIT_S))
    private val assembler = RecordAssembler(renderer, validator)
    private val dir: Path = Files.createTempDirectory("rendition-perf")

    @AfterAll
    fun clean() {
        dir.toFile().deleteRecursively()
    }

    @Test
    fun `generated pages for 30 notes and 10 stamps render within budget`() {
        val document = sheet(notes = 30, stamps = 10)
        repeat(WARM_UP) { renderer.render(document, ByteArrayOutputStream()) }
        val p95 =
            p95 {
                renderer.render(document, ByteArrayOutputStream())
            }
        println("RenditionPerformanceIT generated pages (30 notes, 10 stamps) p95: $p95 ms")
        assertThat(p95).isLessThan(PAGES_BUDGET_MS * 2)
    }

    @Test
    fun `a 60-page record assembles and validates within budget`() {
        val document = pdfPart("document.pdf", pages = 10)
        val attachments = (1..5).map { pdfPart("attachment-$it.pdf", pages = 8) }
        val parts =
            listOf(RecordPart.Generated("Cover", "C-", sheet(notes = 0, stamps = 0))) +
                RecordPart.Pdf("Document", "D-", document, sheet(0, 0)) +
                RecordPart.Generated("Approvals and notes", "N-", sheet(notes = 30, stamps = 10)) +
                attachments.mapIndexed { i, file -> RecordPart.Pdf("Attachment ${i + 1}", "A${i + 1}-", file, sheet(0, 0)) }
        val out = dir.resolve("record.pdf")
        val start = System.nanoTime()
        val assembly = assembler.assemble(parts, METADATA, out)
        val assembled = (System.nanoTime() - start) / NANOS_PER_MILLI
        val check = System.nanoTime()
        val validation = validator.validate(out)
        val validated = (System.nanoTime() - check) / NANOS_PER_MILLI
        println("RenditionPerformanceIT record of ${assembly.pages} pages: assembled in $assembled ms, validated in $validated ms")
        assertThat(validation.compliant).isTrue()
        assertThat(assembly.pages).isGreaterThanOrEqualTo(MIN_RECORD_PAGES)
        assertThat(assembled + validated).isLessThan(RECORD_BUDGET_MS * 2)
    }

    @Test
    fun `veraPDF checks a 200-page record within budget`() {
        val big = dir.resolve("big.pdf")
        Loader.loadPDF(pdfPart("big-source.pdf", pages = 200).toFile()).use { doc ->
            PdfaMetadata.apply(doc, METADATA)
            doc.save(big.toFile())
        }
        validator.validate(big)
        val start = System.nanoTime()
        val validation = validator.validate(big)
        val millis = (System.nanoTime() - start) / NANOS_PER_MILLI
        println("RenditionPerformanceIT veraPDF on 200 pages: $millis ms")
        assertThat(validation.error).isNull()
        assertThat(millis).isLessThan(VALIDATION_BUDGET_MS * 2)
    }

    /** A PDF/A part of [pages] generated pages (as the converter's output would be). */
    private fun pdfPart(
        name: String,
        pages: Int,
    ): Path {
        val raw = ByteArrayOutputStream()
        val blocks = (1..pages).flatMap { listOf(Block.Paragraph(TEXT.repeat(PARAGRAPHS_PER_PAGE)), Block.PageBreak) }
        renderer.render(PageDocument(name, name, blocks), raw)
        val file = dir.resolve(name)
        Loader.loadPDF(raw.toByteArray()).use { doc ->
            PdfaMetadata.apply(doc, METADATA)
            doc.save(file.toFile())
        }
        return file
    }

    private fun sheet(
        notes: Int,
        stamps: Int,
    ): PageDocument {
        val blocks = mutableListOf<Block>(Block.Title("Note sheet"))
        repeat(stamps) { i ->
            blocks +=
                Block.Box(
                    listOf(
                        Block.Paragraph(listOf(Run("Approver ${i + 1}", bold = true), Run("  Approved · 9 Nov 2026"))),
                        Block.KeyValues(listOf("Record hash" to HASH, "Previous record" to HASH)),
                    ),
                )
        }
        repeat(notes) { i ->
            blocks += Block.Box(listOf(Block.Paragraph(listOf(Run("Reader ${i + 1}", bold = true))), Block.Paragraph(TEXT.repeat(3))))
        }
        return PageDocument("M-2026-00042 · Performance", "Final record v1", blocks)
    }

    private fun p95(call: () -> Unit): Long = Latency.p95(warmUp = 0, runs = RUNS, call = call)

    private companion object {
        const val TEXT = "The borrower asks to renew a working capital line for twelve months, secured by a mortgage. "
        const val HASH = "3f9a1c07d2e84b6a9c0e5f7a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e"
        const val PARAGRAPHS_PER_PAGE = 12
        const val WARM_UP = 3
        const val RUNS = 20
        const val NANOS_PER_MILLI = 1_000_000L
        const val PAGES_BUDGET_MS = 1_000L
        const val RECORD_BUDGET_MS = 30_000L
        const val VALIDATION_BUDGET_MS = 15_000L
        const val VALIDATION_LIMIT_S = 120L
        const val MIN_RECORD_PAGES = 55
        val METADATA =
            RecordMetadata("Final record", "M-2026-00042", "Performance", "memo", "memo test", Instant.parse("2026-11-15T00:00:00Z"))
    }
}
