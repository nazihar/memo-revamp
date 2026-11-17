package com.memo.testsupport

import org.apache.poi.wp.usermodel.HeaderFooterType
import org.apache.poi.xwpf.extractor.XWPFWordExtractor
import org.apache.poi.xwpf.usermodel.XWPFDocument
import org.apache.poi.xwpf.usermodel.XWPFParagraph
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * .docx templates for tests (ADR-0048): letterheads made with Apache POI as Word would store them (tags split across
 * runs with different formatting, in headers, footers and an approval table), and edits that plant the content a
 * template must never carry (parts, relationships, content types, raw body XML).
 */
object Docx {
    const val REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    const val DOCUMENT_RELS = "word/_rels/document.xml.rels"

    /**
     * A bank letterhead: the number in the header (split as `{{memo.` + `number}}`), the date in the footer, the subject
     * split across three runs, the initiator, an amount block, and the approval table with `{{approvals}}` in its header
     * row. [extra] adds body paragraphs, each given as its runs.
     */
    fun letterhead(vararg extra: List<String>): ByteArray =
        document { d ->
            val header = d.createHeader(HeaderFooterType.DEFAULT).createParagraph()
            header.createRun().setText("ACME BANK PLC · Credit Division   Ref: ")
            header.createRun().setText("{{memo.")
            header.createRun().apply { isBold = true }.setText("number}}")
            d
                .createFooter(HeaderFooterType.DEFAULT)
                .createParagraph()
                .createRun()
                .setText("Dated {{memo.date}}")
            runs(d.createParagraph(), "Subject: ", "{{", "memo.subject", "}}")
            runs(d.createParagraph(), "From: {{initiator.name}}, {{initiator.position}}")
            runs(d.createParagraph(), "{{?meta.amount}}")
            runs(d.createParagraph(), "Facility amount: {{meta.", "amount", "}}")
            runs(d.createParagraph(), "{{/meta.amount}}")
            extra.forEach { runs(d.createParagraph(), *it.toTypedArray()) }
            val table = d.createTable(2, 3)
            table.getRow(0).getCell(0).text = "Approver{{approvals}}"
            table.getRow(0).getCell(1).text = "Decision"
            table.getRow(0).getCell(2).text = "Date"
            table.getRow(1).getCell(0).text = "[name], [position]"
            table.getRow(1).getCell(1).text = "[decision]"
            table.getRow(1).getCell(2).text = "[decidedAt]"
        }

    /** A document with one paragraph per entry, each given as its runs. */
    fun paragraphs(vararg paragraphs: List<String>): ByteArray =
        document { d -> paragraphs.forEach { runs(d.createParagraph(), *it.toTypedArray()) } }

    fun document(build: (XWPFDocument) -> Unit): ByteArray =
        XWPFDocument().use { d ->
            build(d)
            ByteArrayOutputStream().also { d.write(it) }.toByteArray()
        }

    private fun runs(
        p: XWPFParagraph,
        vararg texts: String,
    ) {
        texts.forEachIndexed { i, text -> p.createRun().apply { isItalic = i % 2 == 1 }.setText(text) }
    }

    /** The text of the body, the headers and the footers, as POI extracts it. */
    fun text(docx: ByteArray): String =
        XWPFDocument(ByteArrayInputStream(docx)).use { d ->
            val body = XWPFWordExtractor(d).use { it.text }
            (listOf(body) + d.headerList.map { it.text } + d.footerList.map { it.text }).joinToString("\n")
        }

    fun entries(docx: ByteArray): Map<String, ByteArray> {
        val found = linkedMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(docx)).use { zip ->
            generateSequence { zip.nextEntry }.forEach { found[it.name] = zip.readAllBytes() }
        }
        return found
    }

    /** [docx] with entries added or replaced; a null value removes the entry. */
    fun edit(
        docx: ByteArray,
        changes: Map<String, ByteArray?>,
    ): ByteArray {
        val entries = entries(docx).toMutableMap()
        changes.forEach { (name, bytes) -> if (bytes == null) entries.remove(name) else entries[name] = bytes }
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    /** [docx] with the text of entry [name] changed by [change]. */
    fun editText(
        docx: ByteArray,
        name: String,
        change: (String) -> String,
    ): ByteArray = edit(docx, mapOf(name to change(String(entries(docx).getValue(name))).toByteArray()))

    /** Adds a relationship to [rels] (created when missing). */
    fun withRelationship(
        docx: ByteArray,
        type: String,
        target: String,
        external: Boolean = false,
        rels: String = DOCUMENT_RELS,
    ): ByteArray {
        val mode = if (external) """ TargetMode="External"""" else ""
        val relationship = """<Relationship Id="rIdTest${type.hashCode().toUInt()}" Type="$type" Target="$target"$mode/>"""
        val existing = entries(docx)[rels]?.let { String(it) }
        val xml =
            existing?.replace("</Relationships>", "$relationship</Relationships>")
                ?: """<?xml version="1.0" encoding="UTF-8"?>""" +
                """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">$relationship</Relationships>"""
        return edit(docx, mapOf(rels to xml.toByteArray()))
    }

    /** Adds an `Override` for [partName] with [contentType]. */
    fun withOverride(
        docx: ByteArray,
        partName: String,
        contentType: String,
    ): ByteArray =
        editText(docx, "[Content_Types].xml") {
            it.replace("</Types>", """<Override PartName="/$partName" ContentType="$contentType"/></Types>""")
        }

    /** Adds raw WordprocessingML (paragraphs, tables) at the end of the body. */
    fun withBodyXml(
        docx: ByteArray,
        xml: String,
    ): ByteArray =
        editText(docx, "word/document.xml") { doc ->
            val at = doc.indexOf("<w:sectPr").takeIf { it >= 0 } ?: doc.lastIndexOf("</w:body>")
            doc.substring(0, at) + xml + doc.substring(at)
        }
}
