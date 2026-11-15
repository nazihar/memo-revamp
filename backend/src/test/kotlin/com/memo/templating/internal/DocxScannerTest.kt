package com.memo.templating.internal

import com.memo.templating.RefusalRule
import com.memo.templating.TagKind
import com.memo.templating.TemplateLocation
import com.memo.templating.TemplateProblemCode
import com.memo.templating.TemplateScan
import com.memo.testsupport.Docx
import com.memo.testsupport.Samples
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** ADR-0048, test plan E03 T1: refusals one rule at a time, the tag inventory with locations, malformed tags. */
class DocxScannerTest {
    private fun scan(docx: ByteArray): TemplateScan = DocxScanner.scan { SeekableInMemoryByteChannel(docx) }

    private fun rules(docx: ByteArray) = scan(docx).refusals.map { it.rule }.toSet()

    private val clean = Docx.letterhead()

    @Test
    fun `a letterhead's tags are found in the body, header, footer and table, also when Word splits them across runs`() {
        val found = scan(clean)
        assertThat(found.refusals).isEmpty()
        assertThat(found.problems).isEmpty()
        val tags = found.tags.map { Triple(it.kind, it.name, it.location.part) }
        assertThat(tags).contains(
            Triple(TagKind.VALUE, "memo.subject", "Body"),
            Triple(TagKind.VALUE, "initiator.name", "Body"),
            Triple(TagKind.SECTION_START, "meta.amount", "Body"),
            Triple(TagKind.VALUE, "meta.amount", "Body"),
            Triple(TagKind.SECTION_END, "meta.amount", "Body"),
            Triple(TagKind.VALUE, "approvals", "Body"),
            Triple(TagKind.ROW_FIELD, "name", "Body"),
            Triple(TagKind.ROW_FIELD, "decidedAt", "Body"),
            Triple(TagKind.VALUE, "memo.number", "Header 1"),
            Triple(TagKind.VALUE, "memo.date", "Footer 1"),
        )
        val subject = found.tags.first { it.name == "memo.subject" }
        assertThat(subject.location).isEqualTo(TemplateLocation("Body", 1))
        assertThat(subject.excerpt).isEqualTo("Subject: {{memo.subject}}")
        val loop = found.tags.first { it.name == "approvals" }
        val field = found.tags.first { it.name == "decision" }
        assertThat(loop.row!!.row).isEqualTo(1)
        assertThat(field.row).isEqualTo(loop.row.copy(row = 2))
    }

    @Test
    fun `macros are refused by part name, relationship and content type`() {
        val vba =
            Docx.withOverride(
                Docx.withRelationship(
                    Docx.edit(clean, mapOf("word/vbaProject.bin" to byteArrayOf(1, 2, 3))),
                    "http://schemas.microsoft.com/office/2006/relationships/vbaProject",
                    "vbaProject.bin",
                ),
                "word/vbaProject.bin",
                "application/vnd.ms-office.vbaProject",
            )
        val refusals = scan(vba).refusals
        assertThat(refusals.map { it.rule }.toSet()).containsExactly(RefusalRule.MACROS)
        assertThat(refusals.map { it.where }).contains("word/vbaProject.bin")

        val macroMain =
            Docx.editText(clean, "[Content_Types].xml") {
                it.replace("wordprocessingml.document.main+xml", "ms-word.document.macroEnabled.main+xml")
            }
        assertThat(rules(macroMain)).contains(RefusalRule.MACROS)
        val keyMap =
            Docx.withRelationship(
                clean,
                "http://schemas.microsoft.com/office/2006/relationships/keyMapCustomizations",
                "customizations.xml",
            )
        assertThat(rules(keyMap)).containsExactly(RefusalRule.MACROS)
    }

    @Test
    fun `ActiveX controls and embedded OLE objects are refused`() {
        val activeX =
            Docx.withRelationship(
                Docx.edit(clean, mapOf("word/activeX/activeX1.xml" to "<ax/>".toByteArray())),
                "${Docx.REL}/control",
                "activeX/activeX1.xml",
            )
        assertThat(rules(activeX)).containsExactly(RefusalRule.ACTIVEX)
        val ole =
            Docx.withRelationship(
                Docx.edit(clean, mapOf("word/embeddings/oleObject1.bin" to byteArrayOf(1))),
                "${Docx.REL}/oleObject",
                "embeddings/oleObject1.bin",
            )
        assertThat(rules(ole)).containsExactly(RefusalRule.OLE_OBJECT)
        assertThat(
            rules(Docx.withRelationship(clean, "${Docx.REL}/package", "embeddings/book.xlsx")),
        ).containsExactly(RefusalRule.OLE_OBJECT)
    }

    @Test
    fun `altChunk, an attached template, mail merge and external images are refused`() {
        assertThat(rules(Docx.withRelationship(clean, "${Docx.REL}/aFChunk", "chunk.html"))).containsExactly(RefusalRule.ALT_CHUNK)
        val attached =
            Docx.withRelationship(
                clean,
                "${Docx.REL}/attachedTemplate",
                "file:///C:/Templates/Normal.dotm",
                true,
                "word/_rels/settings.xml.rels",
            )
        assertThat(rules(attached)).containsExactly(RefusalRule.ATTACHED_TEMPLATE)
        val merge =
            Docx.editText(clean, "word/settings.xml") {
                val element = """<w:mailMerge><w:mainDocumentType w:val="formLetters"/></w:mailMerge>"""
                // POI writes an empty, self-closing settings element.
                val open = if ("</w:settings>" in it) it.substringBefore("</w:settings>") else it.substringBeforeLast("/>") + ">"
                "$open$element</w:settings>"
            }
        assertThat(rules(merge)).containsExactly(RefusalRule.MAIL_MERGE)
        val image = Docx.withRelationship(clean, "${Docx.REL}/image", "https://tracker.example/logo.png", external = true)
        assertThat(rules(image)).containsExactly(RefusalRule.EXTERNAL_RELATIONSHIP)
        val link = Docx.withRelationship(clean, "${Docx.REL}/hyperlink", "https://acme.example/terms", external = true)
        assertThat(rules(link)).isEmpty()
    }

    @Test
    fun `fields that pull content in are refused, also when Word splits the instruction, and ordinary fields are not`() {
        val simple = Docx.withBodyXml(clean, """<w:p><w:fldSimple w:instr=" INCLUDETEXT &quot;\\\\server\\share\\a.docx&quot; "/></w:p>""")
        assertThat(scan(simple).refusals.single().where).matches("""Body, paragraph \d+: INCLUDETEXT""")
        val complex =
            Docx.withBodyXml(
                clean,
                """<w:p><w:r><w:fldChar w:fldCharType="begin"/></w:r>""" +
                    """<w:r><w:instrText xml:space="preserve"> INCLUDE</w:instrText></w:r>""" +
                    """<w:r><w:instrText>PICTURE "https://x.example/a.png"</w:instrText></w:r>""" +
                    """<w:r><w:fldChar w:fldCharType="separate"/></w:r>""" +
                    """<w:r><w:t>pic</w:t></w:r><w:r><w:fldChar w:fldCharType="end"/></w:r></w:p>""",
            )
        assertThat(scan(complex).refusals.single().where).matches("""Body, paragraph \d+: INCLUDEPICTURE""")
        listOf("LINK Excel.Sheet.12 \"book.xlsx\" \"Sheet1!R1C1\"", "DDEAUTO excel \"a\"", "MACROBUTTON Go Click").forEach { instr ->
            val escaped = instr.replace("\"", "&quot;")
            assertThat(
                rules(Docx.withBodyXml(clean, """<w:p><w:fldSimple w:instr="$escaped"/></w:p>""")),
            ).describedAs(instr).containsExactly(
                RefusalRule.FIELD_CODE,
            )
        }
        val page = Docx.withBodyXml(clean, """<w:p><w:fldSimple w:instr=" PAGE \* MERGEFORMAT "/></w:p>""")
        assertThat(rules(page)).isEmpty()
    }

    @Test
    fun `an unknown kind of part is refused as unsupported, and frames and sub-documents too`() {
        assertThat(rules(Docx.withRelationship(clean, "http://example.com/relationships/mystery", "mystery.xml"))).containsExactly(
            RefusalRule.UNSUPPORTED_PART,
        )
        assertThat(
            rules(Docx.withRelationship(clean, "${Docx.REL}/subDocument", "chapter.docx")),
        ).containsExactly(RefusalRule.UNSUPPORTED_PART)
        assertThat(rules(Docx.withRelationship(clean, "${Docx.REL}/frame", "https://x.example/", external = true))).containsExactly(
            RefusalRule.EXTERNAL_RELATIONSHIP,
        )
    }

    @Test
    fun `what is not a readable Word package is refused, never thrown`() {
        assertThat(rules(Samples.pdf())).containsExactly(RefusalRule.NOT_A_DOCUMENT)
        assertThat(rules(Samples.zip("a.txt" to "a".toByteArray()))).containsExactly(RefusalRule.NOT_A_DOCUMENT)
        assertThat(rules(Samples.xlsx())).containsExactly(RefusalRule.NOT_A_DOCUMENT)
        val dtd =
            Docx.editText(clean, "word/document.xml") {
                it.replaceFirst("?>", "?><!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/passwd\">]>")
            }
        assertThat(rules(dtd)).containsExactly(RefusalRule.NOT_A_DOCUMENT)
        assertThat(rules(bigPart())).containsExactly(RefusalRule.NOT_A_DOCUMENT)
    }

    @Test
    fun `malformed and unsupported tags are problems with their location and a suggestion`() {
        val docx =
            Docx.paragraphs(
                listOf("Ref {{ memo.number }}"),
                listOf("Dear {{initiator.name"),
                listOf("Logo {{@logo}}"),
                listOf("Total {{meta.montant_é}}"),
            )
        val problems = scan(docx).problems
        assertThat(problems.map { Triple(it.code, it.location.paragraph, it.suggestion) }).containsExactly(
            Triple(TemplateProblemCode.MALFORMED_TAG, 1, "{{memo.number}}"),
            Triple(TemplateProblemCode.MALFORMED_TAG, 2, null),
            Triple(TemplateProblemCode.UNSUPPORTED_TAG, 3, null),
            Triple(TemplateProblemCode.MALFORMED_TAG, 4, null),
        )
        assertThat(problems[0].excerpt).isEqualTo("Ref {{ memo.number }}")
        assertThat(problems[2].tag).isEqualTo("{{@logo}}")
    }

    @Test
    fun `a text box stored twice as alternate content is read once`() {
        val box =
            """<w:txbxContent><w:p><w:r><w:t>{{memo.type}}</w:t></w:r></w:p></w:txbxContent>"""
        val xml =
            """<w:p><w:r><mc:AlternateContent xmlns:mc="http://schemas.openxmlformats.org/markup-compatibility/2006">""" +
                """<mc:Choice Requires="wps"><w:drawing>""" +
                """<wps:txbx xmlns:wps="http://schemas.microsoft.com/office/word/2010/wordprocessingShape">$box</wps:txbx>""" +
                """</w:drawing></mc:Choice>""" +
                """<mc:Fallback><w:pict><v:textbox xmlns:v="urn:schemas-microsoft-com:vml">$box</v:textbox></w:pict></mc:Fallback>""" +
                """</mc:AlternateContent></w:r></w:p>"""
        val tags = scan(Docx.withBodyXml(clean, xml)).tags.filter { it.name == "memo.type" }
        assertThat(tags).hasSize(1)
    }

    /** A package whose document part inflates beyond the scanner's per-part bound. */
    private fun bigPart(): ByteArray {
        val entries = Docx.entries(clean)
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                if (name == "word/document.xml") {
                    val spaces = ByteArray(1 shl 20) { ' '.code.toByte() }
                    zip.write(bytes.copyOf(bytes.size - "</w:document>".length))
                    repeat(((DocxScanner.MAX_PART_BYTES shr 20) + 1).toInt()) { zip.write(spaces) }
                    zip.write("</w:document>".toByteArray())
                } else {
                    zip.write(bytes)
                }
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }
}
