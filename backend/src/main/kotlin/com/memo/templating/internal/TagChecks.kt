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
 * closest known name as a suggestion.
 */
internal class TagChecks(
    private val vocabulary: TagVocabulary,
) {
    private data class Open(
        val tag: TemplateTag,
    )

    fun check(scan: TemplateScan): List<TemplateProblem> {
        val found = mutableListOf<TemplateProblem>()
        val loopRows = loopRows(scan.tags)
        val open = ArrayDeque<Open>()
        scan.tags.forEach { tag ->
            when (tag.kind) {
                TagKind.VALUE -> found += value(tag, loopRows, scan.tags)
                TagKind.SECTION_START -> found += sectionStart(tag, open)
                TagKind.SECTION_END -> found += sectionEnd(tag, open)
                TagKind.ROW_FIELD -> Unit
            }
        }
        open.forEach { found += problem(TemplateProblemCode.UNBALANCED_SECTION, it.tag) }
        return (scan.problems + found).sortedWith(
            compareBy({ partOrder(it.location.part) }, { it.location.part }, { it.location.paragraph }),
        )
    }

    /** The template row below each loop tag that sits in a table. */
    private fun loopRows(tags: List<TemplateTag>): Map<TableRow, String> =
        tags
            .filter { it.kind == TagKind.VALUE && it.name in vocabulary.loops }
            .mapNotNull { tag -> tag.row?.let { row -> row.copy(row = row.row + 1) to tag.name } }
            .toMap()

    private fun value(
        tag: TemplateTag,
        loopRows: Map<TableRow, String>,
        all: List<TemplateTag>,
    ): List<TemplateProblem> {
        val loop = vocabulary.loops[tag.name]
        return when {
            tag.row != null && tag.row in loopRows -> {
                listOf(problem(TemplateProblemCode.TAG_IN_LOOP_ROW, tag))
            }

            loop != null -> {
                loopProblems(tag, loop, all)
            }

            tag.name !in vocabulary.values -> {
                listOf(
                    problem(
                        TemplateProblemCode.UNKNOWN_TAG,
                        tag,
                        closest(tag.name, vocabulary.values + vocabulary.loops.keys)?.let { "{{$it}}" },
                    ),
                )
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
        open: ArrayDeque<Open>,
    ): List<TemplateProblem> {
        open.addLast(Open(tag))
        return if (tag.name in vocabulary.sections) {
            emptyList()
        } else {
            listOf(problem(TemplateProblemCode.UNKNOWN_TAG, tag, closest(tag.name, vocabulary.sections)?.let { "{{?$it}}" }))
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

    private fun closest(
        name: String,
        known: Set<String>,
    ): String? = Suggestions.closest(name, known)

    /** Body first, then headers, footers and notes, as an admin reads them. */
    private fun partOrder(part: String): Int = PART_ORDER.indexOfFirst { part.startsWith(it) }.let { if (it < 0) PART_ORDER.size else it }

    private companion object {
        val PART_ORDER = listOf("Body", "Header", "Footer")
    }
}

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
