package com.memo.identity.operator

import com.memo.common.context.Actor
import com.memo.common.context.ActorContext
import com.memo.common.context.ActorScope
import com.memo.common.web.AuthenticatedOperator
import com.memo.common.web.ProblemAccessDeniedHandler
import com.memo.common.web.ProblemAuthenticationEntryPoint
import com.memo.tenancy.PlatformHost
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.annotation.Order
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.access.intercept.AuthorizationFilter
import org.springframework.security.web.context.SecurityContextRepository
import org.springframework.security.web.csrf.CsrfTokenRepository
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter
import org.springframework.web.filter.OncePerRequestFilter
import tools.jackson.databind.json.JsonMapper

/**
 * The operator console's own security chain (ADR-0031), ordered before the tenant chain. It covers the platform API only,
 * which the tenant resolution filter serves on the console's host alone. Sign-in steps are open (the partial ones need a
 * password-proven operator session); everything else needs password and TOTP. A tenant session never authenticates here:
 * its cookie has another name, and the session repository reads only the operator principal on this host.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class OperatorSecurityConfig {
    @Bean
    @Order(2)
    fun platformSecurity(
        http: HttpSecurity,
        jsonMapper: JsonMapper,
        contextRepository: SecurityContextRepository,
        csrfTokenRepository: CsrfTokenRepository,
    ): SecurityFilterChain {
        http
            .securityMatcher("${PlatformHost.API_PREFIX}/**")
            .authorizeHttpRequests { auth ->
                PUBLIC.forEach { auth.requestMatchers("$AUTH/$it").permitAll() }
                PARTIAL.forEach { auth.requestMatchers("$AUTH/$it").authenticated() }
                auth.anyRequest().hasAuthority(AuthenticatedOperator.OPERATOR_SESSION_COMPLETE)
            }.securityContext { it.securityContextRepository(contextRepository) }
            .sessionManagement {
                it.sessionCreationPolicy(SessionCreationPolicy.NEVER)
                it.sessionFixation { fixation -> fixation.none() }
            }.csrf {
                it.spa()
                it.csrfTokenRepository(csrfTokenRepository)
            }.addFilterBefore(OperatorActorFilter(), AuthorizationFilter::class.java)
            .exceptionHandling {
                it.authenticationEntryPoint(ProblemAuthenticationEntryPoint(jsonMapper))
                it.accessDeniedHandler(ProblemAccessDeniedHandler(jsonMapper))
            }.headers { headers ->
                headers.contentSecurityPolicy { it.policyDirectives("default-src 'none'; frame-ancestors 'none'") }
                headers.referrerPolicy { it.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER) }
                headers.frameOptions { it.deny() }
            }.httpBasic { it.disable() }
            .formLogin { it.disable() }
            .logout { it.disable() }
            .requestCache { it.disable() }
        return http.build()
    }

    private companion object {
        const val AUTH = "${PlatformHost.API_PREFIX}/auth"
        val PUBLIC = listOf("csrf", "session", "login", "enrolment/lookup", "enrolment/accept")

        /** Need a password-proven operator session, complete or not. */
        val PARTIAL = listOf("mfa", "mfa/enrolment", "mfa/enrolment/confirm", "step-up", "logout")
    }
}

/** Rebinds the actor to the signed-in operator for the rest of the request (ADR-0017, ADR-0031). */
class OperatorActorFilter : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        chain: FilterChain,
    ) {
        val operator = SecurityContextHolder.getContext().authentication?.principal as? AuthenticatedOperator
        if (operator == null) {
            chain.doFilter(request, response)
        } else {
            ActorContext.call(
                ActorScope(Actor.PlatformOperator(operator.operatorId), ActorContext.currentOrNull()?.client),
            ) { chain.doFilter(request, response) }
        }
    }
}
