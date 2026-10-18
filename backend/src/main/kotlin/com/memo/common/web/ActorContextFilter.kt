package com.memo.common.web

import com.memo.common.context.Actor
import com.memo.common.context.ActorContext
import com.memo.common.context.ActorScope
import com.memo.common.context.ClientInfo
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Binds the request's actor and client to the rest of the request (ADR-0017). Until authentication exists (E03) every
 * request is [Actor.Anonymous]; E03 adds the tenant (from the host name) and the signed-in user.
 */
@Component
@Order(ActorContextFilter.ORDER)
class ActorContextFilter : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        chain: FilterChain,
    ) {
        val client = ClientInfo.of(request.remoteAddr, request.getHeader("User-Agent"))
        ActorContext.call(ActorScope(Actor.Anonymous, client)) { chain.doFilter(request, response) }
    }

    companion object {
        /** Right after [CorrelationIdFilter], so the actor scope covers everything that logs or audits. */
        const val ORDER = Ordered.HIGHEST_PRECEDENCE + 10
    }
}
