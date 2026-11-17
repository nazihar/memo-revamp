package com.memo.search.internal

import com.memo.audit.AuditLog
import com.memo.common.context.Actor
import com.memo.common.context.ActorContext
import com.memo.org.OrgDirectory
import org.springframework.stereotype.Service
import java.time.ZoneId
import java.util.UUID

/**
 * Search for the signed-in reader (ADR-0054, design E05 §3, flow H): the candidates from `search_find` under the reader's
 * scope, ordered and paged by keyset, counted ("2,000+" above the cap) and faceted on the same permitted set, with
 * snippets for the page only. Every search is audited with its terms. Inside a tenant transaction.
 */
@Service
class SearchService(
    private val finder: SearchFinder,
    private val scopes: ReaderScopes,
    private val results: SearchResults,
    private val org: OrgDirectory,
    private val audit: AuditLog,
) {
    fun search(
        query: SearchQuery,
        cursor: String?,
        limit: Int,
    ): SearchPageView {
        SearchQueries.check(query)
        val scope = scopes.of(reader())
        val found = finder.find(criteria(query, org.zone()), scope, CAP + 1)
        val candidates = found.take(CAP).sortedWith(SearchOrder.of(query.sort))
        val rest = SearchCursor.after(cursor, query.sort, candidates)
        val page = rest.take(limit.coerceIn(1, MAX_PAGE))
        val view =
            SearchPageView(
                results = results.views(page, query.q, scope),
                count = candidates.size,
                capped = found.size > CAP,
                facets = results.facets(candidates),
                cursor = page.lastOrNull()?.takeIf { rest.size > page.size }?.let { SearchCursor.encode(query.sort, it) },
            )
        record("search", query, view.count, view.capped, page.map { it.memoId })
        return view
    }

    /**
     * Memos offered while typing (flow H1): the last word as a prefix (3+ characters) with the words before it, over
     * [SUGGEST_CAP] candidates; at most [SUGGESTIONS]. Nothing for input that is not searchable yet.
     */
    fun suggest(q: String): List<SuggestionView> {
        SearchQueries.check(SearchQuery(q = q))
        val terms = SearchQueries.terms(q)
        val typing = !q.last().isWhitespace() && terms.last().length >= PREFIX_MIN
        val criteria =
            SearchCriteria(
                text = (if (typing) terms.dropLast(1) else terms).joinToString(" "),
                prefix = terms.last().takeIf { typing },
            )
        val found = finder.find(criteria, scopes.of(reader()), SUGGEST_CAP).take(SUGGESTIONS)
        record("suggest", SearchQuery(q = q), found.size, capped = false, found.map { it.memoId })
        return results.suggestions(found)
    }

    private fun record(
        mode: String,
        query: SearchQuery,
        count: Int,
        capped: Boolean,
        page: List<UUID>,
    ) {
        audit.record(
            "search.performed",
            OBJECT_TYPE,
            reader().toString(),
            after =
                mapOf(
                    "mode" to mode,
                    "q" to query.q.trim(),
                    "filters" to filters(query),
                    "sort" to query.sort.name,
                    "count" to count,
                    "capped" to capped,
                    "memos" to page.map(UUID::toString),
                ),
        )
    }

    private fun filters(query: SearchQuery): Map<String, Any?> =
        mapOf(
            "type" to query.types.map(UUID::toString),
            "status" to query.statuses,
            "unit" to query.units.map(UUID::toString),
            "from" to query.from?.toString(),
            "to" to query.to?.toString(),
            "amountMin" to query.amountMin?.toPlainString(),
            "amountMax" to query.amountMax?.toPlainString(),
            "in" to query.sources.map { it.name },
        ).filterValues { it != null && it != emptyList<Any>() }

    private fun reader(): UUID = checkNotNull((ActorContext.current().actor as? Actor.User)?.userId) { "search needs a signed-in user" }

    companion object {
        /** Candidates per search (ADR-0054): "2,000+" above it. */
        const val CAP = 2000
        const val SUGGEST_CAP = 200
        const val SUGGESTIONS = 8
        const val MAX_PAGE = 50
        const val PREFIX_MIN = 3
        const val OBJECT_TYPE = "search"

        /** The finder's criteria for [query]: dates are whole days in the tenant's time zone, `to` included. */
        fun criteria(
            query: SearchQuery,
            zone: ZoneId,
        ): SearchCriteria =
            SearchCriteria(
                text = query.q.trim(),
                statuses = query.statuses.toSet().ifEmpty { null },
                types = query.types.toSet().ifEmpty { null },
                units = query.units.toSet().ifEmpty { null },
                from = query.from?.atStartOfDay(zone)?.toInstant(),
                to =
                    query.to
                        ?.plusDays(1)
                        ?.atStartOfDay(zone)
                        ?.toInstant(),
                amountMin = query.amountMin,
                amountMax = query.amountMax,
                inDetails = SearchSource.METADATA in query.sources,
                inNotes = SearchSource.NOTES in query.sources,
                inDocuments = SearchSource.DOCUMENTS in query.sources,
            )
    }
}
