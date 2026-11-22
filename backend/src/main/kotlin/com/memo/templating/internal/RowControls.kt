package com.memo.templating.internal

import com.memo.common.poi.PoiLimits
import com.memo.templating.ContentControlException
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.xml.namespace.QName
import javax.xml.stream.XMLEventFactory
import javax.xml.stream.XMLEventWriter
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLOutputFactory
import javax.xml.stream.events.XMLEvent

/**
 * Row-level content controls in a document's tables (ADR-0066, E06 T7): a filled row of a loop with row tags
 * ([com.memo.templating.TemplateData.rowTags]) carries a marker (a per-fill random token and the tag) at the start of each
 * of its values; [wrap] turns every table row holding a marker into a row-level `w:sdt` with that tag (unlocked) and removes
 * the markers; [drop] removes the row-level controls with given tags, their rows included. Both stream
 * `word/document.xml` with StAX, a row at a time.
 */
internal object RowControls {
    private const val W = ControlXml.W
    private const val DOCUMENT = "word/document.xml"
    private val TR = QName(W, "tr")
    private val TBL = QName(W, "tbl")
    private val SDT = QName(W, "sdt")
    private val TAG_ELEMENT = QName(W, "tag")
    private val VAL = QName(W, "val")
    private val events = XMLEventFactory.newInstance()
    private val output = XMLOutputFactory.newFactory()

    /** A row tag: as a content control's ([com.memo.templating.ControlMark]), 1 to 64 letters, digits and `:._-`. */
    val ROW_TAG = Regex("^[A-Za-z0-9:._-]{1,64}$")

    /** The marker put before each value of a tagged row: [token], then the tag in brackets. */
    fun marker(
        token: String,
        tag: String,
    ): String = "$token[$tag]"

    /** [docx] with each table row that holds a marker of [token] wrapped in a row-level control of its tag. */
    fun wrap(
        docx: ByteArray,
        token: String,
    ): ByteArray = rewrite(docx) { Wrapping(token) }

    /** [docx] without the row-level controls whose tag is in [tags]. */
    fun drop(
        docx: ByteArray,
        tags: Set<String>,
    ): ByteArray = if (tags.isEmpty()) docx else rewrite(docx) { Dropping(tags) }

    private fun rewrite(
        docx: ByteArray,
        pass: () -> Pass,
    ): ByteArray {
        PoiLimits.apply()
        val out = ByteArrayOutputStream(docx.size + docx.size / 8)
        try {
            ZipInputStream(ByteArrayInputStream(docx)).use { zip -> ZipOutputStream(out).use { result -> entries(zip, result, pass) } }
        } catch (e: javax.xml.stream.XMLStreamException) {
            throw ContentControlException("not a readable document", e)
        }
        return out.toByteArray()
    }

    /** Every entry of [zip] into [result], `word/document.xml` through a fresh [pass]. */
    private fun entries(
        zip: ZipInputStream,
        result: ZipOutputStream,
        pass: () -> Pass,
    ) {
        generateSequence { zip.nextEntry }.forEach { entry ->
            result.putNextEntry(ZipEntry(entry.name))
            if (entry.name == DOCUMENT) stream(zip, result, pass()) else zip.transferTo(result)
            result.closeEntry()
        }
    }

    private fun stream(
        xml: InputStream,
        out: OutputStream,
        pass: Pass,
    ) {
        // No DTDs and no external entities; text coalesced, so a marker is never split between two events.
        val factory = XMLInputFactory.newFactory()
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false)
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
        factory.setProperty(XMLInputFactory.IS_REPLACING_ENTITY_REFERENCES, false)
        factory.setProperty(XMLInputFactory.IS_COALESCING, true)
        val reader = factory.createXMLEventReader(KeepOpen(xml))
        val writer = output.createXMLEventWriter(KeepOpenOut(out), Charsets.UTF_8.name())
        while (reader.hasNext()) pass.add(reader.nextEvent(), writer)
        writer.flush()
        reader.close()
    }

    /** One pass over `document.xml`, an event at a time. */
    private interface Pass {
        fun add(
            event: XMLEvent,
            writer: XMLEventWriter,
        )
    }

    /** Holds each outermost table row until its end, then writes it, wrapped and cleaned when it holds a marker. */
    private class Wrapping(
        private val token: String,
    ) : Pass {
        private val pattern = Regex(Regex.escape(token) + """\[([A-Za-z0-9:._-]{1,64})]""")
        private var row: MutableList<XMLEvent>? = null
        private var depth = 0

        override fun add(
            event: XMLEvent,
            writer: XMLEventWriter,
        ) {
            val held = row
            when {
                held != null -> {
                    held += event
                    if (event.isStartElement && event.asStartElement().name == TR) depth++
                    if (event.isEndElement && event.asEndElement().name == TR && --depth == 0) {
                        release(held, writer)
                        row = null
                    }
                }

                event.isStartElement && event.asStartElement().name == TR -> {
                    row = mutableListOf(event)
                    depth = 1
                }

                else -> {
                    writer.add(event)
                }
            }
        }

        private fun release(
            row: List<XMLEvent>,
            writer: XMLEventWriter,
        ) {
            val tag =
                row
                    .asSequence()
                    .filter { it.isCharacters }
                    .mapNotNull { pattern.find(it.asCharacters().data)?.groupValues?.get(1) }
                    .firstOrNull()
            if (tag != null) {
                writer.add(events.createStartElement("w", W, "sdt"))
                writer.add(events.createStartElement("w", W, "sdtPr"))
                writer.add(events.createStartElement("w", W, "tag"))
                writer.add(events.createAttribute("w", W, "val", tag))
                writer.add(events.createEndElement("w", W, "tag"))
                writer.add(events.createEndElement("w", W, "sdtPr"))
                writer.add(events.createStartElement("w", W, "sdtContent"))
            }
            row.forEach { event ->
                val text = if (event.isCharacters) event.asCharacters().data else null
                if (text != null && token in text) writer.add(events.createCharacters(pattern.replace(text, ""))) else writer.add(event)
            }
            if (tag != null) {
                writer.add(events.createEndElement("w", W, "sdtContent"))
                writer.add(events.createEndElement("w", W, "sdt"))
            }
        }
    }

    /** Holds each control that is a child of a table until its end, then writes it unless its tag is to be dropped. */
    private class Dropping(
        private val tags: Set<String>,
    ) : Pass {
        private val path = ArrayDeque<QName>()
        private var held: MutableList<XMLEvent>? = null
        private var depth = 0
        private var tag: String? = null

        override fun add(
            event: XMLEvent,
            writer: XMLEventWriter,
        ) {
            val sdt = held
            when {
                sdt != null -> {
                    hold(sdt, event, writer)
                }

                event.isStartElement && event.asStartElement().name == SDT && path.lastOrNull() == TBL -> {
                    held = mutableListOf(event)
                    depth = 1
                }

                else -> {
                    writer.add(event)
                    if (event.isStartElement) path.addLast(event.asStartElement().name)
                    if (event.isEndElement) path.removeLast()
                }
            }
        }

        private fun hold(
            sdt: MutableList<XMLEvent>,
            event: XMLEvent,
            writer: XMLEventWriter,
        ) {
            sdt += event
            if (event.isStartElement) {
                val start = event.asStartElement()
                if (start.name == SDT) depth++
                if (start.name == TAG_ELEMENT && tag == null && depth == 1) tag = start.getAttributeByName(VAL)?.value
            }
            if (event.isEndElement && event.asEndElement().name == SDT && --depth == 0) {
                if (tag !in tags) sdt.forEach(writer::add)
                held = null
                tag = null
            }
        }
    }

    /** The zip entry's stream stays open for the next entry. */
    private class KeepOpen(
        input: InputStream,
    ) : FilterInputStream(input) {
        override fun close() = Unit
    }

    private class KeepOpenOut(
        output: OutputStream,
    ) : FilterOutputStream(output) {
        override fun write(
            b: ByteArray,
            off: Int,
            len: Int,
        ) = out.write(b, off, len)

        override fun close() = flush()
    }
}
