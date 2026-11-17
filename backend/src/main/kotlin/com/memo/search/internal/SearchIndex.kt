package com.memo.search.internal

import com.memo.common.tenant.TenantDsl
import com.memo.jooq.routines.references.searchPutMemo
import com.memo.memo.MemoSearchFacts
import com.memo.memo.MemoSearchSource
import org.springframework.stereotype.Component
import java.time.ZoneOffset
import java.util.UUID

/**
 * Writes the search index (ADR-0054) through the search functions, the only way the application role reaches the search
 * tables. Callers run inside the tenant transaction of the change, so the index moves with its source.
 */
@Component
class SearchIndex(
    private val source: MemoSearchSource,
    private val tenantDsl: TenantDsl,
) {
    /** Indexes [memoId]'s details as they are now; false when the memo does not exist. */
    fun putMemo(memoId: UUID): Boolean = source.facts(memoId)?.also(::put) != null

    private fun put(facts: MemoSearchFacts) {
        val dsl = tenantDsl.current()
        searchPutMemo(
            dsl.configuration(),
            tenantDsl.tenant().value,
            facts.memoId,
            facts.classificationRank,
            facts.originUnitId,
            facts.status,
            facts.typeId,
            facts.initiatorId,
            facts.amount,
            facts.createdAt.atOffset(ZoneOffset.UTC),
            facts.updatedAt.atOffset(ZoneOffset.UTC),
            facts.reference?.let(SearchReferences::norm),
            facts.reference?.let(SearchReferences::tail),
            facts.subject,
            facts.fieldsText,
        )
    }
}
