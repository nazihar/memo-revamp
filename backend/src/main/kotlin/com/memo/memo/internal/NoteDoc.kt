package com.memo.memo.internal

import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.ObjectNode
import java.net.URI
import java.util.UUID

/**
 * A note's body, format `noteDoc/v1` (ADR-0049): the ProseMirror JSON Tiptap writes, limited to paragraphs, bullet and
 * ordered lists (nested at most [NoteDoc.MAX_LIST_DEPTH]), bold, italic, `https:` links, hard breaks, mentions and memo
 * references. [NoteDoc.parse] turns JSON into this tree or lists every problem with its path; the same tree gives the
 * stored JSON (canonical), the plain text, the mentions and, in E04, the PDF.
 */
sealed interface NoteBlock

data class NoteParagraph(
    val inlines: List<NoteInline>,
) : NoteBlock

data class NoteList(
    val ordered: Boolean,
    /** An ordered list's first number; null: 1. */
    val start: Int?,
    val items: List<List<NoteBlock>>,
) : NoteBlock

sealed interface NoteInline

data class NoteText(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    /** An `https:` URL. */
    val link: String? = null,
) : NoteInline

data object NoteBreak : NoteInline

data class NoteMention(
    val userId: UUID,
    /** The name as it was when written. */
    val label: String,
) : NoteInline

data class NoteMemoRef(
    val memoId: UUID,
    /** The memo's number (or subject) as it was when written. */
    val label: String,
) : NoteInline

data class NoteDocument(
    val blocks: List<NoteBlock>,
) {
    /** Plain text: blocks on their own lines, list items marked, mentions as `@name`. */
    fun text(): String = blocks.joinToString("\n") { NoteDoc.text(it, 0) }.trim()

    fun mentions(): Set<UUID> = inlines().filterIsInstance<NoteMention>().map { it.userId }.toSet()

    fun memoRefs(): Set<UUID> = inlines().filterIsInstance<NoteMemoRef>().map { it.memoId }.toSet()

    fun inlines(): List<NoteInline> = blocks.flatMap(NoteDoc::inlines)

    companion object {
        /** A body of one paragraph, for remarks written as plain text (the older API, and actions before E03). */
        fun ofText(text: String): NoteDocument =
            NoteDocument(
                text.trim().split('\n').map { line -> NoteParagraph(listOfNotNull(line.takeIf { it.isNotEmpty() }?.let(::NoteText))) },
            )
    }
}

/** One thing wrong with a note's JSON: where (a JSON path like `content[0].marks[1].attrs.href`) and what. */
data class NoteProblem(
    val path: String,
    val code: String,
)

class NoteRejectedException(
    val problems: List<NoteProblem>,
) : RuntimeException("note rejected: ${problems.take(SHOWN).joinToString { "${it.path}:${it.code}" }}") {
    private companion object {
        const val SHOWN = 5
    }
}

object NoteDoc {
    const val SCHEMA = 1
    const val MAX_JSON_BYTES = 64 * 1024
    const val MAX_NODES = 2_000
    const val MAX_TEXT = 4_000
    const val MAX_LIST_DEPTH = 3
    const val MAX_EACH = 20
    private const val MAX_LABEL = 200
    private const val MAX_URL = 2_000
    private const val MAX_PROBLEMS = 50

    /** Parses [json] into a document, or throws [NoteRejectedException] with every problem found (at most 50). */
    fun parse(json: JsonNode): NoteDocument {
        val parser = Parser()
        val doc = parser.doc(json)
        // Limits apply to a document; for anything else the one problem is enough.
        val notADocument = parser.problems.any { it.code == "NOT_A_DOCUMENT" }
        val problems = parser.problems + if (notADocument) emptyList() else parser.limits(doc)
        if (problems.isNotEmpty()) throw NoteRejectedException(problems.take(MAX_PROBLEMS))
        return doc
    }

    /** The canonical JSON of [doc], as stored: only the keys the format has, in a fixed order. */
    fun json(
        doc: NoteDocument,
        mapper: JsonMapper,
    ): ObjectNode =
        mapper.createObjectNode().apply {
            put("type", "doc")
            set("content", blocks(doc.blocks, mapper))
        }

    private fun blocks(
        blocks: List<NoteBlock>,
        mapper: JsonMapper,
    ): ArrayNode =
        mapper.createArrayNode().apply {
            blocks.forEach { block ->
                add(
                    when (block) {
                        is NoteParagraph -> {
                            mapper.createObjectNode().apply {
                                put("type", "paragraph")
                                if (block.inlines.isNotEmpty()) set("content", inlines(block.inlines, mapper))
                            }
                        }

                        is NoteList -> {
                            mapper.createObjectNode().apply {
                                put("type", if (block.ordered) "orderedList" else "bulletList")
                                val start = block.start
                                if (block.ordered && start != null) set("attrs", mapper.createObjectNode().put("start", start))
                                set(
                                    "content",
                                    mapper.createArrayNode().apply {
                                        block.items.forEach { item ->
                                            add(mapper.createObjectNode().put("type", "listItem").set("content", blocks(item, mapper)))
                                        }
                                    },
                                )
                            }
                        }
                    },
                )
            }
        }

    private fun inlines(
        inlines: List<NoteInline>,
        mapper: JsonMapper,
    ): ArrayNode =
        mapper.createArrayNode().apply {
            inlines.forEach { inline ->
                add(
                    when (inline) {
                        is NoteText -> {
                            mapper.createObjectNode().apply {
                                put("type", "text")
                                put("text", inline.text)
                                val marks = mapper.createArrayNode()
                                if (inline.bold) marks.add(mapper.createObjectNode().put("type", "bold"))
                                if (inline.italic) marks.add(mapper.createObjectNode().put("type", "italic"))
                                inline.link?.let {
                                    marks.add(
                                        mapper
                                            .createObjectNode()
                                            .put(
                                                "type",
                                                "link",
                                            ).set("attrs", mapper.createObjectNode().put("href", it)),
                                    )
                                }
                                if (!marks.isEmpty) set("marks", marks)
                            }
                        }

                        NoteBreak -> {
                            mapper.createObjectNode().put("type", "hardBreak")
                        }

                        is NoteMention -> {
                            mapper.createObjectNode().put("type", "mention").set(
                                "attrs",
                                mapper.createObjectNode().put("id", inline.userId.toString()).put("label", inline.label),
                            )
                        }

                        is NoteMemoRef -> {
                            mapper.createObjectNode().put("type", "memoRef").set(
                                "attrs",
                                mapper.createObjectNode().put("id", inline.memoId.toString()).put("label", inline.label),
                            )
                        }
                    },
                )
            }
        }

    internal fun inlines(block: NoteBlock): List<NoteInline> =
        when (block) {
            is NoteParagraph -> block.inlines
            is NoteList -> block.items.flatten().flatMap(::inlines)
        }

    internal fun text(
        block: NoteBlock,
        depth: Int,
    ): String =
        when (block) {
            is NoteParagraph -> {
                block.inlines.joinToString("") {
                    when (it) {
                        is NoteText -> it.text
                        NoteBreak -> "\n"
                        is NoteMention -> "@${it.label}"
                        is NoteMemoRef -> it.label
                    }
                }
            }

            is NoteList -> {
                block.items.withIndex().joinToString("\n") { (i, item) ->
                    val marker = if (block.ordered) "${(block.start ?: 1) + i}. " else "• "
                    "  ".repeat(depth) + marker + item.joinToString("\n") { text(it, depth + 1) }.trimStart()
                }
            }
        }

    /** Walks the JSON, building the tree and collecting problems; unknown types and keys are problems, never dropped. */
    private class Parser {
        val problems = mutableListOf<NoteProblem>()
        private val checks = Checks(::problem)
        private var nodes = 0

        fun doc(json: JsonNode): NoteDocument {
            if (!json.isObject || json["type"]?.asString() != "doc") {
                problem("", "NOT_A_DOCUMENT")
                return NoteDocument(emptyList())
            }
            checks.keys(json, "", setOf("type", "content"))
            return NoteDocument(blocks(json["content"], "content", 0))
        }

        private fun blocks(
            content: JsonNode?,
            path: String,
            listDepth: Int,
        ): List<NoteBlock> = list(content, path).mapIndexedNotNull { i, node -> block(node, "$path[$i]", listDepth) }

        /** The elements of [content]: none when it is missing, and none with a problem when it is not a list. */
        private fun list(
            content: JsonNode?,
            path: String,
        ): List<JsonNode> {
            if (content != null && !content.isArray) problem(path, "NOT_A_LIST")
            return if (content != null && content.isArray) content.toList() else emptyList()
        }

        private fun block(
            node: JsonNode,
            path: String,
            listDepth: Int,
        ): NoteBlock? {
            nodes++
            return when (val type = node["type"]?.asString()) {
                "paragraph" -> {
                    checks.keys(node, path, setOf("type", "content"))
                    NoteParagraph(inlines(node["content"], "$path.content"))
                }

                "bulletList", "orderedList" -> {
                    checks.keys(node, path, setOf("type", "content", "attrs"))
                    if (listDepth >= MAX_LIST_DEPTH) problem(path, "TOO_DEEP")
                    val start = if (type == "orderedList") checks.start(node["attrs"], "$path.attrs") else checks.attrsNone(node, path)
                    NoteList(type == "orderedList", start, items(node["content"], "$path.content", listDepth + 1))
                }

                else -> {
                    problem(path, "UNKNOWN_NODE")
                    null
                }
            }
        }

        private fun items(
            content: JsonNode?,
            path: String,
            listDepth: Int,
        ): List<List<NoteBlock>> {
            if (content == null || !content.isArray || content.isEmpty) return problem(path, "EMPTY_LIST").let { emptyList() }
            return content.mapIndexed { i, item ->
                nodes++
                if (item["type"]?.asString() != "listItem") problem("$path[$i]", "UNKNOWN_NODE")
                checks.keys(item, "$path[$i]", setOf("type", "content"))
                blocks(item["content"], "$path[$i].content", listDepth)
            }
        }

        private fun inlines(
            content: JsonNode?,
            path: String,
        ): List<NoteInline> = list(content, path).mapIndexedNotNull { i, node -> inline(node, "$path[$i]") }

        private fun inline(
            node: JsonNode,
            path: String,
        ): NoteInline? {
            nodes++
            return when (node["type"]?.asString()) {
                "text" -> text(node, path)
                "hardBreak" -> NoteBreak.also { checks.keys(node, path, setOf("type")) }
                "mention" -> checks.reference(node, path)?.let { (id, label) -> NoteMention(id, label) }
                "memoRef" -> checks.reference(node, path)?.let { (id, label) -> NoteMemoRef(id, label) }
                else -> null.also { problem(path, "UNKNOWN_NODE") }
            }
        }

        private fun text(
            node: JsonNode,
            path: String,
        ): NoteText? {
            checks.keys(node, path, setOf("type", "text", "marks"))
            val text = node["text"]?.takeIf { it.isString }?.asString()
            if (text.isNullOrEmpty()) return null.also { problem("$path.text", "EMPTY_TEXT") }
            var bold = false
            var italic = false
            var link: String? = null
            node["marks"]?.let { marks ->
                if (!marks.isArray) return@let problem("$path.marks", "NOT_A_LIST")
                marks.forEachIndexed { i, mark ->
                    val at = "$path.marks[$i]"
                    when (mark["type"]?.asString()) {
                        "bold" -> bold = true.also { checks.keys(mark, at, setOf("type")) }
                        "italic" -> italic = true.also { checks.keys(mark, at, setOf("type")) }
                        "link" -> link = checks.href(mark, at)
                        else -> problem(at, "UNKNOWN_MARK")
                    }
                }
            }
            return NoteText(text, bold, italic, link)
        }

        fun limits(doc: NoteDocument): List<NoteProblem> {
            val inlines = doc.inlines()
            return listOfNotNull(
                NoteProblem("", "TOO_MANY_NODES").takeIf { nodes > MAX_NODES },
                NoteProblem("", "TOO_LONG").takeIf { doc.text().length > MAX_TEXT },
                NoteProblem("", "EMPTY").takeIf { doc.text().isBlank() },
                NoteProblem("", "TOO_MANY_MENTIONS").takeIf { inlines.count { it is NoteMention } > MAX_EACH },
                NoteProblem("", "TOO_MANY_LINKS").takeIf { inlines.count { it is NoteText && it.link != null } > MAX_EACH },
                NoteProblem("", "TOO_MANY_MEMO_REFERENCES").takeIf { inlines.count { it is NoteMemoRef } > MAX_EACH },
            )
        }

        private fun problem(
            path: String,
            code: String,
        ) {
            if (problems.size < MAX_PROBLEMS) problems += NoteProblem(path, code)
        }
    }

    /** Checks of keys and attributes, reporting through [problem]. */
    private class Checks(
        private val problem: (String, String) -> Unit,
    ) {
        fun keys(
            node: JsonNode?,
            path: String,
            allowed: Set<String>,
        ) {
            if (node == null) return
            if (!node.isObject) return problem(path.ifEmpty { "$" }, "NOT_AN_OBJECT")
            node.propertyNames().forEach { if (it !in allowed) problem(if (path.isEmpty()) it else "$path.$it", "UNKNOWN_KEY") }
        }

        fun href(
            mark: JsonNode,
            path: String,
        ): String? {
            keys(mark, path, setOf("type", "attrs"))
            val attrs = mark["attrs"]
            keys(attrs, "$path.attrs", setOf("href"))
            val href =
                attrs
                    ?.get("href")
                    ?.takeIf { it.isString }
                    ?.asString()
                    ?.trim()
            val ok =
                href != null &&
                    href.length <= MAX_URL &&
                    runCatching {
                        URI(
                            href,
                        )
                    }.getOrNull()?.let { it.scheme.equals("https", ignoreCase = true) && !it.host.isNullOrEmpty() } ==
                    true
            if (!ok) problem("$path.attrs.href", "LINK_NOT_HTTPS")
            return href.takeIf { ok }
        }

        fun reference(
            node: JsonNode,
            path: String,
        ): Pair<UUID, String>? {
            keys(node, path, setOf("type", "attrs"))
            val attrs = node["attrs"]
            keys(attrs, "$path.attrs", setOf("id", "label"))
            val id =
                attrs
                    ?.get("id")
                    ?.takeIf { it.isString }
                    ?.asString()
                    ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            val label =
                attrs
                    ?.get("label")
                    ?.takeIf { it.isString }
                    ?.asString()
                    ?.trim()
            if (id == null) problem("$path.attrs.id", "INVALID_ID")
            if (label.isNullOrEmpty() || label.length > MAX_LABEL) problem("$path.attrs.label", "INVALID_LABEL")
            return if (id != null && !label.isNullOrEmpty() && label.length <= MAX_LABEL) id to label else null
        }

        fun start(
            attrs: JsonNode?,
            path: String,
        ): Int? {
            keys(attrs, path, setOf("start"))
            val start = attrs?.get("start")
            val valid = start != null && start.isInt && start.asInt() in 1..MAX_START
            if (start != null && !valid) problem("$path.start", "INVALID_START")
            return start?.takeIf { valid }?.asInt()?.takeIf { it != 1 }
        }

        fun attrsNone(
            node: JsonNode,
            path: String,
        ): Int? {
            if (node.has("attrs")) problem("$path.attrs", "UNKNOWN_KEY")
            return null
        }

        private companion object {
            const val MAX_START = 10_000
        }
    }
}
