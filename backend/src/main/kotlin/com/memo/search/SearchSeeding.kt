package com.memo.search

import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantTransactions
import com.memo.search.internal.SavedSearches
import com.memo.search.internal.SearchQuery
import org.springframework.stereotype.Service

/**
 * Saved searches for seeders (the demo tenant, Phase 3 E05 T8), through the same rules as the API, as the user bound to
 * the current unit of work. Never called from requests. Its own tenant transaction.
 */
@Service
class SearchSeeding(
    private val saved: SavedSearches,
    private val transactions: TenantTransactions,
) {
    /** Saves a search for [words] named [name], unless the current user already has a saved search of that name. */
    fun saveOnce(
        name: String,
        words: String,
    ): Boolean =
        transactions.inTenant(TenantContext.current()) {
            if (saved.list().any { it.name == name }) false else saved.create(name, SearchQuery(q = words)).let { true }
        }
}
