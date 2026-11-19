package com.memo.templating.internal

import com.memo.common.poi.PoiLimits
import com.memo.templating.ContentControlException
import com.memo.templating.ControlParagraph
import org.apache.xmlbeans.XmlCursor
import org.apache.xmlbeans.XmlObject
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTBody
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTSdtBlock
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
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
 * Block-level content controls in a document body (ADR-0066). A control is `w:sdt` with `w:sdtPr` (id, alias = title,
 * tag, lock `sdtLocked`, no type = rich text) and `w:sdtContent`. Wrapping a filled document streams `document.xml`
 * (StAX, linear in its size); replacing and restoring one control use XMLBeans cursors on POI's `CTBody`.
 */
internal object ControlXml {
    const val W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private const val R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private const val XML = "http://www.w3.org/XML/1998/namespace"
    private const val DOCUMENT = "word/document.xml"
    private const val BODY_CHILD = 3
    private const val MISPLACED = "a block's markers are not both paragraphs of the body"
    private val P = QName(W, "p")
    private val T = QName(W, "t")
    private val random = SecureRandom()
    private val events = XMLEventFactory.newInstance()
    private val output = XMLOutputFactory.newFactory()

    /**
     * [docx] with what lies between each pair of marker paragraphs of the body wrapped in a control, and the markers
     * removed. A start marker's text is [start] followed by the control's tag, a space and its title; its end marker's
     * text is [end]. An empty tag means "no control": the markers are only removed. A marker anywhere else, or one
     * without its pair, fails.
     */
    fun wrapMarked(
        docx: ByteArray,
        start: String,
        end: String,
    ): ByteArray {
        PoiLimits.apply()
        val out = ByteArrayOutputStream(docx.size + docx.size / 4)
        ZipInputStream(ByteArrayInputStream(docx)).use { zip ->
            ZipOutputStream(out).use { result ->
                generateSequence { zip.nextEntry }.forEach { entry ->
                    result.putNextEntry(ZipEntry(entry.name))
                    if (entry.name == DOCUMENT) Wrapper(start, end).wrap(zip, result) else zip.transferTo(result)
                    result.closeEntry()
                }
            }
        }
        return out.toByteArray()
    }

    /** One pass over `document.xml`: body-level paragraphs are held until their text shows whether they are markers. */
    private class Wrapper(
        private val start: String,
        private val end: String,
    ) {
        private val token = start.dropLast(1)
        private var depth = 0
        private var held: MutableList<XMLEvent>? = null
        private val text = StringBuilder()
        private var inText = false

        /** The tag of the control being written, "" for a marked block without a control, null outside any block. */
        private var open: String? = null

        fun wrap(
            xml: InputStream,
            out: OutputStream,
        ) {
            // No DTDs and no external entities (set here, where the reader is made, so the check sees it).
            val factory = XMLInputFactory.newFactory()
            factory.setProperty(XMLInputFactory.SUPPORT_DTD, false)
            factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
            factory.setProperty(XMLInputFactory.IS_REPLACING_ENTITY_REFERENCES, false)
            factory.setProperty(XMLInputFactory.IS_COALESCING, true)
            val reader = factory.createXMLEventReader(KeepOpen(xml))
            val writer = output.createXMLEventWriter(KeepOpenOut(out), Charsets.UTF_8.name())
            while (reader.hasNext()) {
                val event = reader.nextEvent()
                if (event.isStartElement) depth++
                val paragraph = held
                when {
                    paragraph != null -> hold(paragraph, event, writer)
                    event.isStartElement && depth == BODY_CHILD && event.asStartElement().name == P -> held = mutableListOf(event)
                    else -> pass(event, writer)
                }
                if (event.isEndElement) depth--
            }
            check(open == null) { MISPLACED }
            writer.flush()
            reader.close()
        }

        private fun pass(
            event: XMLEvent,
            writer: XMLEventWriter,
        ) {
            check(!(event.isCharacters && token in event.asCharacters().data)) { MISPLACED }
            writer.add(event)
        }

        private fun hold(
            paragraph: MutableList<XMLEvent>,
            event: XMLEvent,
            writer: XMLEventWriter,
        ) {
            paragraph += event
            when {
                event.isStartElement && event.asStartElement().name == T -> inText = true
                event.isEndElement && event.asEndElement().name == T -> inText = false
                event.isCharacters && inText -> text.append(event.asCharacters().data)
            }
            if (event.isEndElement && depth == BODY_CHILD) {
                release(paragraph, writer)
                held = null
                text.setLength(0)
            }
        }

        private fun release(
            paragraph: List<XMLEvent>,
            writer: XMLEventWriter,
        ) {
            val line = text.toString()
            when {
                line.startsWith(start) -> {
                    check(open == null) { "a block started inside another" }
                    val mark = line.removePrefix(start)
                    val tag = mark.substringBefore(' ')
                    if (tag.isNotEmpty()) startControl(writer, tag, mark.substringAfter(' ', ""))
                    open = tag
                }

                line.startsWith(end) -> {
                    val tag = checkNotNull(open) { "a block ended without its start" }
                    if (tag.isNotEmpty()) {
                        writer.add(events.createEndElement("w", W, "sdtContent"))
                        writer.add(events.createEndElement("w", W, "sdt"))
                    }
                    open = null
                }

                else -> {
                    paragraph.forEach { pass(it, writer) }
                }
            }
        }

        private fun startControl(
            writer: XMLEventWriter,
            tag: String,
            title: String,
        ) {
            writer.add(events.createStartElement("w", W, "sdt"))
            writer.add(events.createStartElement("w", W, "sdtPr"))
            value(writer, "id", (random.nextInt(Int.MAX_VALUE - 1) + 1).toString())
            if (title.isNotEmpty()) value(writer, "alias", title)
            value(writer, "tag", tag)
            value(writer, "lock", "sdtLocked")
            writer.add(events.createEndElement("w", W, "sdtPr"))
            writer.add(events.createStartElement("w", W, "sdtContent"))
        }

        private fun value(
            writer: XMLEventWriter,
            name: String,
            value: String,
        ) {
            writer.add(events.createStartElement("w", W, name))
            writer.add(events.createAttribute("w", W, "val", value))
            writer.add(events.createEndElement("w", W, name))
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

    /** The children of [parent] in order. */
    private fun children(parent: XmlObject): List<XmlObject> =
        parent.newCursor().use { c ->
            val found = mutableListOf<XmlObject>()
            var more = c.toFirstChild()
            while (more) {
                found += c.getObject()
                more = c.toNextSibling()
            }
            found
        }

    /** The body-level controls, in document order. */
    fun controls(body: CTBody): List<CTSdtBlock> = children(body).filterIsInstance<CTSdtBlock>()

    fun tag(sdt: CTSdtBlock): String? =
        sdt.sdtPr
            ?.takeIf { it.isSetTag }
            ?.tag
            ?.`val`

    /** Replaces the content of [sdt] with [paragraphs] of plain text, keeping its properties. */
    fun replaceContent(
        sdt: CTSdtBlock,
        paragraphs: List<ControlParagraph>,
    ) {
        val content = sdt.sdtContent ?: sdt.addNewSdtContent()
        children(content).forEach { child -> child.newCursor().use { it.removeXml() } }
        paragraphs.ifEmpty { listOf(ControlParagraph("")) }.forEach { paragraph ->
            content.addNewP().addNewR().apply {
                if (paragraph.bold) addNewRPr().addNewB()
                addNewT().apply {
                    stringValue = paragraph.text
                    newCursor().use { it.setAttributeText(QName(XML, "space"), "preserve") }
                }
            }
        }
    }

    /** Copies [sdt] (from another document) into [body] after [after], or before the body's first control. */
    fun insertCopy(
        body: CTBody,
        sdt: CTSdtBlock,
        after: CTSdtBlock?,
    ) {
        if (refersToParts(sdt)) throw ContentControlException("the control refers to parts of its document")
        val anchor = after ?: controls(body).firstOrNull() ?: throw ContentControlException("no control to restore next to")
        anchor.newCursor().use { at ->
            if (after != null) {
                at.toEndToken()
                at.toNextToken()
            }
            sdt.newCursor().use { from -> from.copyXml(at) }
        }
    }

    /** Whether any element or attribute in [xml] is in the relationships namespace (`r:id`, `r:embed`, …). */
    private fun refersToParts(xml: XmlObject): Boolean =
        xml.newCursor().use { c ->
            xml.newCursor().use { end ->
                end.toEndToken()
                var refers = false
                while (!refers && c.comparePosition(end) < 0 && c.toNextToken() != XmlCursor.TokenType.NONE) {
                    refers = (c.isStart || c.isAttr) && c.name?.namespaceURI == R
                }
                refers
            }
        }
}
