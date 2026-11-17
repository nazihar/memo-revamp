package com.memo.search.internal

import com.memo.common.tenant.TenantDsl
import com.memo.jooq.tables.references.SEARCH_FIND
import org.springframework.stereotype.Component
import java.math.BigDecimal
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
)

/** Runs `search_find` (ADR-0054) for a reader: at most [cap] candidates, best first. */
@Component
class SearchFinder(
    private val tenantDsl: TenantDsl,
    private val clock: Clock,
) {
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
                OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC),
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
            )
        return tenantDsl.current().selectFrom(call).fetch { r ->
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
            )
        }
    }

    companion object {
        /** Candidates per search (ADR-0054): "2,000+" above it. */
        const val DEFAULT_CAP = 2000
    }
}
