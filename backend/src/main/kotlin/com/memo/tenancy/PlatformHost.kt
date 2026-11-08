package com.memo.tenancy

import jakarta.servlet.http.HttpServletRequest
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component
import java.util.Locale

/**
 * The platform operator console (ADR-0031). [host] empty means `admin.<baseDomain>` in multi-tenant mode and no console in
 * single-tenant mode; [enabled] false switches it off everywhere. [publicUrl] is where operators open it, for the
 * enrolment link; empty means `https://<host>`.
 */
@ConfigurationProperties("memo.platform")
data class PlatformProperties(
    val enabled: Boolean = true,
    val host: String = "",
    val publicUrl: String = "",
)

/** Which host serves the operator console, and whether a request came to it (ADR-0031). */
@Component
class PlatformHost(
    platform: PlatformProperties,
    tenancy: TenancyProperties,
) {
    /** The console's host name, or null when there is no console. */
    val name: String? =
        when {
            !platform.enabled -> null
            platform.host.isNotBlank() -> hostName(platform.host)
            tenancy.mode == TenancyProperties.Mode.MULTI -> "$RESERVED_SLUG.${hostName(tenancy.baseDomain)}"
            else -> null
        }

    /** Where operators open the console, without a trailing slash. */
    val publicUrl: String? = name?.let { platform.publicUrl.ifBlank { "https://$it" }.trimEnd('/') }

    fun matches(host: String): Boolean = name != null && hostName(host) == name

    companion object {
        /** Set by the tenant resolution filter on requests to the console's host. */
        const val REQUEST_ATTRIBUTE = "memo.platformHost"

        /** The console's API; served only on its host, and nothing else is served there. */
        const val API_PREFIX = "/api/v1/platform"

        /** The default console host's first label; no tenant may use it. */
        const val RESERVED_SLUG = "admin"

        fun isPlatformRequest(request: HttpServletRequest): Boolean = request.getAttribute(REQUEST_ATTRIBUTE) == true
    }
}

/** A request host as compared everywhere: without port and trailing dot, lower case. */
fun hostName(host: String): String =
    host
        .substringBefore(':')
        .trim()
        .trimEnd('.')
        .lowercase(Locale.ROOT)
