package com.memo.search.internal

import com.memo.common.web.FieldProblem
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/** Where a search looks (flow H2 "Search in"). */
enum class SearchSource { METADATA, NOTES, DOCUMENTS }

/** How results are ordered: by relevance, or by the memo's last update, newest first. */
enum class SearchSort { RELEVANCE, NEWEST }

/** A search as the API takes it and a saved search keeps it (design E05 §3). Checked by [SearchQueries.check]. */
data class SearchQuery(
    val q: String = "",
    val types: List<UUID> = emptyList(),
    val statuses: List<String> = emptyList(),
    val units: List<UUID> = emptyList(),
    val from: LocalDate? = null,
    val to: LocalDate? = null,
    val amountMin: BigDecimal? = null,
    val amountMax: BigDecimal? = null,
    val sources: List<SearchSource> = SearchSource.entries,
    val sort: SearchSort = SearchSort.RELEVANCE,
)

/** A search or saved search the rules refuse (422 `validation`). */
class SearchRejectedException(
    val problems: List<FieldProblem>,
) : RuntimeException("search rejected: ${problems.joinToString { "${it.field}:${it.code}" }}")

/** The input caps of a search (design E05 §3, threat model): words, filters and their values. */
object SearchQueries {
    const val MAX_CHARS = 200
    const val MAX_TERMS = 12
    const val MAX_VALUES = 20

    /** The memo statuses a search may filter on. */
    val STATUSES =
        setOf("DRAFT", "IN_REVIEW", "RETURNED", "APPROVED", "REJECTED", "WITHDRAWN", "DISCARDED", "CANCELLED", "CLOSED")

    private val whitespace = Regex("\\s+")

    fun check(query: SearchQuery) {
        val problems = problems(query)
        if (problems.isNotEmpty()) throw SearchRejectedException(problems)
    }

    fun problems(query: SearchQuery): List<FieldProblem> = words(query.q) + filters(query)

    /** The words of [q], trimmed. */
    fun terms(q: String): List<String> = q.trim().split(whitespace).filter { it.isNotEmpty() }

    private fun words(q: String): List<FieldProblem> {
        val text = q.trim()
        return listOfNotNull(
            when {
                text.isEmpty() -> FieldProblem("q", "REQUIRED", "Enter words to search for.")
                text.length > MAX_CHARS -> FieldProblem("q", "TOO_LONG", "At most $MAX_CHARS characters.")
                terms(text).size > MAX_TERMS -> FieldProblem("q", "TOO_MANY_TERMS", "At most $MAX_TERMS words.")
                else -> null
            },
        )
    }

    private fun filters(query: SearchQuery): List<FieldProblem> {
        val from = query.from
        val to = query.to
        val min = query.amountMin
        val max = query.amountMax
        return listOfNotNull(
            FieldProblem("status", "UNKNOWN", "Not a memo status.").takeIf { query.statuses.any { it !in STATUSES } },
            FieldProblem("filters", "TOO_MANY", "At most $MAX_VALUES values per filter.")
                .takeIf { maxOf(query.types.size, query.statuses.size, query.units.size) > MAX_VALUES },
            FieldProblem("to", "BEFORE_FROM", "The end is before the start.").takeIf { from != null && to != null && to < from },
            FieldProblem("amountMax", "BELOW_MIN", "The maximum is below the minimum.").takeIf { min != null && max != null && max < min },
            FieldProblem("in", "REQUIRED", "Choose where to search.").takeIf { query.sources.isEmpty() },
        )
    }
}
