package com.memo.demo

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Minimal Word documents for the demo memos (design E01 §15), generated at seed time so no binary sits in the repository:
 * a title and paragraphs, with right-to-left paragraphs for Arabic text.
 */
object DemoDocx {
    fun of(
        title: String,
        paragraphs: List<String>,
        arabic: List<String> = emptyList(),
    ): ByteArray {
        val body =
            buildString {
                append(paragraph(title, bold = true))
                paragraphs.forEach { append(paragraph(it)) }
                arabic.forEach { append(paragraph(it, rtl = true)) }
            }
        val document =
            """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>$body</w:body></w:document>"""
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            fun entry(
                name: String,
                text: String,
            ) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.trimIndent().toByteArray())
                zip.closeEntry()
            }
            entry("[Content_Types].xml", CONTENT_TYPES)
            entry("_rels/.rels", RELS)
            entry("word/document.xml", document)
        }
        return bytes.toByteArray()
    }

    private fun paragraph(
        text: String,
        bold: Boolean = false,
        rtl: Boolean = false,
    ): String {
        val escaped = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        val paragraphProps = if (rtl) "<w:pPr><w:bidi/></w:pPr>" else ""
        val runProps = listOfNotNull("<w:b/>".takeIf { bold }, "<w:rtl/>".takeIf { rtl }).joinToString("")
        return "<w:p>$paragraphProps<w:r>${if (runProps.isEmpty()) "" else "<w:rPr>$runProps</w:rPr>"}<w:t>$escaped</w:t></w:r></w:p>"
    }

    private const val CONTENT_TYPES = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
          <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
          <Default Extension="xml" ContentType="application/xml"/>
          <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
        </Types>"""

    private const val RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
          <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
        </Relationships>"""
}
