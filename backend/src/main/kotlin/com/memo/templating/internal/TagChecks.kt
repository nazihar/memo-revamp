package com.memo.templating.internal

import com.memo.templating.DocxTemplates
import com.memo.templating.TableRow
import com.memo.templating.TagKind
import com.memo.templating.TagVocabulary
import com.memo.templating.TemplateProblem
import com.memo.templating.TemplateProblemCode
import com.memo.templating.TemplateScan
import com.memo.templating.TemplateTag
import org.springframework.stereotype.Component
import java.nio.channels.SeekableByteChannel

@Component
internal class DocxTemplatesImpl : DocxTemplates {
    override fun scan(open: () -> SeekableByteChannel): TemplateScan = DocxScanner.scan(open)

    override fun check(
        scan: TemplateScan,
        vocabulary: TagVocabulary,
    ): List<TemplateProblem> = TagChecks(vocabulary).check(scan)
}

/**
 * Checks tags against a [TagVocabulary] (ADR-0048): every value tag known, blocks balanced within one part and one
 * table row, loops in a table with their `[fields]` in the next row and nothing else there. Unknown names get the
 * closest known name (or the tag an alias stands for) as a suggestion.
 *
 * Repeated blocks (ADR-0066): between a block's `{{?name}}` and `{{/name}}` the block's vocabulary applies; each end must
 * be alone in a paragraph of the body, the block used once and never inside another ([TemplateProblemCode.BLOCK_PLACEMENT]),
 * which is what the filler needs to repeat it and wrap each element in its content control.
 */
internal class TagChecks(
    private val vocabulary: TagVocabulary,
) {
    private data class Open(
        val tag: TemplateTag,
    )

    fun check(scan: TemplateScan): List<TemplateProblem> {
        val found = mutableListOf<TemplateProblem>()
        val scopes = scopes(scan.tags, found)
        val loopRows = loopRows(scan.tags, scopes)
        val open = ArrayDeque<Open>()
        scan.tags.forEachIndexed { i, tag ->
            val scope = scopes[i]
            when (tag.kind) {
                TagKind.VALUE -> found += value(tag, scope, loopRows, scan.tags)
                TagKind.SECTION_START -> found += sectionStart(tag, scope, open)
                TagKind.SECTION_END -> found += sectionEnd(tag, open)
                TagKind.ROW_FIELD -> Unit
            }
        }
        open.forEach { found += problem(TemplateProblemCode.UNBALANCED_SECTION, it.tag) }
        return (scan.problems + found).sortedWith(
            compareBy({ partOrder(it.location.part) }, { it.location.part }, { it.location.paragraph }),
        )
    }

    /**
     * The vocabulary each tag is checked against: the document's, or inside a block its own (with the document's values),
     * one level deep. A block's ends themselves belong to the document. Placement problems of blocks go to [found].
     */
    private fun scopes(
        tags: List<TemplateTag>,
        found: MutableList<TemplateProblem>,
    ): List<TagVocabulary> {
        val used = mutableSetOf<String>()
        var inside: String? = null
        return tags.map { tag ->
            val block = vocabulary.blocks[tag.name]
            when {
                block != null && tag.kind == TagKind.SECTION_START -> {
                    val misplaced = !tag.standalone || inside != null || tag.name in used
                    if (misplaced) found += problem(TemplateProblemCode.BLOCK_PLACEMENT, tag)
                    if (inside == null) {
                        inside = tag.name
                        used += tag.name
                    }
                    vocabulary
                }

                block != null && tag.kind == TagKind.SECTION_END && tag.name == inside -> {
                    if (!tag.standalone) found += problem(TemplateProblemCode.BLOCK_PLACEMENT, tag)
                    inside = null
                    vocabulary
                }

                else -> {
                    inside?.let { name -> vocabulary.within(checkNotNull(vocabulary.blocks[name])) } ?: vocabulary
                }
            }
        }
    }

    /** The template row below each loop tag that sits in a table. */
    private fun loopRows(
        tags: List<TemplateTag>,
        scopes: List<TagVocabulary>,
    ): Map<TableRow, String> =
        tags
            .filterIndexed { i, tag -> tag.kind == TagKind.VALUE && tag.name in scopes[i].loops }
            .mapNotNull { tag -> tag.row?.let { row -> row.copy(row = row.row + 1) to tag.name } }
            .toMap()

    private fun value(
        tag: TemplateTag,
        scope: TagVocabulary,
        loopRows: Map<TableRow, String>,
        all: List<TemplateTag>,
    ): List<TemplateProblem> {
        val loop = scope.loops[tag.name]
        return when {
            tag.row != null && tag.row in loopRows -> {
                listOf(problem(TemplateProblemCode.TAG_IN_LOOP_ROW, tag))
            }

            loop != null -> {
                loopProblems(tag, loop, all)
            }

            tag.name !in scope.values -> {
                val suggestion = closest(tag.name, scope.values + scope.loops.keys, scope.aliases)
                listOf(problem(TemplateProblemCode.UNKNOWN_TAG, tag, suggestion?.let { "{{$it}}" }))
            }

            else -> {
                emptyList()
            }
        }
    }

    private fun loopProblems(
        tag: TemplateTag,
        fields: Set<String>,
        all: List<TemplateTag>,
    ): List<TemplateProblem> {
        val below = tag.row?.let { it.copy(row = it.row + 1) }
        val rowFields = all.filter { it.kind == TagKind.ROW_FIELD && below != null && it.row == below }
        if (rowFields.isEmpty()) return listOf(problem(TemplateProblemCode.LOOP_WITHOUT_ROW, tag))
        return rowFields
            .filter { it.name !in fields }
            .map { problem(TemplateProblemCode.UNKNOWN_ROW_FIELD, it, closest(it.name, fields)?.let { name -> "[$name]" }) }
    }

    private fun sectionStart(
        tag: TemplateTag,
        scope: TagVocabulary,
        open: ArrayDeque<Open>,
    ): List<TemplateProblem> {
        open.addLast(Open(tag))
        val block = scope === vocabulary && tag.name in vocabulary.blocks
        return if (tag.name in scope.sections || block) {
            emptyList()
        } else {
            val known = scope.sections + if (scope === vocabulary) vocabulary.blocks.keys else emptySet()
            val aliases = scope.aliases.filterValues { it in known }
            listOf(problem(TemplateProblemCode.UNKNOWN_TAG, tag, closest(tag.name, known, aliases)?.let { "{{?$it}}" }))
        }
    }

    private fun sectionEnd(
        tag: TemplateTag,
        open: ArrayDeque<Open>,
    ): List<TemplateProblem> {
        val top = open.lastOrNull()?.tag
        val matches = top != null && top.name == tag.name && top.location.part == tag.location.part && top.row == tag.row
        if (!matches) return listOf(problem(TemplateProblemCode.UNBALANCED_SECTION, tag))
        open.removeLast()
        return emptyList()
    }

    private fun problem(
        code: TemplateProblemCode,
        tag: TemplateTag,
        suggestion: String? = null,
    ) = TemplateProblem(code, tag.location, tag.excerpt, tag.display(), suggestion)

    private fun TemplateTag.display(): String =
        when (kind) {
            TagKind.VALUE -> "{{$name}}"
            TagKind.SECTION_START -> "{{?$name}}"
            TagKind.SECTION_END -> "{{/$name}}"
            TagKind.ROW_FIELD -> "[$name]"
        }

    /** The closest of [known], or the tag of the closest of [aliases] whose tag is known. */
    private fun closest(
        name: String,
        known: Set<String>,
        aliases: Map<String, String> = emptyMap(),
    ): String? {
        val usable = aliases.filterValues { it in known }
        return Suggestions.closest(name, known + usable.keys)?.let { usable[it] ?: it }
    }

    /** Body first, then headers, footers and notes, as an admin reads them. */
    private fun partOrder(part: String): Int = PART_ORDER.indexOfFirst { part.startsWith(it) }.let { if (it < 0) PART_ORDER.size else it }

    private companion object {
        val PART_ORDER = listOf("Body", "Header", "Footer")
    }
}

/** A block's vocabulary as its tags see it: its own values and the document's, its own sections and loops. */
private fun TagVocabulary.within(block: TagVocabulary): TagVocabulary =
    TagVocabulary(
        values = block.values + values,
        sections = block.sections,
        loops = block.loops,
        aliases = aliases + block.aliases,
    )

/** The closest name by edit distance, when it is close enough to be what was meant. */
internal object Suggestions {
    fun closest(
        name: String,
        known: Collection<String>,
    ): String? {
        val lower = name.lowercase()
        return known
            .map { it to distance(lower, it.lowercase()) }
            .filter { (candidate, d) -> d <= maxOf(MIN_EDITS, candidate.length / CHARS_PER_EDIT) }
            .minWithOrNull(compareBy({ it.second }, { it.first }))
            ?.first
    }

    /** Typos forgiven: at least this many edits, or one per this many characters of the known name. */
    private const val MIN_EDITS = 2
    private const val CHARS_PER_EDIT = 4

    /** Levenshtein distance, two rows. */
    fun distance(
        a: String,
        b: String,
    ): Int {
        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)
        for (i in 1..a.length) {
            current[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + cost)
            }
            val swap = previous
            previous = current
            current = swap
        }
        return previous[b.length]
    }
}
