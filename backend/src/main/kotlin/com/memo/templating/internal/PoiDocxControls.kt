package com.memo.templating.internal

import com.memo.common.poi.PoiLimits
import com.memo.templating.ContentControlException
import com.memo.templating.ControlParagraph
import com.memo.templating.DocumentMarks
import com.memo.templating.DocxControls
import com.memo.templating.FoundControl
import org.apache.poi.xwpf.usermodel.XWPFDocument
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTSdtBlock
import org.springframework.stereotype.Component
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipInputStream
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants
import javax.xml.stream.XMLStreamException
import javax.xml.stream.XMLStreamReader

/**
 * [DocxControls] on Apache POI (ADR-0066): [scan] streams `word/document.xml` with StAX (no DTDs, no external entities,
 * inflated size bounded by [PoiLimits.MAX_ENTRY_BYTES]); [replace] and [restore] open the package with POI under the
 * same limits and edit the body with [ControlXml].
 */
@Component
internal class PoiDocxControls : DocxControls {
    init {
        PoiLimits.apply()
    }

    override fun scan(docx: InputStream): List<FoundControl> = body(docx, ::read)

    override fun marks(docx: InputStream): DocumentMarks =
        body(docx) { xml ->
            var tracked = 0
            var comments = 0
            stream(xml) { reader ->
                if (reader.eventType == XMLStreamConstants.START_ELEMENT && reader.namespaceURI == W) {
                    when (reader.localName) {
                        in TRACKED -> tracked++
                        COMMENT_REFERENCE -> comments++
                    }
                }
            }
            DocumentMarks(tracked, comments)
        }

    override fun refill(
        docx: InputStream,
        source: InputStream,
        tag: String,
    ): ByteArray =
        open(source).use { from ->
            val copy = only(ControlXml.controls(from.document.body), tag)
            edit(docx) { document ->
                val body = document.document.body
                val target = only(ControlXml.controls(body), tag)
                ControlXml.insertCopy(body, copy, target)
                ControlXml.remove(target)
            }
        }

    /** [read] over the package's `word/document.xml`, bounded as POI's packages are. */
    private fun <T> body(
        docx: InputStream,
        read: (InputStream) -> T,
    ): T =
        try {
            ZipInputStream(docx).use { zip ->
                generateSequence { zip.nextEntry }.firstOrNull { it.name == DOCUMENT } ?: throw ContentControlException("no $DOCUMENT")
                read(Bounded(zip, PoiLimits.MAX_ENTRY_BYTES))
            }
        } catch (e: IOException) {
            throw ContentControlException("not a readable document", e)
        } catch (e: XMLStreamException) {
            throw ContentControlException("not a readable document", e)
        }

    override fun replace(
        docx: InputStream,
        contents: Map<String, List<ControlParagraph>>,
    ): ByteArray =
        edit(docx) { document ->
            val controls = ControlXml.controls(document.document.body)
            contents.forEach { (tag, paragraphs) -> ControlXml.replaceContent(only(controls, tag), paragraphs) }
        }

    override fun restore(
        docx: InputStream,
        source: InputStream,
        tag: String,
        after: String?,
    ): ByteArray =
        open(source).use { from ->
            val copy = only(ControlXml.controls(from.document.body), tag)
            edit(docx) { document ->
                val body = document.document.body
                val controls = ControlXml.controls(body)
                if (controls.any { ControlXml.tag(it) == tag }) throw ContentControlException("the control is already there")
                ControlXml.insertCopy(body, copy, after?.let { only(controls, it) })
            }
        }

    private fun only(
        controls: List<CTSdtBlock>,
        tag: String,
    ): CTSdtBlock = controls.singleOrNull { ControlXml.tag(it) == tag } ?: throw ContentControlException("no single control tagged so")

    private fun edit(
        docx: InputStream,
        change: (XWPFDocument) -> Unit,
    ): ByteArray =
        open(docx).use { document ->
            change(document)
            ByteArrayOutputStream().also { document.write(it) }.toByteArray()
        }

    private fun open(docx: InputStream): XWPFDocument =
        try {
            XWPFDocument(docx)
        } catch (e: IOException) {
            throw ContentControlException("not a readable document", e)
        } catch (
            @Suppress("TooGenericExceptionCaught") e: RuntimeException,
        ) {
            // POI reports broken packages with several unchecked exceptions (POIXMLException, NotOfficeXmlFileException, …).
            throw ContentControlException("not a readable document", e)
        }

    /** The controls in [xml], in the order their starts appear. */
    private fun read(xml: InputStream): List<FoundControl> {
        val found = mutableListOf<Scanned>()
        val open = ArrayDeque<Scanned>()
        val path = ArrayDeque<String>()
        stream(xml) { reader ->
            when (reader.eventType) {
                XMLStreamConstants.START_ELEMENT -> start(reader, path, open, found)
                XMLStreamConstants.END_ELEMENT -> if (path.removeLast() == SDT) open.removeLast()
                else -> Unit
            }
        }
        return found.map { FoundControl(it.tag, it.lock, it.title, it.bodyLevel) }
    }

    private fun start(
        reader: XMLStreamReader,
        path: ArrayDeque<String>,
        open: ArrayDeque<Scanned>,
        found: MutableList<Scanned>,
    ) {
        val name = if (reader.namespaceURI == W) reader.localName else "?"
        val parent = path.lastOrNull()
        val inProperties = parent == SDT_PR && path.getOrNull(path.size - 2) == SDT
        when {
            name == SDT -> Scanned(bodyLevel = parent == BODY).also { open.addLast(it) }.also { found += it }
            inProperties && name == TAG -> open.last().tag = reader.getAttributeValue(W, VAL)
            inProperties && name == LOCK -> open.last().lock = reader.getAttributeValue(W, VAL)
            inProperties && name == ALIAS -> open.last().title = reader.getAttributeValue(W, VAL)
        }
        path.addLast(name)
    }

    private class Scanned(
        val bodyLevel: Boolean,
        var tag: String? = null,
        var lock: String? = null,
        var title: String? = null,
    )

    /** Fails once more than [limit] bytes are read, like POI's zip-bomb check. */
    private class Bounded(
        input: InputStream,
        private val limit: Long,
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
            if (count > limit) throw IOException("$DOCUMENT is larger than $limit bytes")
        }

        override fun close() = Unit
    }

    private companion object {
        const val DOCUMENT = "word/document.xml"
        const val W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
        const val SDT = "sdt"
        const val SDT_PR = "sdtPr"
        const val BODY = "body"
        const val TAG = "tag"
        const val LOCK = "lock"
        const val ALIAS = "alias"
        const val VAL = "val"
        const val COMMENT_REFERENCE = "commentReference"

        /** Revision marks (ECMA-376 §17.13.5): what review mode leaves for the secretary to accept or reject. */
        val TRACKED =
            setOf(
                "ins",
                "del",
                "moveFrom",
                "moveTo",
                "pPrChange",
                "rPrChange",
                "sectPrChange",
                "tblPrChange",
                "trPrChange",
                "tcPrChange",
                "tblGridChange",
                "numberingChange",
                "cellIns",
                "cellDel",
                "cellMerge",
            )
    }
}

/** Calls [each] at every event of [xml], read with StAX without DTDs or external entities. */
private fun stream(
    xml: InputStream,
    each: (XMLStreamReader) -> Unit,
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
