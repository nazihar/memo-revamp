package com.memo.tenancy

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import com.memo.common.cache.TenantCaches
import com.memo.common.tenant.TenantId
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.Locale
import java.util.Optional

/**
 * How requests find their tenant (ADR-0022).
 * - `multi`: from the host `<slug>.<baseDomain>`; the bare base domain maps to [fallbackTenant] when set (local dev and
 *   the dev VM), otherwise to no tenant.
 * - `single`: every request belongs to [singleTenant] (dedicated and on-prem installs).
 */
@ConfigurationProperties("memo.tenancy")
data class TenancyProperties(
    val mode: Mode = Mode.MULTI,
    val baseDomain: String = "localhost",
    val singleTenant: String = "",
    val fallbackTenant: String = "",
    /**
     * Where people open memo, for links in emails: `{slug}` is replaced by the tenant's slug. Empty means
     * `https://{slug}.<baseDomain>`; single-tenant installs set their one address.
     */
    val publicUrlTemplate: String = "",
) {
    enum class Mode { MULTI, SINGLE }

    /** The web address of the tenant with [slug], without a trailing slash. */
    fun publicUrl(slug: String): String = publicUrlTemplate.ifBlank { "https://{slug}.$baseDomain" }.replace("{slug}", slug).trimEnd('/')
}

/** A host's tenant and whether it may be used (ADR-0031: a suspended tenant's host answers 403). */
data class ResolvedTenant(
    val id: TenantId,
    val status: TenantStatus,
)

/**
 * Resolves a request host to a tenant; cached, because it runs on every API request. Lifecycle changes evict the cache
 * on every node through [CACHE]'s invalidation messages (ADR-0020); the TTL is a safety net.
 */
@Component
class TenantResolver(
    private val properties: TenancyProperties,
    private val registry: TenantRegistry,
    caches: TenantCaches,
) {
    private val cache: Cache<String, Optional<ResolvedTenant>> =
        Caffeine
            .newBuilder()
            .expireAfterWrite(CACHE_TTL)
            .maximumSize(CACHE_SIZE)
            .build()

    init {
        caches.onEvict(CACHE) { invalidate() }
    }

    /** The slug a host names under the current mode, or null when the host names no tenant. */
    fun slugFor(host: String): String? {
        val name = hostName(host)
        val base = properties.baseDomain.lowercase(Locale.ROOT)
        return when {
            properties.mode == TenancyProperties.Mode.SINGLE -> properties.singleTenant.ifBlank { null }
            name == base -> properties.fallbackTenant.ifBlank { null }
            name.endsWith(".$base") -> name.removeSuffix(".$base").takeIf { SLUG.matches(it) }
            else -> null
        }
    }

    /** The tenant [host] names, in any status. */
    fun resolve(host: String): TenantId? = lookup(host)?.id

    /** The tenant [host] names, with its status. */
    fun lookup(host: String): ResolvedTenant? {
        val slug = slugFor(host) ?: return null
        return cache.get(slug) { Optional.ofNullable(registry.findBySlug(it)) }.orElse(null)
    }

    /** Drops cached lookups on this node; lifecycle changes reach every node through [CACHE]. */
    fun invalidate() = cache.invalidateAll()

    companion object {
        /** Invalidation channel name for host resolution (`CacheInvalidation.invalidate(CACHE, tenant)`). */
        const val CACHE = "tenancy.resolver"

        private val SLUG = Regex("^[a-z0-9](?:[a-z0-9-]{1,61}[a-z0-9])$")
        private val CACHE_TTL: Duration = Duration.ofMinutes(1)
        private const val CACHE_SIZE = 10_000L
    }
}
