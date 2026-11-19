package com.memo.templating.internal

import com.memo.templating.RefusalRule
import com.memo.templating.TableRow
import com.memo.templating.TagKind
import com.memo.templating.TemplateLocation
import com.memo.templating.TemplateProblem
import com.memo.templating.TemplateProblemCode
import com.memo.templating.TemplateRefusal
import com.memo.templating.TemplateScan
import com.memo.templating.TemplateTag
import org.apache.commons.compress.archivers.zip.ZipFile
import java.io.IOException
import java.io.InputStream
import java.nio.channels.SeekableByteChannel
import java.util.MissingResourceException
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants
import javax.xml.stream.XMLStreamException
import javax.xml.stream.XMLStreamReader

/**
 * Reads a .docx package without Apache POI (ADR-0048): the content types, every relationship and the text of the Word
 * parts, in one StAX pass each, with DTDs refused and every part read through a size bound. Finds what makes a template
 * unacceptable, and every tag with its location. Bad content becomes a refusal, never an exception.
 */
internal object DocxScanner {
    const val MAX_ENTRIES = 2_000
    const val MAX_PART_BYTES = 32L shl 20
    const val MAX_TAGS = 1_000

    fun scan(open: () -> SeekableByteChannel): TemplateScan =
        try {
            open().use { channel -> scanPackage(channel) }
        } catch (e: IOException) {
            unreadable(e)
        } catch (e: XMLStreamException) {
            unreadable(e)
        } catch (e: MissingResourceException) {
            // The JDK's StAX scanner reports some DTD errors under message keys its bundle lacks.
            unreadable(e)
        } catch (e: IllegalArgumentException) {
            unreadable(e)
        }

    private fun unreadable(cause: Exception): TemplateScan {
        val where = if (cause is Unreadable) cause.message.orEmpty() else cause.javaClass.simpleName
        return TemplateScan(listOf(TemplateRefusal(RefusalRule.NOT_A_DOCUMENT, where)), emptyList(), emptyList())
    }

    /** The package is not one we read: [where] says what is missing or too large. */
    private class Unreadable(
        where: String,
    ) : IOException(where)

    private fun scanPackage(channel: SeekableByteChannel): TemplateScan {
        val zip =
            ZipFile
                .builder()
                .setSeekableByteChannel(channel)
                .setIgnoreLocalFileHeader(true)
                .get()
        val entries = zip.entries.toList()
        if (entries.size > MAX_ENTRIES) throw Unreadable("entries")
        val names = entries.map { it.name }.toSet()
        val contentTypesEntry = zip.getEntry(CONTENT_TYPES) ?: throw Unreadable(CONTENT_TYPES)
        val types = zip.getInputStream(contentTypesEntry).bounded().use { ContentTypes.read(it) }
        val refusals = mutableListOf<TemplateRefusal>()
        refusals += Packages.refusedNames(names)
        refusals += Packages.refusedTypes(types)
        names.filter { it.endsWith(".rels") }.sorted().forEach { name ->
            zip.getInputStream(zip.getEntry(name)).bounded().use { refusals += Relationships.refused(name, it) }
        }
        val parts = WordParts.of(types, names)
        if (parts.none { it.role == WordParts.Role.BODY }) refusals += TemplateRefusal(RefusalRule.NOT_A_DOCUMENT, "main document")
        val reader = PartReader()
        parts.forEach { part ->
            zip.getInputStream(zip.getEntry(part.name)).bounded().use { reader.read(part, it) }
        }
        return TemplateScan(refusals + reader.refusals, reader.tags, reader.problems)
    }

    const val CONTENT_TYPES = "[Content_Types].xml"

    /** A StAX reader over [input] from a hardened factory: no DTDs, no external entities, no entity replacement. */
    fun xml(input: InputStream): XMLStreamReader {
        val factory = XMLInputFactory.newFactory()
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false)
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
        factory.setProperty(XMLInputFactory.IS_REPLACING_ENTITY_REFERENCES, false)
        factory.setProperty(XMLInputFactory.IS_COALESCING, true)
        return factory.createXMLStreamReader(input)
    }

    /** Walks [reader] to the end, calling [onStart] for every element; a DTD fails the read. */
    inline fun XMLStreamReader.elements(onStart: (XMLStreamReader) -> Unit) {
        while (hasNext()) {
            val event = next()
            if (event == XMLStreamConstants.DTD) throw XMLStreamException("DTD")
            if (event == XMLStreamConstants.START_ELEMENT) onStart(this)
        }
    }

    /** Fails once more than [max] bytes come out: declared entry sizes can lie. */
    fun InputStream.bounded(max: Long = MAX_PART_BYTES): InputStream {
        val source = this
        return object : InputStream() {
            private var count = 0L

            override fun read(): Int = source.read().also { if (it >= 0) check(1) }

            override fun read(
                b: ByteArray,
                off: Int,
                len: Int,
            ): Int = source.read(b, off, len).also { if (it > 0) check(it.toLong()) }

            private fun check(n: Long) {
                count += n
                if (count > max) throw IOException("part larger than $max bytes")
            }

            override fun close() = source.close()
        }
    }
}

/** `[Content_Types].xml`: defaults by extension and overrides by part name (without the leading slash). */
internal data class ContentTypes(
    val defaults: Map<String, String>,
    val overrides: Map<String, String>,
) {
    fun of(partName: String): String? = overrides[partName] ?: defaults[partName.substringAfterLast('.', "").lowercase()]

    companion object {
        fun read(input: InputStream): ContentTypes {
            val defaults = mutableMapOf<String, String>()
            val overrides = mutableMapOf<String, String>()
            with(DocxScanner) {
                xml(input).elements { r ->
                    val type = r.getAttributeValue(null, "ContentType")?.lowercase()
                    when (r.localName) {
                        "Default" -> {
                            r.getAttributeValue(null, "Extension")?.let { if (type != null) defaults[it.lowercase()] = type }
                        }

                        "Override" -> {
                            r.getAttributeValue(null, "PartName")?.let {
                                if (type !=
                                    null
                                ) {
                                    overrides[it.removePrefix("/")] = type
                                }
                            }
                        }
                    }
                }
            }
            return ContentTypes(defaults, overrides)
        }
    }
}

/** Refusals by part name and content type: macros, ActiveX and embedded objects, whatever the relationships say. */
internal object Packages {
    private val MACRO_NAMES = Regex("(?i)(^|/)(vbaProject[^/]*\\.bin|vbaData\\.xml)$")
    private val ACTIVEX_NAMES = Regex("(?i)^word/activeX/")
    private val EMBEDDED_NAMES = Regex("(?i)^word/embeddings/")

    fun refusedNames(names: Set<String>): List<TemplateRefusal> =
        names.sorted().mapNotNull { name ->
            when {
                MACRO_NAMES.containsMatchIn(name) -> TemplateRefusal(RefusalRule.MACROS, name)
                ACTIVEX_NAMES.containsMatchIn(name) -> TemplateRefusal(RefusalRule.ACTIVEX, name)
                EMBEDDED_NAMES.containsMatchIn(name) -> TemplateRefusal(RefusalRule.OLE_OBJECT, name)
                else -> null
            }
        }

    fun refusedTypes(types: ContentTypes): List<TemplateRefusal> =
        (types.overrides.entries.map { it.key to it.value } + types.defaults.entries.map { "*.${it.key}" to it.value })
            .sortedBy { it.first }
            .mapNotNull { (where, type) ->
                when {
                    "macroenabled" in type || "vbaproject" in type || "vbadata" in type -> TemplateRefusal(RefusalRule.MACROS, where)
                    "activex" in type -> TemplateRefusal(RefusalRule.ACTIVEX, where)
                    "oleobject" in type -> TemplateRefusal(RefusalRule.OLE_OBJECT, where)
                    else -> null
                }
            }
}

/**
 * Relationships (`*.rels`): known-dangerous types are refused with their rule, external targets other than hyperlinks
 * are refused, and a type that is neither known-safe nor known-dangerous is refused as unsupported (an allow-list).
 * Types are matched by their last path segments, so transitional and strict namespaces read the same.
 */
internal object Relationships {
    private val SAFE =
        setOf(
            "officeDocument",
            "styles",
            "stylesWithEffects",
            "settings",
            "webSettings",
            "fontTable",
            "font",
            "theme",
            "numbering",
            "footnotes",
            "endnotes",
            "header",
            "footer",
            "image",
            "hdphoto",
            "hyperlink",
            "comments",
            "commentsExtended",
            "commentsIds",
            "commentsExtensible",
            "people",
            "customXml",
            "customXmlProps",
            "metadata/core-properties",
            "extended-properties",
            "custom-properties",
            "metadata/thumbnail",
            "digital-signature/origin",
            "digital-signature/signature",
            "glossaryDocument",
            "printerSettings",
            "diagramData",
            "diagramLayout",
            "diagramQuickStyle",
            "diagramColors",
            "diagramDrawing",
            "chart",
            "chartStyle",
            "chartColorStyle",
            "chartUserShapes",
            "classificationlabels",
            "bibliography",
        )
    private val REFUSED =
        mapOf(
            "vbaProject" to RefusalRule.MACROS,
            "wordVbaData" to RefusalRule.MACROS,
            "keyMapCustomizations" to RefusalRule.MACROS,
            "control" to RefusalRule.ACTIVEX,
            "activeXControlBinary" to RefusalRule.ACTIVEX,
            "oleObject" to RefusalRule.OLE_OBJECT,
            "package" to RefusalRule.OLE_OBJECT,
            "aFChunk" to RefusalRule.ALT_CHUNK,
            "attachedTemplate" to RefusalRule.ATTACHED_TEMPLATE,
            "mailMergeSource" to RefusalRule.MAIL_MERGE,
            "mailMergeHeaderSource" to RefusalRule.MAIL_MERGE,
            "recipientData" to RefusalRule.MAIL_MERGE,
        )

    fun refused(
        relsName: String,
        input: InputStream,
    ): List<TemplateRefusal> {
        val found = mutableListOf<TemplateRefusal>()
        with(DocxScanner) {
            xml(input).elements { r ->
                if (r.localName == "Relationship") {
                    val type = r.getAttributeValue(null, "Type").orEmpty()
                    val target = r.getAttributeValue(null, "Target").orEmpty()
                    val external = r.getAttributeValue(null, "TargetMode").equals("External", ignoreCase = true)
                    rule(type, external)?.let { found += TemplateRefusal(it, "$relsName → ${target.take(MAX_TARGET)}") }
                }
            }
        }
        return found
    }

    private fun rule(
        type: String,
        external: Boolean,
    ): RefusalRule? {
        val last = type.substringAfterLast('/')
        val lastTwo = type.split('/').takeLast(2).joinToString("/")
        return REFUSED[last]
            ?: when {
                external && last != "hyperlink" -> RefusalRule.EXTERNAL_RELATIONSHIP
                last in SAFE || lastTwo in SAFE -> null
                else -> RefusalRule.UNSUPPORTED_PART
            }
    }

    private const val MAX_TARGET = 120
}

/** The Word parts whose text we read, in the order an admin reads them, with their names for problem locations. */
internal object WordParts {
    enum class Role { BODY, HEADER, FOOTER, FOOTNOTES, ENDNOTES, SETTINGS, OTHER }

    data class Part(
        val name: String,
        val role: Role,
        val label: String,
    )

    private const val WML = "application/vnd.openxmlformats-officedocument.wordprocessingml."

    fun of(
        types: ContentTypes,
        names: Set<String>,
    ): List<Part> {
        val byRole =
            names
                .mapNotNull { name -> types.of(name)?.let { name to roleOf(it) } }
                .filter { it.second != null }
                .groupBy({ it.second!! }, { it.first })
        return Role.entries.flatMap { role ->
            byRole[role].orEmpty().sortedWith(compareBy({ it.length }, { it })).mapIndexed {
                i,
                name,
                ->
                Part(name, role, label(role, i, name))
            }
        }
    }

    private fun roleOf(type: String): Role? =
        when (type) {
            "${WML}document.main+xml", "${WML}template.main+xml" -> Role.BODY
            "${WML}header+xml" -> Role.HEADER
            "${WML}footer+xml" -> Role.FOOTER
            "${WML}footnotes+xml" -> Role.FOOTNOTES
            "${WML}endnotes+xml" -> Role.ENDNOTES
            "${WML}settings+xml" -> Role.SETTINGS
            "${WML}comments+xml", "${WML}document.glossary+xml" -> Role.OTHER
            else -> null
        }

    private fun label(
        role: Role,
        index: Int,
        name: String,
    ): String =
        when (role) {
            Role.BODY -> "Body"
            Role.HEADER -> "Header ${index + 1}"
            Role.FOOTER -> "Footer ${index + 1}"
            Role.FOOTNOTES -> "Footnotes"
            Role.ENDNOTES -> "Endnotes"
            Role.SETTINGS -> "Settings"
            Role.OTHER -> name
        }

    /** Parts whose tags poi-tl fills; the others are read for field codes and settings only. */
    fun hasTags(role: Role) = role in setOf(Role.BODY, Role.HEADER, Role.FOOTER, Role.FOOTNOTES, Role.ENDNOTES)
}

/** Collects what [PartWalk]s find across the parts of one package. */
internal class PartReader {
    val refusals = mutableListOf<TemplateRefusal>()
    val tags = mutableListOf<TemplateTag>()
    val problems = mutableListOf<TemplateProblem>()

    fun read(
        part: WordParts.Part,
        input: InputStream,
    ) {
        val walk = PartWalk(part, this)
        val r = DocxScanner.xml(input)
        while (r.hasNext()) walk.on(r.next(), r)
    }

    fun refuse(
        rule: RefusalRule,
        where: String,
    ) {
        refusals += TemplateRefusal(rule, where)
    }

    fun add(tag: TemplateTag) {
        if (tags.size < DocxScanner.MAX_TAGS) {
            tags += tag
        } else if (problems.none { it.code == TemplateProblemCode.TOO_MANY_TAGS }) {
            problems += TemplateProblem(TemplateProblemCode.TOO_MANY_TAGS, tag.location, tag.excerpt)
        }
    }

    fun problem(p: TemplateProblem) {
        if (problems.size < DocxScanner.MAX_TAGS) problems += p
    }
}

/**
 * One StAX pass over a Word part: paragraph text (`w:t`; nested paragraphs of text boxes on their own), field
 * instructions (`w:instrText`, `w:fldSimple`), tables and rows, and the settings that pull in other content. The
 * `mc:Fallback` copy of alternate content is skipped, so a text box is read once.
 */
internal class PartWalk(
    private val part: WordParts.Part,
    private val out: PartReader,
) {
    private class Paragraph(
        val number: Int,
        val row: TableRow?,
        /** Directly in the body: not in a table, a text box (another paragraph) or a content control. */
        val bodyLevel: Boolean,
        val text: StringBuilder = StringBuilder(),
    )

    private class Table(
        val number: Int,
        var row: Int = 0,
    )

    /** An open field: its instruction so far, and whether it was already judged (at `separate`). */
    private class Field(
        val instruction: StringBuilder = StringBuilder(),
        var judged: Boolean = false,
    )

    private val paragraphs = ArrayDeque<Paragraph>()
    private val tables = ArrayDeque<Table>()
    private val fields = ArrayDeque<Field>()
    private var paragraphCount = 0
    private var tableCount = 0
    private var textDepth = 0
    private var instrDepth = 0
    private var fallbackDepth = 0
    private var controlDepth = 0
    private val collect = WordParts.hasTags(part.role)

    fun on(
        event: Int,
        r: XMLStreamReader,
    ) {
        when (event) {
            XMLStreamConstants.DTD -> throw XMLStreamException("DTD")
            XMLStreamConstants.START_ELEMENT -> if (!enteredFallback(r) && r.namespaceURI in W) start(r)
            XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA -> if (fallbackDepth == 0) text(r.text)
            XMLStreamConstants.END_ELEMENT -> if (!leftFallback() && r.namespaceURI in W) end(r.localName)
            else -> Unit
        }
    }

    private fun enteredFallback(r: XMLStreamReader): Boolean {
        if (fallbackDepth > 0 || (r.localName == "Fallback" && r.namespaceURI == MC)) fallbackDepth++
        return fallbackDepth > 0
    }

    private fun leftFallback(): Boolean {
        if (fallbackDepth == 0) return false
        fallbackDepth--
        return true
    }

    private fun start(r: XMLStreamReader) {
        when (r.localName) {
            "p", "sdtContent" -> {
                open(r.localName)
            }

            "tbl" -> {
                tables.addLast(Table(++tableCount))
            }

            "tr" -> {
                tables.lastOrNull()?.let { it.row++ }
            }

            "t" -> {
                textDepth++
            }

            "instrText" -> {
                instrDepth++
            }

            "fldChar" -> {
                fieldChar(r.getAttributeValue(r.namespaceURI, "fldCharType"))
            }

            "fldSimple" -> {
                judge(r.getAttributeValue(r.namespaceURI, "instr").orEmpty())
            }

            "mailMerge" -> {
                if (part.role == WordParts.Role.SETTINGS) out.refuse(RefusalRule.MAIL_MERGE, part.name)
            }

            "attachedTemplate" -> {
                if (part.role == WordParts.Role.SETTINGS) out.refuse(RefusalRule.ATTACHED_TEMPLATE, part.name)
            }
        }
    }

    private fun end(name: String) {
        when (name) {
            "p" -> paragraphs.removeLastOrNull()?.let { if (collect) tagsOf(it) }
            "tbl" -> tables.removeLastOrNull()
            "t" -> textDepth--
            "sdtContent" -> controlDepth--
            "instrText" -> instrDepth--
        }
    }

    private fun text(chars: String) {
        if (textDepth > 0) paragraphs.lastOrNull()?.text?.append(chars)
        if (instrDepth > 0) {
            fields
                .lastOrNull()
                ?.takeUnless { it.judged }
                ?.instruction
                ?.append(chars)
        }
    }

    private val currentRow: TableRow? get() = tables.lastOrNull()?.let { TableRow(part.label, it.number, it.row) }

    /** A paragraph (directly in the body when nothing encloses it) or a content control's content opens. */
    private fun open(name: String) {
        if (name == "sdtContent") {
            controlDepth++
        } else {
            paragraphs.addLast(Paragraph(++paragraphCount, currentRow, paragraphs.isEmpty() && tables.isEmpty() && controlDepth == 0))
        }
    }

    /** `begin` opens a field; its instruction is complete at `separate`, or at `end` when there is no result. */
    private fun fieldChar(type: String?) {
        when (type) {
            "begin" -> {
                fields.addLast(Field())
            }

            "separate" -> {
                fields.lastOrNull()?.takeUnless { it.judged }?.let {
                    it.judged = true
                    judge(it.instruction.toString())
                }
            }

            "end" -> {
                fields.removeLastOrNull()?.takeUnless { it.judged }?.let { judge(it.instruction.toString()) }
            }
        }
    }

    /** A field instruction is refused when it pulls in content from elsewhere or runs something. */
    private fun judge(instruction: String) {
        val name = instruction.trim().substringBefore(' ').uppercase()
        if (name in
            REFUSED_FIELDS
        ) {
            out.refuse(RefusalRule.FIELD_CODE, "${part.label}, paragraph ${paragraphs.lastOrNull()?.number ?: 0}: $name")
        }
    }

    private fun tagsOf(paragraph: Paragraph) {
        val text = paragraph.text.toString()
        if ('{' !in text && '}' !in text && '[' !in text) return
        val location = TemplateLocation(part.label, paragraph.number)
        val rest = StringBuilder(text)
        TAG.findAll(text).forEach { match ->
            for (i in match.range) rest.setCharAt(i, ' ')
            tag(location, paragraph, text, match)
        }
        if ("{{" in rest || "}}" in rest) {
            val at = maxOf(rest.indexOf("{{"), rest.indexOf("}}"))
            out.problem(TemplateProblem(TemplateProblemCode.MALFORMED_TAG, location, excerpt(text, at)))
        }
        paragraph.row?.let { row ->
            FIELD.findAll(text).forEach {
                out.add(TemplateTag(TagKind.ROW_FIELD, it.groupValues[1], location, row, excerpt(text, it.range.first)))
            }
        }
    }

    private fun tag(
        location: TemplateLocation,
        paragraph: Paragraph,
        text: String,
        match: MatchResult,
    ) {
        val content = match.groupValues[1]
        val excerpt = excerpt(text, match.range.first)
        val sign = content.firstOrNull()?.takeIf { it in SIGNS }
        val name = if (sign == null) content else content.substring(1)
        val kind =
            when (sign) {
                null -> TagKind.VALUE
                '?' -> TagKind.SECTION_START
                '/' -> TagKind.SECTION_END
                else -> null
            }
        when {
            kind == null -> {
                out.problem(TemplateProblem(TemplateProblemCode.UNSUPPORTED_TAG, location, excerpt, tag = match.value))
            }

            !NAME.matches(name) -> {
                val cleaned = name.filterNot { it.isWhitespace() }
                val suggestion = if (NAME.matches(cleaned)) "{{${sign ?: ""}$cleaned}}" else null
                out.problem(TemplateProblem(TemplateProblemCode.MALFORMED_TAG, location, excerpt, match.value, suggestion))
            }

            else -> {
                val standalone = paragraph.bodyLevel && part.role == WordParts.Role.BODY && text.trim() == match.value
                out.add(TemplateTag(kind, name, location, paragraph.row, excerpt, standalone))
            }
        }
    }

    companion object {
        private val W =
            setOf(
                "http://schemas.openxmlformats.org/wordprocessingml/2006/main",
                "http://purl.oclc.org/ooxml/wordprocessingml/main",
            )
        private const val MC = "http://schemas.openxmlformats.org/markup-compatibility/2006"
        private val TAG = Regex("""\{\{([^{}]*)}}""")
        private val FIELD = Regex("""\[([A-Za-z][A-Za-z0-9_]*)]""")

        /** Tag names: ASCII, dotted. */
        val NAME = Regex("""[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)*""")
        private const val SIGNS = "?/@#*+=>"
        private val REFUSED_FIELDS =
            setOf(
                "INCLUDETEXT",
                "INCLUDEPICTURE",
                "INCLUDE",
                "LINK",
                "DDE",
                "DDEAUTO",
                "IMPORT",
                "MACROBUTTON",
                "DATABASE",
                "ADDIN",
                "EMBED",
            )

        /** At most [TemplateProblem.EXCERPT_CHARS] of [text] around [at]. */
        fun excerpt(
            text: String,
            at: Int,
        ): String {
            val half = TemplateProblem.EXCERPT_CHARS / 2
            val start = (at - half).coerceIn(0, maxOf(0, text.length - TemplateProblem.EXCERPT_CHARS))
            return text.substring(start, minOf(text.length, start + TemplateProblem.EXCERPT_CHARS)).trim()
        }
    }
}
