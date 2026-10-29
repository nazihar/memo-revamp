package com.memo.tenancy

import com.memo.testsupport.IntegrationTest
import com.memo.testsupport.onHost
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

/** ADR-0022: the request host picks the tenant before authentication; unknown hosts get 404. */
class TenantResolutionIT : IntegrationTest() {
    @Autowired lateinit var mvc: MockMvc

    @Autowired lateinit var registry: TenantRegistry

    @Test
    fun `a known tenant host reaches the security layer and an unknown host is 404`() {
        val slug = "res-${UUID.randomUUID().toString().take(8)}"
        registry.create(slug, "Resolution test")
        // Known host: resolution passes, then Spring Security asks for authentication.
        mvc.perform(get("/api/v1/anything").with(onHost(slug))).andExpect(status().isUnauthorized)
        // Unknown host: stopped before security, without hinting at other tenants.
        mvc
            .perform(get("/api/v1/anything").with(onHost("nobody-${UUID.randomUUID().toString().take(6)}")))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.detail").value("Unknown organisation."))
    }

    @Test
    fun `tenant-independent endpoints work on any host`() {
        mvc.perform(get("/api/v1/health").with(onHost("unknown"))).andExpect(status().isOk)
    }
}
