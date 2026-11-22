package com.memo.templating.internal

import com.memo.templating.TagKind
import com.memo.templating.TagVocabulary
import com.memo.templating.TemplateData
import com.memo.templating.TemplateProblemCode
import com.memo.testsupport.Docx
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * Row-level controls (E06 T7): the rows of a loop with row tags are each wrapped in an unlocked row-level control of their
 * tag, with no marker left in the text; a copy drops the rows whose tags it names and keeps the others; and a deprecated
 * tag is a warning, never a problem.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RowControlsTest {
    private val filler = PoiTlFiller()
    private val controls = PoiDocxControls()
    private val copies = PoiDocxCopies()

    @AfterAll
    fun stop() = filler.destroy()

    @Test
    fun `each tagged row becomes a row-level control, and a copy drops the rows it names`() {
        val template =
            Docx.document { d ->
                d.createParagraph().createRun().setText("Resolutions of {{meeting.label}}")
                val table = d.createTable(2, 2)
                listOf(listOf("Number{{resolutions}}", "Text"), listOf("[number]", "[text]")).forEachIndexed { r, cells ->
                    cells.forEachIndexed { c, text ->
                        table
                            .getRow(r)
                            .getCell(c)
                            .paragraphs
                            .first()
                            .createRun()
                            .setText(text)
                    }
                }
            }
        val data =
            TemplateData(
                values = mapOf("meeting.label" to "BRC 3/2026"),
                rows =
                    mapOf(
                        "resolutions" to
                            listOf(
                                mapOf("number" to "BRC/2026/001", "text" to "The loan is approved."),
                                mapOf("number" to "BRC/2026/002", "text" to "The renewal is approved."),
                                mapOf("number" to "BRC/2026/003", "text" to "The report is noted."),
                            ),
                    ),
                rowTags = mapOf("resolutions" to listOf("memo-row:a", "memo-row:b", null)),
            )
        val filled = filler.fill(template.inputStream(), data)

        val found = controls.scan(filled.inputStream())
        assertThat(found.map { it.tag to it.bodyLevel }).containsExactly("memo-row:a" to false, "memo-row:b" to false)
        assertThat(found).allMatch { it.lock == null }
        val text = Docx.text(filled)
        assertThat(text).contains("BRC/2026/001", "The renewal is approved.", "BRC/2026/003").doesNotContain("memo-row", "[")

        val copy = copies.dropRows(filled, setOf("memo-row:a"))
        assertThat(Docx.text(copy)).doesNotContain("BRC/2026/001", "The loan is approved.").contains("BRC/2026/002", "BRC/2026/003")
        assertThat(controls.scan(copy.inputStream()).map { it.tag }).containsExactly("memo-row:b")
        assertThat(copies.dropRows(filled, emptySet())).isEqualTo(filled)
    }

    @Test
    fun `a deprecated tag is a warning with its note, not a problem`() {
        val templates = DocxTemplatesImpl()
        val template = Docx.paragraphs(listOf("Status: {{minutes.status}}"), listOf("{{meeting.label}}"))
        val scan = templates.scan { SeekableInMemoryByteChannel(template) }
        val vocabulary =
            TagVocabulary(
                values = setOf("minutes.status", "meeting.label"),
                sections = emptySet(),
                loops = emptyMap(),
                deprecated = mapOf("minutes.status" to "Always empty now."),
            )
        assertThat(templates.check(scan, vocabulary)).isEmpty()
        val warnings = templates.warnings(scan, vocabulary)
        assertThat(warnings.map { Triple(it.code, it.tag, it.note) })
            .containsExactly(Triple(TemplateProblemCode.DEPRECATED_TAG, "minutes.status", "Always empty now."))
        assertThat(scan.tags.single { it.name == "minutes.status" }.kind).isEqualTo(TagKind.VALUE)
    }
}
