package com.memo.memo.internal.rendition

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
import java.time.Duration
import java.time.Instant
import javax.imageio.ImageIO
import kotlin.math.abs

/**
 * Generated pages (ADR-0052, E04 T2): golden images of a cover, approval stamps and a note sheet in English; every string
 * comes back from text extraction in order; characters outside Noto Sans are counted; and the pages, given the PDF/A
 * metadata, pass veraPDF as PDF/A-2u. Set `GOLDEN_UPDATE=true` to rewrite the golden images (then review them).
 */
class PageRendererTest {
    private val renderer = PdfBoxPageRenderer()

    @Test
    fun `cover, stamps and a long note sheet render as the golden images, with every string in order`() {
        val pdf = render(sample())
        Loader.loadPDF(pdf).use { doc ->
            assertThat(doc.numberOfPages).isGreaterThanOrEqualTo(3)
            val text = normalised(PDFTextStripper().apply { sortByPosition = true }.getText(doc))
            val expected =
                listOf(
                    "Final record",
                    "Number M-2026-00042",
                    HASH,
                    "Round 1",
                    "Demo Head of Credit",
                    "paragraph 30.",
                    "Round 2",
                    "Approved.",
                )
            assertThat(positions(text, expected)).isSorted()
            val images = PDFRenderer(doc)
            (0 until minOf(doc.numberOfPages, GOLDEN_PAGES)).forEach { page ->
                golden("sheet-${page + 1}.png", images.renderImageWithDPI(page, DPI, ImageType.RGB))
            }
        }
    }

    @Test
    fun `characters the font lacks are drawn as a replacement and counted`() {
        val out = ByteArrayOutputStream()
        val result =
            renderer.render(
                PageDocument("M-1 · test", "Final record v1", listOf(Block.Paragraph("Ελληνικά, Кириллица, and 你好 ✓"))),
                out,
            )
        assertThat(result.missingGlyphs).isGreaterThanOrEqualTo(2)
        Loader.loadPDF(out.toByteArray()).use { doc ->
            assertThat(PDFTextStripper().getText(doc)).contains("Ελληνικά, Кириллица").contains("�")
        }
    }

    @Test
    fun `generated pages with the PDF-A metadata pass veraPDF as PDF-A-2u`() {
        val file = Files.createTempFile("generated", ".pdf")
        try {
            Loader.loadPDF(render(sample())).use { doc ->
                PdfaMetadata.apply(
                    doc,
                    RecordMetadata("Final record of M-2026-00042", "M-2026-00042", "Document SHA-256 $HASH", "memo", "memo test", AT),
                )
                doc.save(file.toFile())
            }
            val validation = VeraPdfValidator(Duration.ofSeconds(60)).validate(file)
            assertThat(validation.failedRules).isEmpty()
            assertThat(validation.compliant).isTrue()
        } finally {
            Files.deleteIfExists(file)
        }
    }

    private fun render(document: PageDocument): ByteArray {
        val out = ByteArrayOutputStream()
        val result = renderer.render(document, out)
        assertThat(result.missingGlyphs).isZero()
        return out.toByteArray()
    }

    private fun sample(): PageDocument {
        val blocks = mutableListOf<Block>()
        blocks += Block.Title("Final record")
        blocks +=
            Block.KeyValues(
                listOf(
                    "Number" to "M-2026-00042",
                    "Subject" to "Credit line renewal for Meghna Agro: a BDT 40 million working capital line for twelve months",
                    "Approved" to "15 Nov 2026, 14:20 UTC by Demo Director",
                    "Document" to "Version 2 · SHA-256 $HASH",
                ),
            )
        blocks += Block.Heading("Round 1")
        blocks +=
            Block.Box(
                listOf(
                    Block.Paragraph(listOf(Run("Demo Head of Credit", bold = true), Run("  Returned · 8 Nov 2026, 14:20"))),
                    Block.Paragraph(listOf(Run("Please attach annex B with the lease terms."))),
                    Block.Paragraph("Authenticated with password and authenticator, verified 2 minutes before.", small = true),
                ),
            )
        blocks += Block.Paragraph(listOf(Run("Annex B to follow.")), struck = true)
        blocks +=
            Block.Paragraph(
                (1..30).map { i ->
                    Run("This is paragraph $i. ", bold = i % 7 == 0, italic = i % 5 == 0)
                } + Run("The remark ends at paragraph 30."),
            )
        blocks += Block.Rule
        blocks += Block.Heading("Round 2")
        repeat(STAMPS) { i ->
            blocks +=
                Block.Box(
                    listOf(
                        Block.Paragraph(listOf(Run("Approver ${i + 1}", bold = true), Run("  Approved · 9 Nov 2026"))),
                        Block.KeyValues(listOf("Record" to HASH, "Previous" to HASH.reversed())),
                    ),
                )
        }
        blocks += Block.Paragraph(listOf(Run("Approved.", bold = true)))
        return PageDocument("M-2026-00042 · Credit line renewal for Meghna Agro", "Final record v1", blocks)
    }

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

    /** Without whitespace: wrapping puts line breaks inside long strings such as hashes. */
    private fun normalised(text: String) = text.replace(Regex("\\s+"), "")

    private fun positions(
        text: String,
        items: List<String>,
    ): List<Int> = items.map { item -> text.indexOf(normalised(item)).also { assertThat(it).describedAs(item).isNotNegative() } }

    private companion object {
        const val HASH = "3f9a1c07d2e84b6a9c0e5f7a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e"
        const val STAMPS = 12
        const val GOLDEN_PAGES = 3
        const val DPI = 100f
        const val CHANNEL_TOLERANCE = 64
        const val PIXEL_SHARE = 0.005
        val AT: Instant = Instant.parse("2026-11-15T14:22:00Z")
    }
}
