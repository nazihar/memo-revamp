package com.memo.common.cache

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantId
import org.jooq.DSLContext
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/**
 * An in-process cache of one kind of read-mostly tenant configuration (ADR-0020). Entries are keyed by tenant, so a cache
 * never serves one tenant's value to another. Values are evicted across nodes by [CacheInvalidation] at commit; the TTL is
 * a safety net only.
 */
class TenantCache<K : Any, V : Any> internal constructor(
    val name: String,
    ttl: Duration,
    maxSize: Long,
) {
    private val cache: Cache<Pair<TenantId, K>, V> =
        Caffeine
            .newBuilder()
            .expireAfterWrite(ttl)
            .maximumSize(maxSize)
            .build()

    /**
     * The cached value for [key] in the current tenant, loading it with [loader] on a miss. Inside a transaction that has
     * already changed this cache's data, the cache is bypassed, so a transaction always reads its own writes.
     */
    fun get(
        key: K,
        loader: () -> V,
    ): V {
        val tenant = TenantContext.current()
        if (CacheInvalidation.isDirtyInCurrentTransaction(name)) return loader()
        return cache.get(tenant to key) { loader() }
    }

    internal fun evict(
        tenant: TenantId,
        key: String?,
    ) {
        if (key == null) {
            cache.asMap().keys.removeIf { it.first == tenant }
        } else {
            cache.asMap().keys.removeIf { it.first == tenant && it.second.toString() == key }
        }
    }

    internal fun evictAll() = cache.invalidateAll()

    internal fun size(): Long = cache.estimatedSize()
}

/** Registry of every tenant cache on this node, so invalidations and reconnect flushes reach all of them. */
@Component
class TenantCaches {
    private val caches = ConcurrentHashMap<String, TenantCache<*, *>>()

    fun <K : Any, V : Any> register(
        name: String,
        ttl: Duration = DEFAULT_TTL,
        maxSize: Long = DEFAULT_MAX_SIZE,
    ): TenantCache<K, V> {
        val cache = TenantCache<K, V>(name, ttl, maxSize)
        check(caches.putIfAbsent(name, cache) == null) { "cache '$name' registered twice" }
        return cache
    }

    internal fun evict(
        name: String,
        tenant: TenantId,
        key: String?,
    ) {
        caches[name]?.evict(tenant, key)
    }

    internal fun evictAll() = caches.values.forEach { it.evictAll() }

    companion object {
        val DEFAULT_TTL: Duration = Duration.ofMinutes(10)
        const val DEFAULT_MAX_SIZE = 10_000L
    }
}

/**
 * Evicts cache entries on every node when the current transaction commits (ADR-0020): `pg_notify` in the transaction
 * (PostgreSQL delivers it only at commit, never on rollback) plus a local eviction after commit, so this node's next read is
 * fresh without waiting for its own notification.
 */
@Component
class CacheInvalidation(
    private val dsl: DSLContext,
    private val caches: TenantCaches,
) {
    fun invalidate(
        cache: String,
        tenant: TenantId,
        key: String? = null,
    ) {
        val payload = CacheMessage(cache, tenant, key).encode()
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            dsl.fetch("select pg_notify(?, ?)", CHANNEL, payload)
            caches.evict(cache, tenant, key)
            return
        }
        dsl.fetch("select pg_notify(?, ?)", CHANNEL, payload)
        dirtyCaches().add(cache)
        TransactionSynchronizationManager.registerSynchronization(
            object : TransactionSynchronization {
                override fun afterCommit() = caches.evict(cache, tenant, key)
            },
        )
    }

    companion object {
        const val CHANNEL = "memo_cache"
        private val DIRTY_KEY = Any()

        @Suppress("UNCHECKED_CAST")
        private fun dirtyCaches(): MutableSet<String> {
            val existing = TransactionSynchronizationManager.getResource(DIRTY_KEY) as MutableSet<String>?
            if (existing != null) return existing
            val created = mutableSetOf<String>()
            TransactionSynchronizationManager.bindResource(DIRTY_KEY, created)
            TransactionSynchronizationManager.registerSynchronization(
                object : TransactionSynchronization {
                    override fun afterCompletion(status: Int) {
                        TransactionSynchronizationManager.unbindResourceIfPossible(DIRTY_KEY)
                    }
                },
            )
            return created
        }

        @Suppress("UNCHECKED_CAST")
        internal fun isDirtyInCurrentTransaction(cache: String): Boolean =
            TransactionSynchronizationManager.isActualTransactionActive() &&
                (TransactionSynchronizationManager.getResource(DIRTY_KEY) as Set<String>?)?.contains(cache) == true
    }
}

/** The notification payload: cache name, tenant and optional key, tab-separated. Ids only, never values. */
internal data class CacheMessage(
    val cache: String,
    val tenant: TenantId,
    val key: String?,
) {
    fun encode(): String = listOf(cache, tenant.toString(), key ?: "").joinToString("\t")

    companion object {
        private const val PARTS = 3

        fun decode(payload: String): CacheMessage? {
            val parts = payload.split('\t', limit = PARTS)
            val tenant = parts.getOrNull(1)?.let { runCatching { TenantId(java.util.UUID.fromString(it)) }.getOrNull() }
            return if (parts.size == PARTS && tenant != null) CacheMessage(parts[0], tenant, parts[2].ifEmpty { null }) else null
        }
    }
}
