package com.memo.tenancy.web

import com.memo.tenancy.TenantResolver
import org.springframework.boot.actuate.endpoint.annotation.Endpoint
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation
import org.springframework.boot.actuate.endpoint.web.WebEndpointResponse
import org.springframework.stereotype.Component

/**
 * Caddy's on-demand TLS `ask` check (ADR-0022): `GET /actuator/tenanthost?domain=<host>` on the management port (never
 * proxied to the internet) answers 200 only for hosts that name an existing tenant, so certificates are issued for real
 * tenant hosts only.
 */
@Component
@Endpoint(id = "tenanthost")
class TenantHostEndpoint(
    private val resolver: TenantResolver,
) {
    @ReadOperation
    fun check(domain: String): WebEndpointResponse<Map<String, Boolean>> =
        if (resolver.resolve(domain) != null) {
            WebEndpointResponse(mapOf("known" to true), WebEndpointResponse.STATUS_OK)
        } else {
            WebEndpointResponse(mapOf("known" to false), WebEndpointResponse.STATUS_NOT_FOUND)
        }
}
