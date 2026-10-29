package com.memo.tenancy.web

import com.memo.common.tenant.TenantContext
import com.memo.common.web.writeProblem
import com.memo.tenancy.TenantResolver
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import tools.jackson.databind.json.JsonMapper

/**
 * Binds the tenant named by the request host for the rest of the request (ADR-0022), before Spring Security, so the login
 * endpoints know whose accounts to check. Tenant-independent endpoints (health, API docs) are skipped. An unknown host
 * gets 404 without revealing whether other tenants exist.
 */
@Component
@Order(TenantResolutionFilter.ORDER)
class TenantResolutionFilter(
    private val resolver: TenantResolver,
    private val jsonMapper: JsonMapper,
) : OncePerRequestFilter() {
    override fun shouldNotFilter(request: HttpServletRequest): Boolean {
        val path = request.requestURI.removePrefix(request.contextPath)
        return !path.startsWith("/api/") || TENANT_INDEPENDENT.any { path == it || path.startsWith("$it/") }
    }

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        chain: FilterChain,
    ) {
        val tenant = resolver.resolve(request.serverName)
        if (tenant == null) {
            response.writeProblem(jsonMapper, HttpStatus.NOT_FOUND, "Unknown organisation.", request.requestURI)
            return
        }
        TenantContext.call(tenant) { chain.doFilter(request, response) }
    }

    companion object {
        /** After the correlation id, before Spring Security (whose filter chain runs at -100). */
        const val ORDER = Ordered.HIGHEST_PRECEDENCE + 5

        /** Endpoints that work on any host and need no tenant. */
        val TENANT_INDEPENDENT = listOf("/api/v1/health")
    }
}
