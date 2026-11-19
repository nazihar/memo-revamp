package com.memo.templating.internal

import com.memo.templating.ContentControlException
import com.memo.templating.ControlParagraph
import com.memo.templating.FoundControl
import com.memo.testsupport.Docx
import com.memo.testsupport.MinutesSample
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * Item controls (ADR-0066, test plan E06 T1): one locked, tagged block-level control per item; replacing one control's
 * content leaves nothing of it; a missing control is restored after the previous one.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ContentControlWrapperTest {
    private val filler = PoiTlFiller()
    private val controls = PoiDocxControls()
    private val draft = filler.fill(MinutesSample.template().inputStream(), MinutesSample.data())

    @AfterAll
    fun stop() = filler.destroy()

    @Test
    fun `each item's block is one locked, tagged rich-text control in the body, holding that item only`() {
        assertThat(controls.scan(draft.inputStream())).containsExactlyElementsOf(
            (1..9).map { FoundControl(MinutesSample.tag(it), "sdtLocked", "Item $it", bodyLevel = true) },
        )
        val texts = MinutesSample.controlTexts(draft)
        assertThat(texts.getValue(MinutesSample.tag(5))).contains("5. Agenda item 5", MinutesSample.CANARY, "declared an interest", "For")
        assertThat(texts.getValue(MinutesSample.tag(6))).contains("6. Agenda item 6").doesNotContain("Agenda item 5", "Agenda item 7")
        val xml = Docx.entries(draft).getValue("word/document.xml").decodeToString()
        assertThat(Regex("<w:sdt>").findAll(xml).count()).isEqualTo(9)
        assertThat(xml).doesNotContain("<w:text", "<w:dropDownList", "<w:comboBox")
        // The heading and closing lines stay outside the controls.
        val body = Docx.text(draft)
        assertThat(body).startsWith("Minutes of the Board Risk Committee 4/2026").contains("the meeting closed at 12:40.")
        assertThat(texts.values.joinToString()).doesNotContain("Minutes of the", "meeting closed")
    }

    @Test
    fun `replacing item 5 keeps its control and leaves no word of its discussion`() {
        val copy =
            controls.replace(
                draft.inputStream(),
                mapOf(
                    MinutesSample.tag(5) to
                        listOf(
                            ControlParagraph("5. Agenda item 5", bold = true),
                            ControlParagraph("You declared an interest in this item and took no part."),
                            ControlParagraph("Outcome: APPROVED"),
                        ),
                ),
            )
        assertThat(controls.scan(copy.inputStream())).hasSize(9).allMatch { it.lock == "sdtLocked" }
        assertThat(Docx.text(copy)).doesNotContain(MinutesSample.CANARY, "borrower's exposure").contains("took no part")
        val texts = MinutesSample.controlTexts(copy)
        assertThat(texts.getValue(MinutesSample.tag(6))).isEqualTo(MinutesSample.controlTexts(draft).getValue(MinutesSample.tag(6)))
        assertThat(Docx.entries(copy).values.joinToString { it.decodeToString() }).doesNotContain(MinutesSample.CANARY)
    }

    @Test
    fun `a deleted control is restored after the previous item from a new fill`() {
        val withoutSix =
            Docx.editText(draft, "word/document.xml") { xml ->
                val at = xml.indexOf("""w:val="${MinutesSample.tag(6)}"""")
                val start = xml.lastIndexOf("<w:sdt>", at)
                val end = xml.indexOf("</w:sdt>", at) + "</w:sdt>".length
                xml.removeRange(start, end)
            }
        assertThat(controls.scan(withoutSix.inputStream()).map { it.tag }).doesNotContain(MinutesSample.tag(6)).hasSize(8)
        val again = filler.fill(MinutesSample.template().inputStream(), MinutesSample.data())
        val restored = controls.restore(withoutSix.inputStream(), again.inputStream(), MinutesSample.tag(6), MinutesSample.tag(5))
        assertThat(controls.scan(restored.inputStream()).map { it.tag }).containsExactlyElementsOf((1..9).map(MinutesSample::tag))
        assertThat(MinutesSample.controlTexts(restored).getValue(MinutesSample.tag(6))).contains("6. Agenda item 6")
        val first = controls.restore(dropFirst(), again.inputStream(), MinutesSample.tag(1), null)
        assertThat(controls.scan(first.inputStream()).map { it.tag }).containsExactlyElementsOf((1..9).map(MinutesSample::tag))
    }

    @Test
    fun `the draft saved by Microsoft Word keeps every control with its tag and lock`() {
        // Golden file (E06 T1): this draft opened in Word, `ContentControl.Delete` and a range delete around item 6 both
        // refused ("This content control is locked and cannot be deleted"), a line typed in item 5, saved as .docx with
        // personal information removed.
        val word = checkNotNull(javaClass.getResourceAsStream("/templating/minutes-saved-by-word.docx")).use { it.readBytes() }
        assertThat(controls.scan(word.inputStream())).containsExactlyElementsOf(
            (1..9).map { FoundControl(MinutesSample.tag(it), "sdtLocked", "Item $it", bodyLevel = true) },
        )
        assertThat(MinutesSample.controlTexts(word).getValue(MinutesSample.tag(5))).contains("EDITED-IN-WORD", MinutesSample.CANARY)
        val copy = controls.replace(word.inputStream(), mapOf(MinutesSample.tag(5) to listOf(ControlParagraph("Item 5 — restricted"))))
        assertThat(Docx.text(copy)).doesNotContain(MinutesSample.CANARY, "EDITED-IN-WORD")
    }

    @Test
    fun `unknown tags, a control already there and unreadable files are refused`() {
        assertThatThrownBy { controls.replace(draft.inputStream(), mapOf("memo-item:nope" to emptyList())) }
            .isInstanceOf(ContentControlException::class.java)
        assertThatThrownBy { controls.restore(draft.inputStream(), draft.inputStream(), MinutesSample.tag(6), MinutesSample.tag(5)) }
            .isInstanceOf(ContentControlException::class.java)
        assertThatThrownBy { controls.scan("not a document".byteInputStream()) }.isInstanceOf(ContentControlException::class.java)
        assertThatThrownBy { controls.replace("not a document".byteInputStream(), emptyMap()) }
            .isInstanceOf(ContentControlException::class.java)
        val linked =
            Docx.editText(draft, "word/document.xml") { xml ->
                xml.replace(
                    "Agenda item 6</w:t></w:r>",
                    "Agenda item 6</w:t></w:r><w:hyperlink r:id=\"rId99\"><w:r><w:t>link</w:t></w:r></w:hyperlink>",
                )
            }
        assertThatThrownBy { controls.restore(dropSix(), linked.inputStream(), MinutesSample.tag(6), MinutesSample.tag(5)) }
            .isInstanceOf(ContentControlException::class.java)
    }

    private fun dropFirst() = drop(1)

    private fun dropSix() = drop(6)

    private fun drop(n: Int) =
        Docx
            .editText(draft, "word/document.xml") { xml ->
                val at = xml.indexOf("""w:val="${MinutesSample.tag(n)}"""")
                xml.removeRange(xml.lastIndexOf("<w:sdt>", at), xml.indexOf("</w:sdt>", at) + "</w:sdt>".length)
            }.inputStream()
}
