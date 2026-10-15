package com.memorevamp.common.web

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.MDC
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.util.UUID

/**
 * Correlation id for every request (ADR-0016). An inbound `X-Request-Id` is honoured only if it is a UUID or nginx's
 * 32-hex `$request_id`; it is then *re-rendered* as a canonical UUID from the parsed numbers, so no client-supplied text
 * ever reaches logs or response headers (no log injection, no response splitting). Otherwise a new id is generated.
 * Stored in the MDC as `correlation.id` for structured logs and echoed in the response.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class CorrelationIdFilter : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        chain: FilterChain,
    ) {
        val id = (parse(request.getHeader(HEADER)) ?: UUID.randomUUID()).toString()
        MDC.put(MDC_KEY, id)
        response.setHeader(HEADER, id)
        try {
            chain.doFilter(request, response)
        } finally {
            MDC.remove(MDC_KEY)
        }
    }

    companion object {
        const val HEADER = "X-Request-Id"
        const val MDC_KEY = "correlation.id"
        private val CANONICAL_UUID = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
        private val HEX32 = Regex("^[0-9a-fA-F]{32}$")
        private const val HEX_RADIX = 16
        private const val HEX_DIGITS_PER_LONG = 16

        /** A UUID or 32 hex digits (nginx `$request_id`) → UUID built from the parsed bits; anything else → null. */
        fun parse(value: String?): UUID? =
            when {
                value == null -> {
                    null
                }

                CANONICAL_UUID.matches(value) -> {
                    UUID.fromString(value)
                }

                HEX32.matches(value) -> {
                    UUID(
                        java.lang.Long.parseUnsignedLong(value.substring(0, HEX_DIGITS_PER_LONG), HEX_RADIX),
                        java.lang.Long.parseUnsignedLong(value.substring(HEX_DIGITS_PER_LONG), HEX_RADIX),
                    )
                }

                else -> {
                    null
                }
            }
    }
}
