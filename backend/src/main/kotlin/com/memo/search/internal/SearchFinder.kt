package com.memo.search.internal

import com.memo.common.tenant.TenantDsl
import com.memo.jooq.tables.references.SEARCH_FIND
import com.memo.jooq.tables.references.SEARCH_SNIPPETS
import com.memo.memo.MemoOrigin
import org.springframework.dao.DataAccessException
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.sql.SQLException
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/** What to search for (design E05 §3): the words, the last word as a prefix while typing, and the filters. */
data class SearchCriteria(
    val text: String = "",
    /** The last word, matched as a prefix (3+ letters or digits), for search as you type. */
    val prefix: String? = null,
    val statuses: Set<String>? = null,
    val types: Set<UUID>? = null,
    val units: Set<UUID>? = null,
    val from: Instant? = null,
    val to: Instant? = null,
    val amountMin: BigDecimal? = null,
    val amountMax: BigDecimal? = null,
    val inDetails: Boolean = true,
    val inNotes: Boolean = true,
    val inDocuments: Boolean = true,
    /** Memos raised here, imported ones (design E06 §11), or both (null). */
    val origins: Set<MemoOrigin>? = null,
)

/** A memo that matched, with where it matched; the caller pages, counts and facets these. */
data class SearchCandidate(
    val memoId: UUID,
    val score: Float,
    val inDetails: Boolean,
    val inNotes: Boolean,
    val inDocuments: Boolean,
    val byNumber: Boolean,
    val status: String,
    val typeId: UUID,
    val originUnitId: UUID?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val origin: MemoOrigin = MemoOrigin.NATIVE,
) {
    companion object {
        /** A position in an order (a cursor), not a result. */
        fun probe(
            memoId: UUID,
            score: Float = 0f,
            updatedAt: Instant = Instant.EPOCH,
        ) = SearchCandidate(memoId, score, false, false, false, false, "", memoId, null, Instant.EPOCH, updatedAt)
    }
}

/** A page result's snippet: from a document version, a note or the subject; matches between U+E000 and U+E001. */
data class SearchSnippet(
    val source: String,
    val sourceId: UUID?,
    val text: String,
) {
    companion object {
        const val DOCUMENT = "document"
        const val NOTE = "note"
    }
}

/** The search took longer than [SearchFinder.TIMEOUT_SECONDS] (503 `SEARCH_TIMEOUT`). */
class SearchTimeoutException : RuntimeException("search timed out")

/** Runs `search_find` and `search_snippets` (ADR-0054) for a reader, each within [TIMEOUT_SECONDS]. */
@Component
class SearchFinder(
    private val tenantDsl: TenantDsl,
    private val clock: Clock,
) {
    /** At most [cap] candidates, best first. */
    fun find(
        criteria: SearchCriteria,
        scope: ReaderScope,
        cap: Int = DEFAULT_CAP,
    ): List<SearchCandidate> {
        val keys = SearchReferences.keys(criteria.text)
        val call =
            SEARCH_FIND.call(
                tenantDsl.tenant().value,
                scope.reader,
                scope.clearanceRank,
                scope.units.toTypedArray<UUID?>(),
                scope.allUnits,
                now(),
                criteria.text,
                criteria.prefix,
                keys?.first,
                keys?.second,
                criteria.statuses?.toTypedArray<String?>(),
                criteria.types?.toTypedArray<UUID?>(),
                criteria.units?.toTypedArray<UUID?>(),
                criteria.from?.atOffset(ZoneOffset.UTC),
                criteria.to?.atOffset(ZoneOffset.UTC),
                criteria.amountMin,
                criteria.amountMax,
                criteria.inDetails,
                criteria.inNotes,
                criteria.inDocuments,
                cap,
                criteria.origins?.map { it.name }?.toTypedArray<String?>(),
            )
        return timed {
            tenantDsl.current().selectFrom(call).queryTimeout(TIMEOUT_SECONDS).fetch { r ->
                SearchCandidate(
                    memoId = requireNotNull(r.memoId),
                    score = r.score ?: 0f,
                    inDetails = r.inMeta == true,
                    inNotes = r.inNotes == true,
                    inDocuments = r.inDocs == true,
                    byNumber = r.inRef == true,
                    status = requireNotNull(r.status),
                    typeId = requireNotNull(r.typeId),
                    originUnitId = r.originUnitId,
                    createdAt = requireNotNull(r.createdAt).toInstant(),
                    updatedAt = requireNotNull(r.updatedAt).toInstant(),
                    origin = MemoOrigin.valueOf(requireNotNull(r.origin)),
                )
            }
        }
    }

    /** The snippets of [memoIds] (one page) for [q], for memos the reader may read; by memo. */
    fun snippets(
        memoIds: List<UUID>,
        q: String,
        scope: ReaderScope,
    ): Map<UUID, SearchSnippet> {
        val call =
            SEARCH_SNIPPETS.call(
                tenantDsl.tenant().value,
                scope.reader,
                scope.clearanceRank,
                scope.units.toTypedArray<UUID?>(),
                scope.allUnits,
                now(),
                memoIds.toTypedArray<UUID?>(),
                q,
                null,
            )
        return timed {
            tenantDsl
                .current()
                .selectFrom(call)
                .queryTimeout(TIMEOUT_SECONDS)
                .fetch { r -> requireNotNull(r.memoId) to SearchSnippet(r.source.orEmpty(), r.sourceId, r.snippet.orEmpty()) }
                .toMap()
        }
    }

    private fun now(): OffsetDateTime = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC)

    private fun <T> timed(query: () -> T): T =
        try {
            query()
        } catch (e: DataAccessException) {
            if (cancelled(e)) throw SearchTimeoutException() else throw e
        }

    /** PostgreSQL's "canceling statement due to statement timeout" (or user request, as the driver's timeout does). */
    private fun cancelled(e: Throwable): Boolean =
        generateSequence(e) { it.cause }.any { (it as? SQLException)?.sqlState == QUERY_CANCELED }

    companion object {
        /** Candidates per search (ADR-0054): "2,000+" above it. */
        const val DEFAULT_CAP = 2000
        const val TIMEOUT_SECONDS = 2
        private const val QUERY_CANCELED = "57014"
    }
}
