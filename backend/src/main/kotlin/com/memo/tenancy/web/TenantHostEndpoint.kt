package com.memo.tenancy.web

import com.memo.tenancy.PlatformHost
import com.memo.tenancy.TenantResolver
import org.springframework.boot.actuate.endpoint.annotation.Endpoint
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation
import org.springframework.boot.actuate.endpoint.web.WebEndpointResponse
import org.springframework.stereotype.Component

/**
 * Caddy's on-demand TLS `ask` check (ADR-0022): `GET /actuator/tenanthost?domain=<host>` on the management port (never
 * proxied to the internet) answers 200 only for hosts that name an existing tenant or the operator console (ADR-0031), so
 * certificates are issued for real hosts only.
 */
@Component
@Endpoint(id = "tenanthost")
class TenantHostEndpoint(
    private val resolver: TenantResolver,
    private val platformHost: PlatformHost,
) {
    @ReadOperation
    fun check(domain: String): WebEndpointResponse<Map<String, Boolean>> =
        if (resolver.resolve(domain) != null || platformHost.matches(domain)) {
            WebEndpointResponse(mapOf("known" to true), WebEndpointResponse.STATUS_OK)
        } else {
            WebEndpointResponse(mapOf("known" to false), WebEndpointResponse.STATUS_NOT_FOUND)
        }
}
