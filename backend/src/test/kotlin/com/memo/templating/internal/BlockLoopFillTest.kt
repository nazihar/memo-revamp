package com.memo.templating.internal

import com.memo.templating.ControlMark
import com.memo.templating.FillFailure
import com.memo.templating.TemplateData
import com.memo.templating.TemplateFillException
import com.memo.testsupport.Docx
import com.memo.testsupport.MinutesSample
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowableOfType
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * Block loops (ADR-0066, test plan E06 T1): a `{{?items}}…{{/items}}` section over a list repeats per element, with value
 * tags, a nested section and a table-row loop resolved against the element, and the document's values still reachable.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BlockLoopFillTest {
    private val filler = PoiTlFiller()

    @AfterAll
    fun stop() = filler.destroy()

    private fun fill(
        data: TemplateData,
        template: ByteArray = MinutesSample.template(),
    ) = filler.fill(template.inputStream(), data)

    @Test
    fun `each item gets its own block, in order, with its section, its vote rows and the document's values`() {
        val text = Docx.text(fill(MinutesSample.data(controls = false)))
        val headings = (1..9).map { "$it. Agenda item $it" }
        assertThat(headings.map { text.indexOf(it) }).allMatch { it >= 0 }.isSorted
        assertThat(text).contains(
            "Demo Director declared an interest and left the meeting for this item.",
            "The committee noted the paper on item 9.",
            "For\t10",
            "Against\t0",
            "APPROVED (BRC)",
            "NOTED (BRC)",
            "Demo Chair\tChair",
            "the meeting closed at 12:40.",
        )
        assertThat(Regex("declared an interest").findAll(text).count()).isEqualTo(1)
        assertThat(Regex("Key points discussed included:").findAll(text).count()).isEqualTo(9)
        assertThat(text).doesNotContain("{{", "}}", "[side]", "memoControl")
    }

    @Test
    fun `an empty block leaves nothing, and a block without controls makes no control`() {
        val none = fill(MinutesSample.data(items = 0))
        assertThat(Docx.text(none)).doesNotContain("Agenda item", "Key points")
        assertThat(Docx.entries(none).getValue("word/document.xml").decodeToString()).doesNotContain("<w:sdt")
        val plain = fill(MinutesSample.data(controls = false))
        assertThat(Docx.entries(plain).getValue("word/document.xml").decodeToString()).doesNotContain("<w:sdt")
    }

    @Test
    fun `a value missing from an element fails, and blocks are one level deep`() {
        val data = MinutesSample.data()
        val broken =
            data.blocks.getValue("items").mapIndexed { i, item ->
                if (i == 3) item.copy(values = item.values - "item.notes") else item
            }
        assertThat(failure(data.copy(blocks = mapOf("items" to broken)))).isEqualTo(FillFailure.MISSING_VALUE)
        val nested =
            Docx.paragraphs(
                *listOf("{{?items}}", "{{?items}}", "{{item.title}}", "{{/items}}", "{{/items}}").map { listOf(it) }.toTypedArray(),
            )
        val plain = data.copy(blocks = mapOf("items" to data.blocks.getValue("items").map { it.copy(control = null) }))
        assertThat(failure(plain, nested))
            .isIn(FillFailure.MISSING_VALUE, FillFailure.INVALID_TEMPLATE)
    }

    @Test
    fun `values that look like tags or markers stay text and make no control`() {
        val data = MinutesSample.data()
        val hostile =
            data.blocks.getValue("items").map {
                it.copy(values = it.values + ("item.notes" to "{{memoControl.start}} {{/items}} <w:sdt/> [side]"))
            }
        val docx = fill(data.copy(blocks = mapOf("items" to hostile)))
        assertThat(Docx.text(docx)).contains("{{memoControl.start}} {{/items}} <w:sdt/> [side]")
        assertThat(PoiDocxControls().scan(docx.inputStream())).hasSize(9)
    }

    @Test
    fun `a block with controls must start and end in paragraphs of the body`() {
        val inTable =
            Docx.document { d ->
                val table = d.createTable(3, 1)
                table.getRow(0).getCell(0).text = "{{?items}}"
                table.getRow(1).getCell(0).text = "{{item.title}}"
                table.getRow(2).getCell(0).text = "{{/items}}"
            }
        val data = TemplateData(emptyMap(), blocks = mapOf("items" to listOf(item(1), item(2))))
        assertThat(failure(data, inTable)).isEqualTo(FillFailure.INVALID_TEMPLATE)
        val sameParagraph = Docx.paragraphs(listOf("{{?items}}{{item.title}}{{/items}}"))
        assertThat(failure(data, sameParagraph)).isEqualTo(FillFailure.INVALID_TEMPLATE)
    }

    @Test
    fun `sixty items with long notes fill within the default time limit`() {
        val start = System.nanoTime()
        val docx = fill(MinutesSample.data(items = SIXTY, notes = { MinutesSample.prose(LONG_NOTES, it) }))
        val millis = (System.nanoTime() - start) / NANOS_PER_MILLI
        println("BlockLoopFillTest: 60 items in $millis ms, ${docx.size} bytes")
        assertThat(PoiDocxControls().scan(docx.inputStream())).hasSize(SIXTY)
        assertThat(millis).isLessThan(PoiTlFiller.DEFAULT_TIMEOUT.toMillis())
    }

    private fun item(n: Int) = TemplateData(mapOf("item.title" to "Item $n"), control = ControlMark("memo-item:$n", "Item $n"))

    private fun failure(
        data: TemplateData,
        template: ByteArray = MinutesSample.template(),
    ): FillFailure = catchThrowableOfType(TemplateFillException::class.java) { fill(data, template) }.failure

    private companion object {
        const val SIXTY = 60
        const val LONG_NOTES = 600
        const val NANOS_PER_MILLI = 1_000_000L
    }
}
