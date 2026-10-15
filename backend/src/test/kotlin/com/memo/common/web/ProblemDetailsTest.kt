package com.memo.common.web

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.assertj.MockMvcTester
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

/** Threat model I3: unexpected errors become a generic RFC 9457 problem without exception details. */
class ProblemDetailsTest {
    @RestController
    class FailingController {
        @GetMapping("/boom")
        fun boom(): String = throw IllegalStateException("secret-internal-detail at db-host-17")
    }

    private val mvc =
        MockMvcTester.create(
            MockMvcBuilders
                .standaloneSetup(FailingController())
                .setControllerAdvice(ProblemDetailsAdvice())
                .build(),
        )

    @Test
    fun `unexpected exception becomes a generic 500 problem`() {
        val result = mvc.get().uri("/boom").exchange()
        assertThat(result).hasStatus(HttpStatus.INTERNAL_SERVER_ERROR)
        assertThat(result).hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
        assertThat(result).bodyJson().extractingPath("$.detail").isEqualTo("An unexpected error occurred.")
        assertThat(result.response.contentAsString)
            .doesNotContain("secret-internal-detail")
            .doesNotContain("IllegalStateException")
            .doesNotContain("db-host-17")
    }

    @Test
    fun `framework errors are problem details too`() {
        val result = mvc.get().uri("/no-such-route").exchange()
        assertThat(result).hasStatus(HttpStatus.NOT_FOUND)
        assertThat(result).hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
    }
}
