package com.memo.memo.internal

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowableOfType
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/** ADR-0049, test plan E03 T3: `noteDoc/v1` accepts its shapes only, and gives a stable plain text. */
class NoteDocTest {
    private val json = JsonMapper.builder().build()
    private val bob = UUID.fromString("0198a5c4-2f31-7d00-8000-000000000001")
    private val memo = UUID.fromString("0198a5c4-2f31-7d00-8000-000000000002")

    private fun parse(text: String) = NoteDoc.parse(json.readTree(text))

    private fun problems(text: String): List<String> =
        catchThrowableOfType(NoteRejectedException::class.java) { parse(text) }.problems.map { "${it.path}:${it.code}" }

    private fun doc(content: String) = """{"type":"doc","content":[$content]}"""

    private fun para(inline: String) = """{"type":"paragraph","content":[$inline]}"""

    @Test
    fun `every allowed shape is read, and the plain text is stable`() {
        val note =
            doc(
                para(
                    """{"type":"text","text":"Annex B has the "},""" +
                        """{"type":"text","text":"lease terms","marks":[{"type":"bold"},{"type":"italic"}]},""" +
                        """{"type":"text","text":". "},{"type":"mention","attrs":{"id":"$bob","label":"Demo Head of Credit"}},""" +
                        """{"type":"hardBreak"},""" +
                        """{"type":"text","text":"See "},{"type":"memoRef","attrs":{"id":"$memo","label":"M-2026-00007"}},""" +
                        """{"type":"text","text":" and the policy",""" +
                        """"marks":[{"type":"link","attrs":{"href":"https://bank.example/policy"}}]}""",
                ) + "," +
                    """{"type":"bulletList","content":[{"type":"listItem","content":[${para("""{"type":"text","text":"rent"}""")},""" +
                    """{"type":"orderedList","attrs":{"start":3},"content":[{"type":"listItem","content":[${para(
                        """{"type":"text","text":"May"}""",
                    )}]}]}]}]}""",
            )
        val parsed = parse(note)
        assertThat(
            parsed.text(),
        ).isEqualTo("Annex B has the lease terms. @Demo Head of Credit\nSee M-2026-00007 and the policy\n• rent\n  3. May")
        assertThat(parsed.mentions()).containsExactly(bob)
        assertThat(parsed.memoRefs()).containsExactly(memo)
        val link = parsed.inlines().filterIsInstance<NoteText>().single { it.link != null }
        assertThat(link.link).isEqualTo("https://bank.example/policy")
        // The stored form reads back as the same document.
        assertThat(NoteDoc.parse(NoteDoc.json(parsed, json))).isEqualTo(parsed)
    }

    @Test
    fun `unknown nodes, marks and keys, and links other than https are refused with their path`() {
        assertThat(problems(doc("""{"type":"image","attrs":{"src":"https://x.example/a.png"}}"""))).contains("content[0]:UNKNOWN_NODE")
        assertThat(problems(doc("""{"type":"paragraph","attrs":{"textAlign":"left"},"content":[{"type":"text","text":"x"}]}""")))
            .containsExactly("content[0].attrs:UNKNOWN_KEY")
        assertThat(
            problems(doc(para("""{"type":"text","text":"x","marks":[{"type":"underline"}]}"""))),
        ).containsExactly("content[0].content[0].marks[0]:UNKNOWN_MARK")
        assertThat(problems(doc(para("""{"type":"text","text":"x","marks":[{"type":"link","attrs":{"href":"javascript:alert(1)"}}]}"""))))
            .containsExactly("content[0].content[0].marks[0].attrs.href:LINK_NOT_HTTPS")
        assertThat(problems(doc(para("""{"type":"text","text":"x","marks":[{"type":"link","attrs":{"href":"http://bank.example"}}]}"""))))
            .containsExactly("content[0].content[0].marks[0].attrs.href:LINK_NOT_HTTPS")
        assertThat(
            problems(
                doc(
                    para("""{"type":"text","text":"x","marks":[{"type":"link","attrs":{"href":"https://a.example","target":"_blank"}}]}"""),
                ),
            ),
        ).containsExactly("content[0].content[0].marks[0].attrs.target:UNKNOWN_KEY")
        assertThat(
            problems(doc(para("""{"type":"mention","attrs":{"id":"not-a-uuid","label":"x"}}"""))),
        ).contains("content[0].content[0].attrs.id:INVALID_ID")
        assertThat(problems("""{"type":"doc","content":[],"extra":1}""")).contains("extra:UNKNOWN_KEY")
        assertThat(problems("""["not","an","object"]""")).containsExactly(":NOT_A_DOCUMENT")
    }

    @Test
    fun `limits refuse deep lists, empty notes, long text and too many mentions`() {
        fun list(depth: Int): String =
            if (depth == 0) {
                para("""{"type":"text","text":"x"}""")
            } else {
                """{"type":"bulletList","content":[{"type":"listItem","content":[${list(depth - 1)}]}]}"""
            }
        assertThat(problems(doc(list(4))).any { it.endsWith(":TOO_DEEP") }).isTrue()
        assertThat(parse(doc(list(3))).text()).contains("x")
        assertThat(problems(doc(para("")))).containsExactly(":EMPTY")
        assertThat(problems(doc(para("""{"type":"text","text":"${"a".repeat(NoteDoc.MAX_TEXT + 1)}"}""")))).containsExactly(":TOO_LONG")
        val mentions =
            (1..NoteDoc.MAX_EACH + 1).joinToString(
                ",",
            ) { """{"type":"mention","attrs":{"id":"${UUID.randomUUID()}","label":"P$it"}}""" }
        assertThat(problems(doc(para(mentions)))).containsExactly(":TOO_MANY_MENTIONS")
    }

    @Test
    fun `a plain remark becomes one paragraph per line`() {
        val doc = NoteDocument.ofText("Please attach annex B.\nAnd the rent schedule.")
        assertThat(doc.blocks).hasSize(2)
        assertThat(doc.text()).isEqualTo("Please attach annex B.\nAnd the rent schedule.")
        assertThat(NoteDoc.parse(NoteDoc.json(doc, json))).isEqualTo(doc)
    }
}
