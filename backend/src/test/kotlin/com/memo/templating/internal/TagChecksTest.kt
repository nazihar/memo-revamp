package com.memo.templating.internal

import com.memo.templating.TagVocabulary
import com.memo.templating.TemplateProblemCode
import com.memo.testsupport.Docx
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** ADR-0048, test plan E03 T1: tags checked against what a kind of document offers, with locations and suggestions. */
class TagChecksTest {
    private val vocabulary =
        TagVocabulary(
            values =
                setOf(
                    "memo.number",
                    "memo.date",
                    "memo.subject",
                    "initiator.name",
                    "initiator.position",
                    "meta.amount",
                    "meta.borrower",
                ),
            sections = setOf("meta.amount", "meta.borrower"),
            loops = mapOf("approvals" to setOf("name", "position", "decision", "decidedAt")),
        )
    private val templates = DocxTemplatesImpl()

    private fun check(docx: ByteArray) = templates.check(templates.scan { SeekableInMemoryByteChannel(docx) }, vocabulary)

    @Test
    fun `the letterhead has no problems`() {
        assertThat(check(Docx.letterhead())).isEmpty()
    }

    @Test
    fun `an unknown tag names its part and paragraph and suggests the closest known one`() {
        val problems = check(Docx.letterhead(listOf("Reference ", "{{memo.", "numbr}}"), listOf("{{totally.different}}")))
        assertThat(problems.map { it.code }).containsExactly(TemplateProblemCode.UNKNOWN_TAG, TemplateProblemCode.UNKNOWN_TAG)
        assertThat(problems[0].location.part).isEqualTo("Body")
        assertThat(problems[0].location.paragraph).isEqualTo(6)
        assertThat(problems[0].tag).isEqualTo("{{memo.numbr}}")
        assertThat(problems[0].suggestion).isEqualTo("{{memo.number}}")
        assertThat(problems[0].excerpt).isEqualTo("Reference {{memo.numbr}}")
        assertThat(problems[1].suggestion).isNull()
    }

    @Test
    fun `blocks must open and close in order, in the same place, and only on fields`() {
        val unclosed = check(Docx.paragraphs(listOf("{{?meta.amount}}"), listOf("{{meta.amount}}")))
        assertThat(unclosed.map { it.code }).containsExactly(TemplateProblemCode.UNBALANCED_SECTION)
        val stray = check(Docx.paragraphs(listOf("{{meta.amount}}"), listOf("{{/meta.amount}}")))
        assertThat(stray.map { it.code }).containsExactly(TemplateProblemCode.UNBALANCED_SECTION)
        val crossing =
            check(Docx.paragraphs(listOf("{{?meta.amount}}{{?meta.borrower}}"), listOf("{{/meta.amount}}{{/meta.borrower}}")))
        assertThat(crossing.map { it.code }).contains(TemplateProblemCode.UNBALANCED_SECTION)
        val notAField = check(Docx.paragraphs(listOf("{{?memo.number}}x{{/memo.number}}")))
        assertThat(notAField.map { it.code }).containsExactly(TemplateProblemCode.UNKNOWN_TAG)
        val nested = check(Docx.paragraphs(listOf("{{?meta.amount}}{{?meta.borrower}}"), listOf("{{/meta.borrower}}{{/meta.amount}}")))
        assertThat(nested).isEmpty()
    }

    @Test
    fun `a loop needs a table row of known fields below it, and nothing else in that row`() {
        val outside = check(Docx.paragraphs(listOf("{{approvals}}")))
        assertThat(outside.map { it.code }).containsExactly(TemplateProblemCode.LOOP_WITHOUT_ROW)

        val docx =
            Docx.document { d ->
                val table = d.createTable(2, 2)
                table.getRow(0).getCell(0).text = "Approver{{approvals}}"
                table.getRow(1).getCell(0).text = "[nme]"
                table.getRow(1).getCell(1).text = "[decision] {{memo.number}}"
            }
        val problems = check(docx)
        assertThat(
            problems.map { it.code },
        ).containsExactlyInAnyOrder(TemplateProblemCode.UNKNOWN_ROW_FIELD, TemplateProblemCode.TAG_IN_LOOP_ROW)
        assertThat(problems.first { it.code == TemplateProblemCode.UNKNOWN_ROW_FIELD }.suggestion).isEqualTo("[name]")

        val empty =
            Docx.document { d ->
                val table = d.createTable(2, 1)
                table.getRow(0).getCell(0).text = "Approvals {{approvals}}"
                table.getRow(1).getCell(0).text = "nothing here"
            }
        assertThat(check(empty).map { it.code }).containsExactly(TemplateProblemCode.LOOP_WITHOUT_ROW)
    }

    @Test
    fun `brackets outside a loop row are ordinary text`() {
        val docx =
            Docx.document { d ->
                d.createParagraph().createRun().setText("See [annex] for details")
                d
                    .createTable(1, 1)
                    .getRow(0)
                    .getCell(0)
                    .text = "[not a field]"
            }
        assertThat(check(docx)).isEmpty()
    }

    @Test
    fun `suggestions tolerate a few typos and ignore case`() {
        assertThat(Suggestions.closest("Memo.Numbr", setOf("memo.number", "memo.date"))).isEqualTo("memo.number")
        assertThat(Suggestions.closest("initator.nme", setOf("initiator.name"))).isEqualTo("initiator.name")
        assertThat(Suggestions.closest("xyz", setOf("memo.number"))).isNull()
        assertThat(Suggestions.distance("kitten", "sitting")).isEqualTo(3)
    }
}
