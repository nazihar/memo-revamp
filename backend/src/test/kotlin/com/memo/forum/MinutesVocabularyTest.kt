package com.memo.forum

import com.memo.forum.internal.MinutesTemplateSample
import com.memo.templating.FillFailure
import com.memo.templating.TemplateFillException
import com.memo.templating.TemplateProblem
import com.memo.templating.TemplateProblemCode
import com.memo.templating.internal.DocxTemplatesImpl
import com.memo.templating.internal.PoiTlFiller
import com.memo.testsupport.Docx
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel
import org.apache.poi.wp.usermodel.HeaderFooterType
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.LocalDate

/**
 * The minutes vocabulary (design E06 §1.7; test plan T2 `MinutesVocabularyTest`): item tags inside the items block only,
 * the meeting's values everywhere, suggestions (also from the names admins commonly type), and the block's placement
 * that the filler needs: each end alone in a paragraph of the body, once, never inside another block.
 */
class MinutesVocabularyTest {
    private val templates = DocxTemplatesImpl()

    private fun check(docx: ByteArray): List<TemplateProblem> =
        templates.check(templates.scan { SeekableInMemoryByteChannel(docx) }, MinutesTemplateTags.vocabulary)

    private fun items(vararg body: String): ByteArray =
        Docx.paragraphs(
            listOf("Minutes of {{meeting.label}}"),
            listOf("{{?items}}"),
            *body.map { listOf(it) }.toTypedArray(),
            listOf("{{/items}}"),
        )

    @Test
    fun `item tags and the meeting's values are known inside the block, with sections on item values`() {
        assertThat(
            check(items("{{item.number}}. {{item.title}} ({{meeting.label}})", "{{?item.notes}}", "{{item.notes}}", "{{/item.notes}}")),
        ).isEmpty()
    }

    @Test
    fun `a misspelt item tag is unknown and suggests the tag it means, also from a common word`() {
        val problems = check(items("Key points: {{item.discusion}}", "{{item.decison}}", "{{item.titel}}"))
        assertThat(problems.map { it.code }).containsOnly(TemplateProblemCode.UNKNOWN_TAG)
        assertThat(problems.map { it.tag to it.suggestion }).containsExactly(
            "{{item.discusion}}" to "{{item.notes}}",
            "{{item.decison}}" to "{{item.outcome}}",
            "{{item.titel}}" to "{{item.title}}",
        )
        assertThat(problems[0].location.paragraph).isEqualTo(3)
    }

    @Test
    fun `an item tag outside the block is unknown, and so is a meeting section inside it`() {
        val outside = check(Docx.paragraphs(listOf("{{item.title}}")))
        assertThat(outside.map { it.code to it.tag }).containsExactly(TemplateProblemCode.UNKNOWN_TAG to "{{item.title}}")
        val inside = check(items("{{?meeting.place}}", "{{meeting.place}}", "{{/meeting.place}}"))
        assertThat(inside.map { it.code to it.tag }).containsExactly(TemplateProblemCode.UNKNOWN_TAG to "{{?meeting.place}}")
        assertThat(check(Docx.paragraphs(listOf("{{?meeting.place}}"), listOf("At {{meeting.place}}"), listOf("{{/meeting.place}}"))))
            .isEmpty()
    }

    @Test
    fun `each end of the block must be alone in a paragraph of the body`() {
        val sharing = check(Docx.paragraphs(listOf("Items: {{?items}}"), listOf("{{item.title}}"), listOf("{{/items}}")))
        assertThat(sharing.map { it.code to it.tag }).containsExactly(TemplateProblemCode.BLOCK_PLACEMENT to "{{?items}}")

        val onePara = check(Docx.paragraphs(listOf("{{?items}}{{item.title}}{{/items}}")))
        assertThat(onePara.map { it.code }).containsExactly(TemplateProblemCode.BLOCK_PLACEMENT, TemplateProblemCode.BLOCK_PLACEMENT)

        val table =
            Docx.document { d ->
                val t = d.createTable(3, 1)
                t.getRow(0).getCell(0).text = "{{?items}}"
                t.getRow(1).getCell(0).text = "{{item.title}}"
                t.getRow(2).getCell(0).text = "{{/items}}"
            }
        assertThat(check(table).map { it.code }).contains(TemplateProblemCode.BLOCK_PLACEMENT)

        val header =
            Docx.document { d ->
                val h = d.createHeader(HeaderFooterType.DEFAULT)
                h.createParagraph().createRun().setText("{{?items}}")
                h.createParagraph().createRun().setText("{{item.title}}")
                h.createParagraph().createRun().setText("{{/items}}")
            }
        assertThat(check(header).map { it.code }).contains(TemplateProblemCode.BLOCK_PLACEMENT)
    }

    @Test
    fun `the block is used once and never inside another`() {
        val repeated =
            check(
                Docx.paragraphs(
                    listOf("{{?items}}"),
                    listOf("{{item.title}}"),
                    listOf("{{/items}}"),
                    listOf("{{?items}}"),
                    listOf("{{item.number}}"),
                    listOf("{{/items}}"),
                ),
            )
        assertThat(repeated.map { it.code to it.location.paragraph }).containsExactly(TemplateProblemCode.BLOCK_PLACEMENT to 4)
        val nested = check(items("{{?items}}", "{{item.title}}", "{{/items}}"))
        assertThat(nested.map { it.code }).contains(TemplateProblemCode.BLOCK_PLACEMENT)
    }

    @Test
    fun `a block the scanner refuses is one the filler cannot repeat`() {
        val table =
            Docx.document { d ->
                val t = d.createTable(3, 1)
                t.getRow(0).getCell(0).text = "{{?items}}"
                t.getRow(1).getCell(0).text = "{{item.title}}"
                t.getRow(2).getCell(0).text = "{{/items}}"
            }
        val filler = PoiTlFiller()
        try {
            assertThatThrownBy { filler.fill(table.inputStream(), MinutesTemplateSample.data(LocalDate.of(2026, 11, 19))) }
                .isInstanceOfSatisfying(
                    TemplateFillException::class.java,
                ) { assertThat(it.failure).isEqualTo(FillFailure.INVALID_TEMPLATE) }
        } finally {
            filler.destroy()
        }
    }

    @Test
    fun `loops are tables of known fields, and an unknown loop field suggests the right one`() {
        val docx =
            Docx.document { d ->
                val t = d.createTable(2, 2)
                t.getRow(0).getCell(0).text = "Present{{present}}"
                t.getRow(0).getCell(1).text = "Role"
                t.getRow(1).getCell(0).text = "[name]"
                t.getRow(1).getCell(1).text = "[rol]"
            }
        val problems = check(docx)
        assertThat(problems.map { it.code to it.suggestion }).containsExactly(TemplateProblemCode.UNKNOWN_ROW_FIELD to "[role]")
    }
}
