package com.memo.search.internal

import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant
import java.util.Base64
import java.util.UUID

/** A piece of a snippet: text, and whether it matched (shown as `<mark>`). Text only: clients never render it as HTML. */
data class SnippetPart(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val text: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val match: Boolean,
)

/** Where a result matched (flow H2 "Found in"). */
enum class FoundInKind { DETAILS, NUMBER, NOTE, DOCUMENT, ATTACHMENT }

/** Where a result matched: the note's author or the attachment's name when there is one. */
data class FoundInView(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val kind: FoundInKind,
    val name: String? = null,
)

data class SearchResultView(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val memoId: UUID,
    /** The memo's number; null while it has none. */
    val number: String?,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val subject: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val typeId: UUID,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val typeName: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val status: String,
    val unitId: UUID?,
    val unitName: String?,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val updatedAt: Instant,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val snippet: List<SnippetPart>,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val foundIn: FoundInView,
)

/** One value of a facet with the number of matching memos the reader may read. */
data class FacetView(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val value: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val label: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val count: Int,
)

data class SearchFacetsView(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val status: List<FacetView>,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val type: List<FacetView>,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val unit: List<FacetView>,
)

/** A page of results. [count] is exact up to the cap; [capped] says there were more ("2,000+"). */
data class SearchPageView(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val results: List<SearchResultView>,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val count: Int,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val capped: Boolean,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val facets: SearchFacetsView,
    /** The next page's cursor; null on the last page. */
    val cursor: String?,
)

/** A memo offered while typing in the search box. */
data class SuggestionView(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val memoId: UUID,
    val number: String?,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val subject: String,
)

/**
 * The position after a result in the order of a search: (score, memo) for relevance, (updated, memo) for newest. Opaque
 * Base64url tokens; a malformed one is a 400. Candidates are recomputed each time, so paging follows the keyset.
 */
object SearchCursor {
    fun encode(
        sort: SearchSort,
        last: SearchCandidate,
    ): String {
        val key = if (sort == SearchSort.RELEVANCE) last.score.toRawBits().toString() else last.updatedAt.toString()
        return Base64.getUrlEncoder().withoutPadding().encodeToString("${sort.name}|$key|${last.memoId}".toByteArray())
    }

    /** The candidates after [cursor] in [ordered] (sorted by [sort]); all of them when there is no cursor. */
    fun after(
        cursor: String?,
        sort: SearchSort,
        ordered: List<SearchCandidate>,
    ): List<SearchCandidate> {
        if (cursor == null) return ordered
        val parts = runCatching { String(Base64.getUrlDecoder().decode(cursor)).split('|') }.getOrNull()
        require(parts != null && parts.size == PARTS && parts[0] == sort.name) { "invalid cursor" }
        val id = runCatching { UUID.fromString(parts[2]) }.getOrElse { throw IllegalArgumentException("invalid cursor") }
        val position: Comparator<SearchCandidate> = SearchOrder.of(sort)
        val probe =
            runCatching {
                if (sort == SearchSort.RELEVANCE) {
                    SearchCandidate.probe(id, score = Float.fromBits(parts[1].toInt()))
                } else {
                    SearchCandidate.probe(id, updatedAt = Instant.parse(parts[1]))
                }
            }.getOrElse { throw IllegalArgumentException("invalid cursor") }
        return ordered.filter { position.compare(it, probe) > 0 }
    }

    private const val PARTS = 3
}

/** The orders of [SearchSort]: relevance (score, best first) or newest (last update), then memo id. */
object SearchOrder {
    fun of(sort: SearchSort): Comparator<SearchCandidate> =
        when (sort) {
            SearchSort.RELEVANCE -> compareByDescending<SearchCandidate> { it.score }.thenBy { it.memoId }
            SearchSort.NEWEST -> compareByDescending<SearchCandidate> { it.updatedAt }.thenBy { it.memoId }
        }
}

/** Snippets mark matches with U+E000 and U+E001 (search_snippets); here they become parts. */
object SnippetParts {
    private const val START = ''
    private const val STOP = ''

    fun of(snippet: String): List<SnippetPart> {
        val parts = mutableListOf<SnippetPart>()
        var match = false
        val text = StringBuilder()

        fun flush() {
            if (text.isNotEmpty()) parts += SnippetPart(text.toString(), match)
            text.clear()
        }
        for (c in snippet) {
            when (c) {
                START -> flush().also { match = true }
                STOP -> flush().also { match = false }
                else -> text.append(c)
            }
        }
        flush()
        return parts
    }
}
