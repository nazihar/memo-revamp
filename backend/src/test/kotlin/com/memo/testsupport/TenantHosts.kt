package com.memo.testsupport

import com.memo.tenancy.TenantRegistry
import org.springframework.test.web.servlet.request.RequestPostProcessor
import java.util.UUID

/** Requests on a tenant's host (ADR-0022): tests use `<slug>.localhost`, the default base domain. */
fun onHost(slug: String): RequestPostProcessor =
    RequestPostProcessor { request ->
        request.serverName = "$slug.localhost"
        request
    }

/** Creates a fresh tenant and returns its slug, for API tests that need a tenant host. */
fun TenantRegistry.aTenantSlug(label: String = "t"): String {
    val slug = "$label-${UUID.randomUUID().toString().take(8)}"
    create(slug = slug, name = "Test tenant $slug")
    return slug
}
