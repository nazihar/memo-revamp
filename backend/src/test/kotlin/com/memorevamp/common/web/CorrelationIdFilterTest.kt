package com.memorevamp.common.web

import jakarta.servlet.FilterChain
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.slf4j.MDC
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse

class CorrelationIdFilterTest {
    private val filter = CorrelationIdFilter()
    private val uuidShape = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"

    private fun run(inbound: String?): Pair<String?, String?> {
        val request = MockHttpServletRequest().apply { inbound?.let { addHeader(CorrelationIdFilter.HEADER, it) } }
        val response = MockHttpServletResponse()
        var seenInMdc: String? = null
        filter.doFilter(request, response, FilterChain { _, _ -> seenInMdc = MDC.get(CorrelationIdFilter.MDC_KEY) })
        return seenInMdc to response.getHeader(CorrelationIdFilter.HEADER)
    }

    @Test
    fun `inbound UUID is kept, logged and echoed`() {
        val (mdc, header) = run("8d2a6f0e-1c3b-4a8e-9f1d-2b7c5e4a9d10")
        assertThat(mdc).isEqualTo("8d2a6f0e-1c3b-4a8e-9f1d-2b7c5e4a9d10")
        assertThat(header).isEqualTo(mdc)
    }

    @Test
    fun `nginx request_id (32 hex) is accepted as the same id in UUID form`() {
        val (mdc, _) = run("8d2a6f0e1c3b4a8e9f1d2b7c5e4a9d10")
        assertThat(mdc).isEqualTo("8d2a6f0e-1c3b-4a8e-9f1d-2b7c5e4a9d10")
    }

    @Test
    fun `missing id is generated`() {
        val (mdc, header) = run(null)
        assertThat(mdc).matches(uuidShape)
        assertThat(header).isEqualTo(mdc)
    }

    @Test
    fun `anything else is replaced, so client text never reaches logs or headers`() {
        listOf("abc", "x".repeat(65), "id\r\nSet-Cookie: x=y", "8d2a6f0e-1c3b\nFAKE LOG LINE", "<script>", "short-id-1").forEach { bad ->
            val (mdc, header) = run(bad)
            assertThat(mdc).isNotEqualTo(bad).matches(uuidShape)
            assertThat(header).isEqualTo(mdc)
        }
    }

    @Test
    fun `MDC is cleared after the request`() {
        run("8d2a6f0e-1c3b-4a8e-9f1d-2b7c5e4a9d10")
        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull()
    }
}
