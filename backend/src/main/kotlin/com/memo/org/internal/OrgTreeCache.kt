package com.memo.org.internal

import com.memo.common.authz.OrgTree
import com.memo.common.cache.CacheInvalidation
import com.memo.common.cache.TenantCache
import com.memo.common.cache.TenantCaches
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantTransactions
import org.springframework.stereotype.Component
import java.util.UUID

/** The tenant's units with their ancestors, as the policy and the tree screen read them. */
class TreeSnapshot(
    units: List<OrgUnit>,
) {
    private val ancestors: Map<UUID, Set<UUID>> = units.associate { it.id to it.ancestors.toSet() }
    private val descendants: Map<UUID, Set<UUID>> =
        units
            .flatMap { unit -> unit.ancestors.map { it to unit.id } }
            .groupBy({ it.first }, { it.second })
            .mapValues { it.value.toSet() }

    fun isWithin(
        unit: UUID,
        root: UUID,
    ) = unit == root || ancestors[unit]?.contains(root) == true

    fun subtree(root: UUID): Set<UUID> = if (root in ancestors) descendants[root].orEmpty() + root else emptySet()
}

/**
 * The org tree for the policy (ADR-0026, ADR-0027), cached per tenant (ADR-0020) and evicted on every node at commit of
 * any unit change, so scope checks need no query.
 */
@Component
class OrgTreeCache(
    caches: TenantCaches,
    private val transactions: TenantTransactions,
    private val units: UnitStore,
    private val invalidation: CacheInvalidation,
) : OrgTree {
    private val cache: TenantCache<String, TreeSnapshot> = caches.register(CACHE)

    override fun isWithin(
        unit: UUID,
        root: UUID,
    ): Boolean = snapshot().isWithin(unit, root)

    override fun subtree(root: UUID): Set<UUID> = snapshot().subtree(root)

    /** Inside the tenant transaction that changed the tree. */
    fun evict() = invalidation.invalidate(CACHE, TenantContext.current())

    private fun snapshot(): TreeSnapshot {
        val tenant = TenantContext.current()
        return cache.get(KEY) { transactions.readInTenant(tenant) { TreeSnapshot(units.units()) } }
    }

    private companion object {
        const val CACHE = "org.tree"
        const val KEY = "tree"
    }
}
