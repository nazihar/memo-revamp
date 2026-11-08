package com.memo.tenancy.web

import com.memo.common.tenant.TenantContext
import com.memo.common.web.ProblemTypes
import com.memo.common.web.writeProblem
import com.memo.tenancy.PlatformHost
import com.memo.tenancy.TenantResolver
import com.memo.tenancy.TenantStatus
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
 *
 * The operator console's host (ADR-0031) serves the platform API and nothing else, without a tenant; tenant hosts never
 * serve the platform API. Both refusals are 404, as for any path that does not exist. A suspended tenant's host answers
 * 403 `tenant-suspended` to every request (ADR-0031): one place, so no endpoint can forget it.
 */
@Component
@Order(TenantResolutionFilter.ORDER)
class TenantResolutionFilter(
    private val resolver: TenantResolver,
    private val platformHost: PlatformHost,
    private val jsonMapper: JsonMapper,
) : OncePerRequestFilter() {
    override fun shouldNotFilter(request: HttpServletRequest): Boolean {
        val path = path(request)
        return !path.startsWith("/api/") || TENANT_INDEPENDENT.any { path == it || path.startsWith("$it/") }
    }

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        chain: FilterChain,
    ) {
        val path = path(request)
        val platformPath = path == PlatformHost.API_PREFIX || path.startsWith("${PlatformHost.API_PREFIX}/")
        val onPlatformHost = platformHost.matches(request.serverName)
        when {
            onPlatformHost && platformPath -> {
                request.setAttribute(PlatformHost.REQUEST_ATTRIBUTE, true)
                chain.doFilter(request, response)
            }

            onPlatformHost || platformPath -> {
                response.writeProblem(jsonMapper, HttpStatus.NOT_FOUND, "Not found.", request.requestURI)
            }

            else -> {
                resolveTenant(request, response, chain)
            }
        }
    }

    private fun resolveTenant(
        request: HttpServletRequest,
        response: HttpServletResponse,
        chain: FilterChain,
    ) {
        val tenant = resolver.lookup(request.serverName)
        when (tenant?.status) {
            null -> {
                response.writeProblem(jsonMapper, HttpStatus.NOT_FOUND, "Unknown organisation.", request.requestURI)
            }

            TenantStatus.SUSPENDED -> {
                response.writeProblem(
                    jsonMapper,
                    HttpStatus.FORBIDDEN,
                    "This organisation is suspended.",
                    request.requestURI,
                    ProblemTypes.TENANT_SUSPENDED,
                )
            }

            TenantStatus.ACTIVE -> {
                TenantContext.call(tenant.id) { chain.doFilter(request, response) }
            }
        }
    }

    private fun path(request: HttpServletRequest) = request.requestURI.removePrefix(request.contextPath)

    companion object {
        /** After the correlation id, before Spring Security (whose filter chain runs at -100). */
        const val ORDER = Ordered.HIGHEST_PRECEDENCE + 5

        /** Endpoints that work on any host and need no tenant. */
        val TENANT_INDEPENDENT = listOf("/api/v1/health")
    }
}
