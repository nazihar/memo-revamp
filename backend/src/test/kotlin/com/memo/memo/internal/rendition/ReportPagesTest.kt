package com.memo.memo.internal.rendition

import com.memo.memo.ReportColumn
import com.memo.memo.ReportDocument
import com.memo.memo.ReportSection
import com.memo.memo.ReportTable
import org.apache.pdfbox.Loader
import org.apache.pdfbox.rendering.ImageType
import org.apache.pdfbox.rendering.PDFRenderer
import org.apache.pdfbox.text.PDFTextStripper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.math.abs

/**
 * Report pages (design E06 §8, the reconciliation report's summary; ADR-0052's renderer): the title, the sections' headings,
 * paragraphs and lines come back from text extraction in order, nothing is missing from the font, and the first page matches
 * its golden image. Set `GOLDEN_UPDATE=true` to rewrite it (then review it). Landscape tables (the decision register's PDF,
 * design E08 §1.5): every cell in order, the header on every page, a row taller than a page continued, "Page n of N".
 */
class ReportPagesTest {
    private val pages = ReportPagesRenderer(PdfBoxPageRenderer())

    @Test
    fun `a report renders its sections in order, as the golden image`() {
        val first = render()
        Loader.loadPDF(first).use { pdf ->
            val text = PDFTextStripper().apply { sortByPosition = true }.getText(pdf).replace(Regex("\\s+"), " ")
            val expected = listOf("Reconciliation report", "Batch", "LEGACY-DMS", "Result", "BALANCED", "Rows", "Read", "1250", "Fixity")
            val positions = expected.map { item -> text.indexOf(item).also { assertThat(it).describedAs(item).isNotNegative() } }
            assertThat(positions).isSorted()
            golden("report-summary-1.png", PDFRenderer(pdf).renderImageWithDPI(0, DPI, ImageType.RGB))
        }
    }

    @Test
    fun `a landscape table repeats its header on every page and continues a row taller than a page`() {
        val long = (1..900).joinToString(" ") { "word$it" }
        val rows = (1..60).map { listOf("BRC $it/2026", "$it", "Title of item $it", if (it == 30) long else "Approved.") }
        val document =
            ReportDocument(
                "Register of committee decisions",
                "Register of committee decisions",
                "Produced by Demo Auditor on 22 November 2026",
                listOf(
                    ReportSection("About this export", lines = listOf("Decisions" to "60")),
                    ReportSection(
                        "Board Risk Committee",
                        table =
                            ReportTable(
                                listOf(
                                    ReportColumn("Meeting"),
                                    ReportColumn("Item", 0.4f),
                                    ReportColumn("Title", 2f),
                                    ReportColumn("Decision", 4f),
                                ),
                                rows,
                            ),
                    ),
                    ReportSection("Number check", lines = listOf("Board Risk Committee 2026" to "1 to 60, none missing")),
                ),
                landscape = true,
                pagesInWords = true,
            )
        val out = ByteArrayOutputStream()
        val result = pages.render(document, out)
        assertThat(result.missingGlyphs).isZero()
        Loader.loadPDF(out.toByteArray()).use { pdf ->
            assertThat(pdf.numberOfPages).isEqualTo(result.pages).isGreaterThan(2)
            val box = pdf.getPage(0).mediaBox
            assertThat(box.width).isGreaterThan(box.height)
            val stripper = PDFTextStripper().apply { sortByPosition = true }
            (1..pdf.numberOfPages).forEach { page ->
                stripper.startPage = page
                stripper.endPage = page
                val text = stripper.getText(pdf)
                assertThat(text).contains("Page $page of ${pdf.numberOfPages}", "Produced by Demo Auditor")
                // Every page of the table starts with its header; the last holds the number check after it.
                if (page > 1 && page < pdf.numberOfPages) assertThat(text).contains("Meeting", "Decision")
            }
            stripper.startPage = 1
            stripper.endPage = pdf.numberOfPages
            val text = stripper.getText(pdf).replace(Regex("\\s+"), " ")
            val titles = rows.map { it[2] }
            val expected =
                listOf("About this export") + titles.take(30) + listOf("word1 ", "word450 ", "word900 ") + titles.drop(30) + "Number check"
            assertThat(expected.map { text.indexOf(it) }).allSatisfy { assertThat(it).isNotNegative() }.isSorted()
        }
    }

    private fun render(): ByteArray {
        val out = ByteArrayOutputStream()
        val result = pages.render(sample(), out)
        assertThat(result.missingGlyphs).isZero()
        assertThat(result.pages).isGreaterThanOrEqualTo(1)
        return out.toByteArray()
    }

    private fun sample() =
        ReportDocument(
            "Reconciliation report",
            "Legacy import · Branch archive 2019",
            "Reconciliation report",
            listOf(
                ReportSection(
                    "Batch",
                    lines =
                        listOf(
                            "Batch" to "Branch archive 2019 (0196f2a4-0000-7000-8000-000000000001)",
                            "Source code" to "LEGACY-DMS",
                            "Started" to "Demo Admin, 2026-11-16T10:00:00Z",
                        ),
                ),
                ReportSection("Result", listOf("BALANCED", "Complete: yes. Valid: yes.")),
                ReportSection(
                    "Rows",
                    listOf("Read = imported + skipped + rejected + failed + not started."),
                    listOf("Read" to "1250", "Imported" to "1240", "Rejected" to "10", "Rejected NUMBER_IN_USE" to "10"),
                ),
                ReportSection("Fixity", lines = listOf("Imported originals" to "3720", "Verified" to "3720", "Mismatches" to "0")),
            ),
        )

    /** Compares with the golden image within a tolerance (anti-aliasing differs a little between platforms). */
    private fun golden(
        name: String,
        image: BufferedImage,
    ) {
        val path = Path.of("src/test/resources/golden/rendition", name)
        if (System.getenv("GOLDEN_UPDATE") == "true" || !Files.exists(path)) {
            Files.createDirectories(path.parent)
            ImageIO.write(image, "png", path.toFile())
            if (System.getenv("GOLDEN_UPDATE") != "true") error("golden image $name was missing and has been written: review it")
            return
        }
        val expected = ImageIO.read(path.toFile())
        assertThat(image.width to image.height).isEqualTo(expected.width to expected.height)
        var different = 0
        for (x in 0 until image.width) {
            for (y in 0 until image.height) {
                if (distance(image.getRGB(x, y), expected.getRGB(x, y)) > CHANNEL_TOLERANCE) different++
            }
        }
        assertThat(different.toDouble() / (image.width * image.height)).isLessThan(PIXEL_SHARE)
    }

    private fun distance(
        a: Int,
        b: Int,
    ): Int = (0..2).maxOf { shift -> abs((a shr (shift * 8) and 0xFF) - (b shr (shift * 8) and 0xFF)) }

    private companion object {
        const val DPI = 100f
        const val CHANNEL_TOLERANCE = 64
        const val PIXEL_SHARE = 0.005
    }
}
