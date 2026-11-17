package com.memo.docedit.internal

import com.memo.files.FileType
import com.memo.files.RenditionInput
import com.memo.files.RenditionOutcome
import org.apache.poi.common.usermodel.PictureType
import org.apache.poi.util.Units
import org.apache.poi.xslf.usermodel.XMLSlideShow
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.apache.poi.xwpf.usermodel.XWPFDocument
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.verapdf.gf.foundry.VeraGreenfieldFoundryProvider
import org.verapdf.pdfa.Foundries
import org.verapdf.pdfa.flavours.PDFAFlavour
import tools.jackson.databind.json.JsonMapper
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.Clock
import java.util.UUID
import javax.imageio.ImageIO

/**
 * ONLYOFFICE's `pdfa` output (ADR-0053, E04 T1): converts a Word memo with a table and a picture, a spreadsheet and a
 * presentation, and asks veraPDF which PDF/A part and level each claims and whether it passes. Runs against a real
 * document server only (`MEMO_SPIKE_DOCSERVER`, `MEMO_SPIKE_JWT`); the results are recorded in the E04 log.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "MEMO_SPIKE_DOCSERVER", matches = ".+")
class PdfaConversionIT {
    private lateinit var converter: OnlyOfficeConverter

    @BeforeAll
    fun setUp() {
        VeraGreenfieldFoundryProvider.initialise()
        converter =
            OnlyOfficeConverter(
                DocEditProperties(documentServerUrl = System.getenv("MEMO_SPIKE_DOCSERVER"), jwtSecret = System.getenv("MEMO_SPIKE_JWT")),
                JsonMapper.builder().build(),
                Clock.systemUTC(),
            )
    }

    @Test
    fun `pdfa output of a Word memo, a spreadsheet and a presentation, as veraPDF sees it`() {
        val inputs =
            listOf(
                Triple("memo.docx", FileType.DOCX, wordMemo()),
                Triple("figures.xlsx", FileType.XLSX, spreadsheet()),
                Triple("slides.pptx", FileType.PPTX, presentation()),
            )
        val results =
            inputs.map { (name, type, bytes) ->
                val input = RenditionInput(name, type, bytes.size.toLong()) { ByteArrayInputStream(bytes) }
                val out = ByteArrayOutputStream()
                val start = System.nanoTime()
                val outcome = converter.toPdfA(input, "spike-${UUID.randomUUID()}", out)
                val millis = (System.nanoTime() - start) / NANOS_PER_MILLI
                assertThat(outcome).isEqualTo(RenditionOutcome.Written())
                val verdict = verdict(out.toByteArray())
                println("PdfaConversionIT $name: $millis ms, ${out.size()} bytes, $verdict")
                verdict
            }
        assertThat(results).allSatisfy { assertThat(it.claimed).isNotEqualTo("none") }
    }

    private data class Verdict(
        val claimed: String,
        val compliantAsClaimed: Boolean,
        val failedRules: List<String>,
        val compliantAs2u: Boolean,
        val failedRules2u: List<String>,
        val validationMillis: Long,
    )

    private fun verdict(pdf: ByteArray): Verdict {
        val start = System.nanoTime()
        Foundries.defaultInstance().use { foundry ->
            val (claimed, ok, failed) =
                foundry.createParser(ByteArrayInputStream(pdf)).use { parser ->
                    val flavour = parser.flavour
                    val result = foundry.createValidator(flavour, false).validate(parser)
                    Triple(flavour.id, result.isCompliant, failedRules(result))
                }
            val (ok2u, failed2u) =
                foundry.createParser(ByteArrayInputStream(pdf), PDFAFlavour.fromString("2u")).use { parser ->
                    val result = foundry.createValidator(PDFAFlavour.fromString("2u"), false).validate(parser)
                    result.isCompliant to failedRules(result)
                }
            return Verdict(claimed, ok, failed, ok2u, failed2u, (System.nanoTime() - start) / NANOS_PER_MILLI)
        }
    }

    private fun failedRules(result: org.verapdf.pdfa.results.ValidationResult): List<String> =
        result.testAssertions
            .filter { it.status == org.verapdf.pdfa.results.TestAssertion.Status.FAILED }
            .map { "${it.ruleId.clause}-${it.ruleId.testNumber}" }
            .distinct()

    private fun wordMemo(): ByteArray =
        XWPFDocument().use { doc ->
            doc.createParagraph().createRun().apply {
                isBold = true
                setText("Credit line renewal for Meghna Agro")
            }
            repeat(PARAGRAPHS) { i ->
                doc.createParagraph().createRun().setText(
                    "Paragraph ${i + 1}. The borrower asks to renew a BDT 40 million working capital line for twelve months, " +
                        "secured by a mortgage over its warehouse and a lien on receivables.",
                )
            }
            val table = doc.createTable(TABLE_ROWS, TABLE_COLUMNS)
            table.getRow(0).getCell(0).text = "Facility"
            table.getRow(0).getCell(1).text = "Limit (BDT)"
            table.getRow(0).getCell(2).text = "Tenor"
            table.getRow(1).getCell(0).text = "Working capital"
            table.getRow(1).getCell(1).text = "40,000,000"
            table.getRow(1).getCell(2).text = "12 months"
            val png = ByteArrayOutputStream().also { ImageIO.write(picture(), "png", it) }.toByteArray()
            doc.createParagraph().createRun().addPicture(
                ByteArrayInputStream(png),
                PictureType.PNG,
                "chart.png",
                Units.toEMU(PICTURE_POINTS),
                Units.toEMU(PICTURE_POINTS / 2.0),
            )
            ByteArrayOutputStream().also { doc.write(it) }.toByteArray()
        }

    private fun picture(): BufferedImage =
        BufferedImage(PICTURE_PIXELS, PICTURE_PIXELS / 2, BufferedImage.TYPE_INT_ARGB).apply {
            val g = createGraphics()
            g.color = Color(0, 0, 0, 0)
            g.fillRect(0, 0, width, height)
            g.color = Color(30, 90, 160, 160)
            g.fillOval(10, 10, width - 20, height - 20)
            g.dispose()
        }

    private fun spreadsheet(): ByteArray =
        XSSFWorkbook().use { book ->
            val sheet = book.createSheet("Figures")
            repeat(TABLE_ROWS * 10) { r ->
                val row = sheet.createRow(r)
                row.createCell(0).setCellValue("Month ${r + 1}")
                row.createCell(1).setCellValue((r + 1) * 1_250_000.0)
            }
            ByteArrayOutputStream().also { book.write(it) }.toByteArray()
        }

    private fun presentation(): ByteArray =
        XMLSlideShow().use { show ->
            repeat(TABLE_ROWS) { i ->
                val slide = show.createSlide()
                slide.createTextBox().apply {
                    anchor = java.awt.Rectangle(50, 50, 600, 100)
                    text = "Slide ${i + 1}: credit line renewal"
                }
            }
            ByteArrayOutputStream().also { show.write(it) }.toByteArray()
        }

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
        const val PARAGRAPHS = 40
        const val TABLE_ROWS = 3
        const val TABLE_COLUMNS = 3
        const val PICTURE_PIXELS = 400
        const val PICTURE_POINTS = 300.0
    }
}
