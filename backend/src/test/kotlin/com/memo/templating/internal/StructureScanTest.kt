package com.memo.templating.internal

import com.memo.templating.ContentControlException
import com.memo.templating.DocumentMarks
import com.memo.templating.FoundControl
import com.memo.testsupport.Docx
import com.memo.testsupport.MinutesSample
import org.apache.poi.xwpf.usermodel.XWPFDocument
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * Reading the item controls by StAX (ADR-0066, design §9 budget: under 2 s on a 200-page file), and what the structure
 * check will see when a control is missing, duplicated, unknown or nested.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StructureScanTest {
    private val filler = PoiTlFiller()
    private val controls = PoiDocxControls()

    @AfterAll
    fun stop() = filler.destroy()

    @Test
    fun `a 200-page minutes file is scanned in well under two seconds`() {
        val data = MinutesSample.data(items = ITEMS, notes = { MinutesSample.prose(NOTES_WORDS, it) })
        val docx = filler.fill(MinutesSample.template().inputStream(), data)
        val pages = XWPFDocument(docx.inputStream()).use { it.properties.extendedProperties.pages }
        val words = Docx.text(docx).split(Regex("\\s+")).size
        controls.scan(docx.inputStream())
        val start = System.nanoTime()
        val found = controls.scan(docx.inputStream())
        val millis = (System.nanoTime() - start) / NANOS_PER_MILLI
        println("StructureScanTest: $words words (about ${words / WORDS_PER_PAGE} pages; POI says $pages), ${docx.size} bytes, $millis ms")
        assertThat(words / WORDS_PER_PAGE).isGreaterThanOrEqualTo(MIN_PAGES)
        assertThat(found).hasSize(ITEMS).allMatch { it.bodyLevel && it.lock == "sdtLocked" }
        assertThat(millis).isLessThan(BUDGET_MILLIS)
    }

    @Test
    fun `missing, duplicated, unknown and nested controls are all visible to the check`() {
        val draft = filler.fill(MinutesSample.template().inputStream(), MinutesSample.data(items = 3))
        val tag2 = MinutesSample.tag(2)
        val changed =
            Docx.editText(draft, "word/document.xml") { xml ->
                val at = xml.indexOf("""w:val="$tag2"""")
                val sdt = xml.substring(xml.lastIndexOf("<w:sdt>", at), xml.indexOf("</w:sdt>", at) + "</w:sdt>".length)
                val unknown = sdt.replace(tag2, "memo-item:unknown")
                val inline =
                    """<w:p><w:sdt><w:sdtPr><w:tag w:val="inline"/></w:sdtPr>""" +
                        """<w:sdtContent><w:r><w:t>x</w:t></w:r></w:sdtContent></w:sdt></w:p>"""
                xml.replace(sdt, sdt + sdt + unknown + inline)
            }
        val found = controls.scan(changed.inputStream())
        assertThat(found.map { it.tag }).containsExactly(
            MinutesSample.tag(1),
            tag2,
            tag2,
            "memo-item:unknown",
            "inline",
            MinutesSample.tag(3),
        )
        assertThat(found.first { it.tag == "inline" }).isEqualTo(FoundControl("inline", null, null, bodyLevel = false))
    }

    @Test
    fun `entities and DTDs are not resolved`() {
        val draft = filler.fill(MinutesSample.template().inputStream(), MinutesSample.data(items = 1))
        val hostile =
            Docx.editText(draft, "word/document.xml") { xml ->
                xml.replaceFirst("?>", "?><!DOCTYPE d [<!ENTITY x SYSTEM \"file:///etc/passwd\">]>")
            }
        val found = runCatching { controls.scan(hostile.inputStream()) }
        assertThat(found.getOrNull()?.map { it.tag } ?: listOf("refused")).isIn(listOf(MinutesSample.tag(1)), listOf("refused"))
    }

    @Test
    fun `refill replaces one control from a fresh fill in place and keeps everything else`() {
        val draft = filler.fill(MinutesSample.template().inputStream(), MinutesSample.data(items = 3))
        val edited =
            Docx.editText(draft, "word/document.xml") { xml ->
                xml
                    .replace(
                        "The committee noted the paper on item 2.",
                        "EDITED-2",
                    ).replace("The committee noted the paper on item 3.", "EDITED-3")
            }
        val fresh = filler.fill(MinutesSample.template().inputStream(), MinutesSample.data(items = 3, notes = { "Fresh notes on $it." }))
        val refilled = controls.refill(edited.inputStream(), fresh.inputStream(), MinutesSample.tag(2))
        assertThat(controls.scan(refilled.inputStream()).map { it.tag to it.lock })
            .containsExactly(*(1..3).map { MinutesSample.tag(it) to "sdtLocked" }.toTypedArray())
        assertThat(Docx.text(refilled)).contains("Fresh notes on 2.", "EDITED-3").doesNotContain("EDITED-2", "Fresh notes on 3.")
        val duplicated =
            Docx.editText(edited, "word/document.xml") { xml ->
                val at = xml.indexOf("""w:val="${MinutesSample.tag(2)}"""")
                val sdt = xml.substring(xml.lastIndexOf("<w:sdt>", at), xml.indexOf("</w:sdt>", at) + "</w:sdt>".length)
                xml.replace(sdt, sdt + sdt)
            }
        assertThat(runCatching { controls.refill(duplicated.inputStream(), fresh.inputStream(), MinutesSample.tag(2)) }.exceptionOrNull())
            .isInstanceOf(ContentControlException::class.java)
    }

    @Test
    fun `tracked changes and comments are counted, a clean draft has none`() {
        val draft = filler.fill(MinutesSample.template().inputStream(), MinutesSample.data(items = 2))
        assertThat(controls.marks(draft.inputStream()).clean).isTrue()
        val reviewed =
            Docx.editText(draft, "word/document.xml") { xml ->
                val marks =
                    """<w:p><w:ins w:id="91" w:author="Chair"><w:r><w:t>x</w:t></w:r></w:ins>""" +
                        """<w:del w:id="92" w:author="Chair"><w:r><w:delText>y</w:delText></w:r></w:del>""" +
                        """<w:r><w:commentReference w:id="1"/></w:r></w:p>"""
                xml.replaceFirst("<w:body>", "<w:body>$marks")
            }
        assertThat(controls.marks(reviewed.inputStream())).isEqualTo(DocumentMarks(trackedChanges = 2, comments = 1))
    }

    private companion object {
        const val ITEMS = 60
        const val NOTES_WORDS = 1_700
        const val WORDS_PER_PAGE = 500
        const val MIN_PAGES = 200
        const val BUDGET_MILLIS = 2_000L
        const val NANOS_PER_MILLI = 1_000_000L
    }
}
