package com.memo.docedit.internal

import com.memo.files.FileType
import com.memo.files.RenditionInput
import com.memo.files.RenditionOutcome
import com.memo.templating.ControlParagraph
import com.memo.templating.FoundControl
import com.memo.templating.internal.PoiDocxControls
import com.memo.templating.internal.PoiTlFiller
import com.memo.testsupport.Docx
import com.memo.testsupport.MinutesSample
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.verapdf.gf.foundry.VeraGreenfieldFoundryProvider
import org.verapdf.pdfa.Foundries
import org.verapdf.pdfa.flavours.PDFAFlavour
import tools.jackson.databind.json.JsonMapper
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.util.UUID
import kotlin.io.path.exists
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes

/**
 * The E06 T1 spike against a real document server (ADR-0066; results in the E06 log). Three steps, each run when its
 * input is there:
 * 1. writes the nine-item draft (`MEMO_SPIKE_OUT/draft.docx`) for the browser round trip
 *    (`web/e2e/spike/minutes-controls.spec.ts`, which drives ONLYOFFICE 10.0.0.1's editor and saves through the callback);
 * 2. checks what that round trip saved (`saved-edit.docx`, `saved-reopened.docx`, `saved-review.docx`): nine controls
 *    with their tags and locks, item 5's edit inside its control, tracked changes from review mode;
 * 3. replaces item 5 in the saved file and converts it to PDF and PDF/A with ONLYOFFICE (`MEMO_SPIKE_DOCSERVER`,
 *    `MEMO_SPIKE_JWT`): no word of item 5's discussion in the text layer, veraPDF's verdict on the PDF/A.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "MEMO_SPIKE_OUT", matches = ".+")
class MinutesControlsRoundTripIT {
    private val out: Path = Path.of(System.getenv("MEMO_SPIKE_OUT"))
    private val filler = PoiTlFiller()
    private val controls = PoiDocxControls()

    @BeforeAll
    fun setUp() {
        Files.createDirectories(out)
        VeraGreenfieldFoundryProvider.initialise()
    }

    @AfterAll
    fun stop() = filler.destroy()

    private val expected = (1..9).map { FoundControl(MinutesSample.tag(it), "sdtLocked", "Item $it", bodyLevel = true) }

    @Test
    fun `1 the draft for the editor has nine locked, tagged controls`() {
        val draft = filler.fill(MinutesSample.template().inputStream(), MinutesSample.data())
        out.resolve("draft.docx").writeBytes(draft)
        assertThat(controls.scan(draft.inputStream())).containsExactlyElementsOf(expected)
    }

    @Test
    fun `2 what ONLYOFFICE saved after edits, a deletion attempt and undo keeps the controls`() {
        val saved = out.resolve("saved-edit.docx")
        assumeTrue(saved.exists(), "run the browser round trip first")
        listOf("saved-edit.docx", "saved-reopened.docx", "saved-review.docx")
            .map(out::resolve)
            .filter { it.exists() }
            .forEach { file ->
                val found = controls.scan(file.readBytes().inputStream())
                val xml = Docx.entries(file.readBytes()).getValue("word/document.xml").decodeToString()
                println("MinutesControlsRoundTripIT ${file.fileName}: $found")
                println(
                    "MinutesControlsRoundTripIT ${file.fileName}: ins=${Regex("<w:ins ").findAll(xml).count()} " +
                        "del=${Regex("<w:del ").findAll(xml).count()} sdtContent text of item 5 = " +
                        MinutesSample.controlTexts(file.readBytes())[MinutesSample.tag(5)],
                )
            }
        val found = controls.scan(saved.readBytes().inputStream())
        assertThat(found.map { it.tag }).containsExactlyElementsOf(expected.map { it.tag })
        assertThat(found).allMatch { it.lock == "sdtLocked" && it.bodyLevel }
        assertThat(MinutesSample.controlTexts(saved.readBytes()).getValue(MinutesSample.tag(5))).contains("EDITED-INSIDE-5")
    }

    @Test
    fun `3 a copy with item 5 replaced has none of its discussion in its PDF or PDF-A text`() {
        val server = System.getenv("MEMO_SPIKE_DOCSERVER").orEmpty()
        assumeTrue(server.isNotBlank(), "needs a document server")
        val converter =
            OnlyOfficeConverter(
                DocEditProperties(documentServerUrl = server, jwtSecret = System.getenv("MEMO_SPIKE_JWT")),
                JsonMapper.builder().build(),
                Clock.systemUTC(),
            )
        val source = out.resolve("saved-edit.docx").takeIf { it.exists() }?.readBytes() ?: out.resolve("draft.docx").readBytes()
        val copy =
            controls.replace(
                source.inputStream(),
                mapOf(
                    MinutesSample.tag(5) to
                        listOf(
                            ControlParagraph("5. Agenda item 5", bold = true),
                            ControlParagraph("You declared an interest in this item and took no part."),
                            ControlParagraph("APPROVED"),
                        ),
                ),
            )
        out.resolve("copy-item5-replaced.docx").writeBytes(copy)
        val full = text(convert(converter, source, pdfa = false))
        assertThat(full).contains(MinutesSample.CANARY)
        val pdf = convert(converter, copy, pdfa = false)
        val pdfa = convert(converter, copy, pdfa = true)
        out.resolve("copy.pdf").writeBytes(pdf)
        out.resolve("copy-pdfa.pdf").writeBytes(pdfa)
        listOf(pdf, pdfa).forEach { bytes ->
            assertThat(text(bytes)).doesNotContain(MinutesSample.CANARY, "borrower").contains("took no part", "Agenda item 6")
        }
        println("MinutesControlsRoundTripIT PDF/A: ${verdict(pdfa)}")
    }

    private fun convert(
        converter: OnlyOfficeConverter,
        docx: ByteArray,
        pdfa: Boolean,
    ): ByteArray {
        val input = RenditionInput("minutes.docx", FileType.DOCX, docx.size.toLong()) { ByteArrayInputStream(docx) }
        val bytes = ByteArrayOutputStream()
        val key = "spike-${UUID.randomUUID()}"
        val outcome = if (pdfa) converter.toPdfA(input, key, bytes) else converter.toPdf(input, key, bytes)
        assertThat(outcome).isEqualTo(RenditionOutcome.Written())
        return bytes.toByteArray()
    }

    private fun text(pdf: ByteArray): String = Loader.loadPDF(pdf).use { PDFTextStripper().getText(it) }

    private fun verdict(pdf: ByteArray): String =
        Foundries.defaultInstance().use { foundry ->
            foundry.createParser(ByteArrayInputStream(pdf), PDFAFlavour.fromString("2u")).use { parser ->
                val result = foundry.createValidator(PDFAFlavour.fromString("2u"), false).validate(parser)
                "PDF/A-2u compliant ${result.isCompliant}"
            }
        }
}
