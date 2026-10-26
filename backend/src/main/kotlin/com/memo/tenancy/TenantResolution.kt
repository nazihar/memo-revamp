package com.memo.tenancy

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
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

/** Resolves a request host to a tenant id; cached, because it runs on every API request. */
@Component
class TenantResolver(
    private val properties: TenancyProperties,
    private val registry: TenantRegistry,
) {
    private val cache: Cache<String, Optional<TenantId>> =
        Caffeine
            .newBuilder()
            .expireAfterWrite(CACHE_TTL)
            .maximumSize(CACHE_SIZE)
            .build()

    /** The slug a host names under the current mode, or null when the host names no tenant. */
    fun slugFor(host: String): String? {
        val name =
            host
                .substringBefore(':')
                .trim()
                .trimEnd('.')
                .lowercase(Locale.ROOT)
        val base = properties.baseDomain.lowercase(Locale.ROOT)
        return when {
            properties.mode == TenancyProperties.Mode.SINGLE -> properties.singleTenant.ifBlank { null }
            name == base -> properties.fallbackTenant.ifBlank { null }
            name.endsWith(".$base") -> name.removeSuffix(".$base").takeIf { SLUG.matches(it) }
            else -> null
        }
    }

    fun resolve(host: String): TenantId? {
        val slug = slugFor(host) ?: return null
        return cache.get(slug) { Optional.ofNullable(registry.findIdBySlug(it)) }.orElse(null)
    }

    /** Drops cached lookups (tenant created, renamed or removed; E07 calls it). */
    fun invalidate() = cache.invalidateAll()

    private companion object {
        val SLUG = Regex("^[a-z0-9](?:[a-z0-9-]{1,61}[a-z0-9])$")
        val CACHE_TTL: Duration = Duration.ofMinutes(1)
        const val CACHE_SIZE = 10_000L
    }
}
