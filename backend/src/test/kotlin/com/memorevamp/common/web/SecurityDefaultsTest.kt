package com.memorevamp.common.web

import com.memorevamp.testsupport.IntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.assertj.MockMvcTester

/** Authorization matrix of E01 (design.md §5): deny-by-default, health anonymous, API docs off by default. */
class SecurityDefaultsTest : IntegrationTest() {
    @Autowired
    lateinit var mvc: MockMvcTester

    @Test
    fun `health is available anonymously`() {
        assertThat(mvc.get().uri("/api/v1/health")).hasStatus(HttpStatus.OK)
    }

    @Test
    fun `unknown api route is rejected with 401 problem details`() {
        val result = mvc.get().uri("/api/v1/does-not-exist").exchange()
        assertThat(result).hasStatus(HttpStatus.UNAUTHORIZED)
        assertThat(result).hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
        assertThat(result).bodyJson().extractingPath("$.status").isEqualTo(401)
    }

    @Test
    fun `mutating request without credentials or csrf token is rejected with problem details`() {
        // CSRF protection answers first (403); authentication would answer 401. Either way: never processed.
        val result = mvc.post().uri("/api/v1/health").exchange()
        assertThat(result).hasStatus(HttpStatus.FORBIDDEN)
        assertThat(result).hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
    }

    @Test
    fun `api docs are disabled unless explicitly enabled`() {
        assertThat(mvc.get().uri("/v3/api-docs")).hasStatus(HttpStatus.UNAUTHORIZED)
    }

    @Test
    fun `responses carry strict security headers`() {
        val result = mvc.get().uri("/api/v1/health").exchange()
        assertThat(result).headers().hasValue("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'")
        assertThat(result).headers().hasValue("X-Content-Type-Options", "nosniff")
        assertThat(result).headers().hasValue("Referrer-Policy", "no-referrer")
        assertThat(result).headers().hasValue("X-Frame-Options", "DENY")
        assertThat(result).headers().containsHeader("Cache-Control")
        assertThat(result.response.getHeader("Cache-Control")).contains("no-store")
    }
}
