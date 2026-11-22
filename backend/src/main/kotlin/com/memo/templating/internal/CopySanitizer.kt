package com.memo.templating.internal

import com.memo.common.poi.PoiLimits
import com.memo.templating.ContentControlException
import com.memo.templating.DocxCopies
import org.springframework.stereotype.Component
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.xml.namespace.QName
import javax.xml.stream.XMLEventFactory
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLOutputFactory
import javax.xml.stream.XMLStreamConstants
import javax.xml.stream.XMLStreamException
import javax.xml.stream.events.StartElement

/**
 * What a reader's copy of the minutes keeps besides its body (ADR-0066, E06 T1's note): footnotes, endnotes and comments
 * no longer referenced from the body once item controls are replaced are dropped, and the document properties (core,
 * extended, custom: title, author, company, keywords, the titles of parts) are emptied, so nothing of a redacted item
 * survives in another part of the package or in the PDF's metadata. Streams each part with StAX (no DTDs, no external
 * entities), each inflated part bounded as POI's packages are.
 */
internal object CopySanitizer {
    private const val W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private const val DOCUMENT = "word/document.xml"
    private val NOTES = mapOf("word/footnotes.xml" to "footnote", "word/endnotes.xml" to "endnote", "word/comments.xml" to "comment")
    private val REFERENCES =
        mapOf(
            "footnoteReference" to "footnote",
            "endnoteReference" to "endnote",
            "commentReference" to "comment",
            "commentRangeStart" to "comment",
        )

    /** The separator notes Word and ONLYOFFICE keep in every notes part (never referenced, never content). */
    private val KEPT_TYPES = setOf("separator", "continuationSeparator", "continuationNotice")

    private val EMPTY_PROPERTIES =
        mapOf(
            "docProps/core.xml" to
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
                """<cp:coreProperties xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties" """ +
                """xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:dcterms="http://purl.org/dc/terms/"/>""",
            "docProps/app.xml" to
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
                """<Properties xmlns="http://schemas.openxmlformats.org/officeDocument/2006/extended-properties"/>""",
            "docProps/custom.xml" to
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
                """<Properties xmlns="http://schemas.openxmlformats.org/officeDocument/2006/custom-properties"/>""",
        )

    fun sanitize(docx: ByteArray): ByteArray {
        PoiLimits.apply()
        try {
            val referenced = referenced(docx)
            val out = ByteArrayOutputStream(docx.size)
            ZipInputStream(ByteArrayInputStream(docx)).use { zip ->
                ZipOutputStream(out).use { result ->
                    generateSequence { zip.nextEntry }.forEach { entry -> copy(entry.name, zip, result, referenced) }
                }
            }
            return out.toByteArray()
        } catch (e: IOException) {
            throw ContentControlException("not a readable document", e)
        } catch (e: XMLStreamException) {
            throw ContentControlException("not a readable document", e)
        }
    }

    /** Copies the part [name] from [zip] to [result]: emptied, pruned of notes not referred to, or as it is. */
    private fun copy(
        name: String,
        zip: ZipInputStream,
        result: ZipOutputStream,
        referenced: Map<String, Set<String>>,
    ) {
        result.putNextEntry(ZipEntry(name))
        val empty = EMPTY_PROPERTIES[name]
        val note = NOTES[name]
        when {
            empty != null -> result.write(empty.toByteArray(Charsets.UTF_8))
            note != null -> prune(Bounded(zip), result, note, referenced[note].orEmpty())
            else -> zip.transferTo(result)
        }
        result.closeEntry()
    }

    /** Every text of every part of `word/` (body, headers, footers, notes, comments; deleted text too), one paragraph a line. */
    fun text(docx: InputStream): String =
        try {
            val text = StringBuilder()
            ZipInputStream(docx).use { zip ->
                generateSequence { zip.nextEntry }
                    .filter { it.name.startsWith("word/") && it.name.endsWith(".xml") }
                    .forEach { partText(Bounded(zip), text) }
            }
            text.toString()
        } catch (e: IOException) {
            throw ContentControlException("not a readable document", e)
        } catch (e: XMLStreamException) {
            throw ContentControlException("not a readable document", e)
        }

    private val TEXTS = setOf("t", "delText", "instrText")

    /** The text of one part, appended to [text]. */
    private fun partText(
        part: InputStream,
        text: StringBuilder,
    ) {
        var inText = false
        stream(part) { reader ->
            val named = reader.isStartElement || reader.isEndElement
            val name = if (named && reader.namespaceURI == W) reader.localName else ""
            when (reader.eventType) {
                XMLStreamConstants.START_ELEMENT -> {
                    if (name in TEXTS) inText = true
                }

                XMLStreamConstants.END_ELEMENT -> {
                    if (name in TEXTS) inText = false
                    if (name == "p") text.append('\n')
                }

                XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA -> {
                    if (inText) text.append(reader.text)
                }

                else -> {
                    return@stream
                }
            }
        }
    }

    /** The note and comment ids the body refers to, by kind. */
    private fun referenced(docx: ByteArray): Map<String, Set<String>> {
        val found = mutableMapOf<String, MutableSet<String>>()
        ZipInputStream(ByteArrayInputStream(docx)).use { zip ->
            generateSequence { zip.nextEntry }.firstOrNull { it.name == DOCUMENT } ?: throw ContentControlException("no $DOCUMENT")
            stream(Bounded(zip)) { reader ->
                if (reader.eventType == XMLStreamConstants.START_ELEMENT && reader.namespaceURI == W) {
                    REFERENCES[reader.localName]?.let { kind ->
                        reader.getAttributeValue(W, "id")?.let { found.getOrPut(kind) { mutableSetOf() } += it }
                    }
                }
            }
        }
        return found
    }

    /** Copies [input] to [output] without the [element]s (children of the root) whose id is not in [keep]. */
    private fun prune(
        input: InputStream,
        output: OutputStream,
        element: String,
        keep: Set<String>,
    ) {
        val factory = XMLInputFactory.newFactory()
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false)
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
        factory.setProperty(XMLInputFactory.IS_REPLACING_ENTITY_REFERENCES, false)
        val reader = factory.createXMLEventReader(input)
        val writer = XMLOutputFactory.newFactory().createXMLEventWriter(Unclosed(output), Charsets.UTF_8.name())
        val events = XMLEventFactory.newInstance()
        var depth = 0
        var skipping = 0
        try {
            while (reader.hasNext()) {
                val event = reader.nextEvent()
                if (event.isStartElement) {
                    depth++
                    if (skipping == 0 && depth == 2 && dropped(event.asStartElement(), element, keep)) skipping = depth
                    if (skipping == 0) writer.add(event)
                } else if (event.isEndElement) {
                    if (skipping == 0) writer.add(event)
                    if (skipping == depth) skipping = 0
                    depth--
                } else if (event.isStartDocument) {
                    writer.add(events.createStartDocument(Charsets.UTF_8.name(), "1.0", true))
                } else if (skipping == 0) {
                    writer.add(event)
                }
            }
            writer.flush()
        } finally {
            reader.close()
            writer.close()
        }
    }

    private fun dropped(
        start: StartElement,
        element: String,
        keep: Set<String>,
    ): Boolean {
        if (start.name != QName(W, element)) return false
        val type = start.getAttributeByName(QName(W, "type"))?.value
        val id = start.getAttributeByName(QName(W, "id"))?.value
        return type !in KEPT_TYPES && id !in keep
    }

    private fun stream(
        xml: InputStream,
        each: (javax.xml.stream.XMLStreamReader) -> Unit,
    ) {
        val factory = XMLInputFactory.newFactory()
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false)
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
        factory.setProperty(XMLInputFactory.IS_REPLACING_ENTITY_REFERENCES, false)
        val reader = factory.createXMLStreamReader(xml)
        try {
            while (reader.hasNext()) {
                reader.next()
                each(reader)
            }
        } finally {
            reader.close()
        }
    }

    /** One zip entry, inflated at most [PoiLimits.MAX_ENTRY_BYTES]; closing it leaves the zip open. */
    private class Bounded(
        input: InputStream,
    ) : FilterInputStream(input) {
        private var count = 0L

        override fun read(): Int = super.read().also { if (it >= 0) count(1) }

        override fun read(
            b: ByteArray,
            off: Int,
            len: Int,
        ): Int = super.read(b, off, len).also { if (it > 0) count(it.toLong()) }

        private fun count(n: Long) {
            count += n
            if (count > PoiLimits.MAX_ENTRY_BYTES) throw IOException("a part is larger than ${PoiLimits.MAX_ENTRY_BYTES} bytes")
        }

        override fun close() = Unit
    }

    /** The zip's stream, which the XML writer must not close. */
    private class Unclosed(
        output: OutputStream,
    ) : java.io.FilterOutputStream(output) {
        override fun write(
            b: ByteArray,
            off: Int,
            len: Int,
        ) = out.write(b, off, len)

        override fun close() = flush()
    }
}

/** [DocxCopies] on [CopySanitizer]. */
@Component
internal class PoiDocxCopies : DocxCopies {
    override fun forCopy(docx: ByteArray): ByteArray = CopySanitizer.sanitize(docx)

    override fun text(docx: InputStream): String = CopySanitizer.text(docx)
}
