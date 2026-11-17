package com.memo.memo.internal.rendition

import org.apache.pdfbox.Loader
import org.apache.pdfbox.cos.COSName
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.PDResources
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.encryption.AccessPermission
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.apache.pdfbox.pdmodel.interactive.action.PDActionJavaScript
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm
import org.apache.pdfbox.pdmodel.interactive.form.PDTextField
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import javax.imageio.ImageIO

/**
 * The assembler (ADR-0053, E04 T3): one record from generated pages, a clean PDF, a hostile PDF (JavaScript, a launch-style
 * open action, a page action, a form, a font not embedded, CMYK), an encrypted PDF, a broken file and a transparent PNG.
 * The record passes veraPDF as PDF/A-2u, each part went in the expected way, and nothing hostile is left.
 */
class RecordAssemblerTest {
    private val validator = VeraPdfValidator(Duration.ofSeconds(60))
    private val renderer = PdfBoxPageRenderer()
    private val assembler = RecordAssembler(renderer, validator)
    private val dir: Path = Files.createTempDirectory("assembler-test")

    @AfterEach
    fun removeFiles() {
        dir.toFile().deleteRecursively()
    }

    @Test
    fun `every kind of part becomes a valid PDF-A-2u record with bookmarks, labels and nothing hostile`() {
        val parts =
            listOf(
                RecordPart.Generated("Cover", "C-", pages("Final record", "Credit line renewal for Meghna Agro")),
                RecordPart.Pdf("Approved document", "D-", clean(), placeholder("document")),
                RecordPart.Pdf("Attachment 1: hostile.pdf", "A1-", hostile(), placeholder("hostile.pdf")),
                RecordPart.Pdf("Attachment 2: locked.pdf", "A2-", encrypted(), placeholder("locked.pdf")),
                RecordPart.Pdf("Attachment 3: broken.pdf", "A3-", broken(), placeholder("broken.pdf")),
                RecordPart.Image("Attachment 4: chart.png", "A4-", png(), placeholder("chart.png")),
                RecordPart.Generated("Attachment 5: data.zip", "A5-", placeholder("data.zip")),
            )
        val out = dir.resolve("record.pdf")
        val assembly = assembler.assemble(parts, metadata(), out)

        assertThat(assembly.parts.map { it.mode }).containsExactly(
            PartMode.GENERATED,
            PartMode.NORMALISED,
            PartMode.RASTERISED,
            PartMode.PLACEHOLDER,
            PartMode.PLACEHOLDER,
            PartMode.IMAGE,
            PartMode.GENERATED,
        )
        assertThat(assembly.parts.map { it.pages }).allSatisfy { assertThat(it).isPositive() }
        assertThat(assembly.rasterisedPages).isEqualTo(1)

        val validation = validator.validate(out)
        assertThat(validation.failedRules).isEmpty()
        assertThat(validation.compliant).isTrue()

        Loader.loadPDF(out.toFile()).use { record ->
            val catalog = record.documentCatalog
            assertThat(catalog.cosObject.containsKey(COSName.OPEN_ACTION)).isFalse()
            assertThat(catalog.acroForm).isNull()
            assertThat(catalog.names?.cosObject?.containsKey(COSName.JAVA_SCRIPT) ?: false).isFalse()
            assertThat(catalog.outputIntents).hasSize(1)
            assertThat(record.pages.flatMap { it.annotations }.mapNotNull { (it as? PDAnnotationLink)?.action })
                .noneMatch { it is PDActionJavaScript }
            val titles = generateSequence(catalog.documentOutline.firstChild) { it.nextSibling }.map { it.title }.toList()
            assertThat(titles).containsExactlyElementsOf(parts.map { it.title })
            val labels = catalog.pageLabels.labelsByPageIndices
            assertThat(labels[assembly.parts[2].firstPage]).isEqualTo("A1-1")
            assertThat(labels[0]).isEqualTo("C-1")
        }
        assertThat(RecordText.missing(out, listOf("Final record", "Credit line renewal", "Approved document text", "locked.pdf")))
            .isEmpty()
    }

    private fun metadata() =
        RecordMetadata(
            "Final record of M-2026-00042",
            "M-2026-00042",
            "Document SHA-256 3f9a1c07",
            "memo",
            "memo test",
            Instant.parse("2026-11-15T14:22:00Z"),
        )

    private fun pages(vararg lines: String) = PageDocument("M-2026-00042", "Final record v1", lines.map { Block.Paragraph(it) })

    private fun placeholder(name: String) =
        PageDocument("M-2026-00042", "Final record v1", listOf(Block.Heading(name), Block.Paragraph("Kept as the original file.")))

    /** A part as the converter gives it: our own pages with PDF/A metadata. */
    private fun clean(): Path {
        val file = dir.resolve("clean.pdf")
        val raw = dir.resolve("clean-raw.pdf")
        Files.newOutputStream(raw).use { renderer.render(pages("Approved document text"), it) }
        Loader.loadPDF(raw.toFile()).use { doc ->
            PdfaMetadata.apply(doc, metadata())
            doc.save(file.toFile())
        }
        return file
    }

    private fun hostile(): Path {
        val file = dir.resolve("hostile.pdf")
        PDDocument().use { doc ->
            val page = PDPage(PDRectangle.A4)
            doc.addPage(page)
            val helvetica = PDType1Font(Standard14Fonts.FontName.HELVETICA)
            PDPageContentStream(doc, page).use { cs ->
                cs.setNonStrokingColor(0.1f, 0.6f, 0.2f, 0.05f)
                cs.addRect(50f, 600f, 200f, 100f)
                cs.fill()
                cs.beginText()
                cs.setFont(helvetica, 12f)
                cs.newLineAtOffset(50f, 750f)
                cs.showText("A font that is not embedded")
                cs.endText()
            }
            doc.documentCatalog.openAction = PDActionJavaScript("app.alert('open')")
            page.cosObject.setItem(COSName.AA, PDActionJavaScript("app.alert('page')").cosObject)
            page.annotations =
                listOf(
                    PDAnnotationLink().apply {
                        rectangle = PDRectangle(50f, 500f, 100f, 20f)
                        action = PDActionJavaScript("app.alert('link')")
                    },
                )
            val form = PDAcroForm(doc)
            form.defaultResources = PDResources().apply { put(COSName.HELV, helvetica) }
            form.defaultAppearance = "/Helv 10 Tf 0 g"
            doc.documentCatalog.acroForm = form
            val field = PDTextField(form)
            field.partialName = "amount"
            field.widgets.first().apply {
                rectangle = PDRectangle(50f, 450f, 200f, 20f)
                this.page = page
            }
            page.annotations = page.annotations + field.widgets
            form.fields.add(field)
            field.value = "40,000,000"
            doc.save(file.toFile())
        }
        return file
    }

    private fun encrypted(): Path {
        val file = dir.resolve("locked.pdf")
        PDDocument().use { doc ->
            doc.addPage(PDPage(PDRectangle.A4))
            doc.protect(StandardProtectionPolicy("owner-secret", "user-secret", AccessPermission()).apply { encryptionKeyLength = 128 })
            doc.save(file.toFile())
        }
        return file
    }

    private fun broken(): Path = dir.resolve("broken.pdf").also { Files.writeString(it, "%PDF-1.7\nnot really a pdf") }

    private fun png(): Path {
        val file = dir.resolve("chart.png")
        val image = BufferedImage(PICTURE, PICTURE / 2, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        g.color = Color(30, 90, 160, 120)
        g.fillOval(10, 10, PICTURE - 20, PICTURE / 2 - 20)
        g.dispose()
        ImageIO.write(image, "png", file.toFile())
        return file
    }

    private companion object {
        const val PICTURE = 400
    }
}
