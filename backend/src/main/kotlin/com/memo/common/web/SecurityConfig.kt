package com.memo.common.web

import com.memo.common.context.Actor
import com.memo.common.context.ActorContext
import com.memo.common.context.ActorScope
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.annotation.Order
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.access.intercept.AuthorizationFilter
import org.springframework.security.web.context.SecurityContextRepository
import org.springframework.security.web.csrf.CookieCsrfTokenRepository
import org.springframework.security.web.csrf.CsrfTokenRepository
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter
import org.springframework.web.filter.OncePerRequestFilter
import tools.jackson.databind.json.JsonMapper

/**
 * Deny-by-default (AGENTS.md §3.4). Only [PUBLIC_ENDPOINTS] are anonymous; `/auth/logout` accepts any session; every
 * other endpoint needs a complete session (all required factors, ADR-0023/0024). Authentication comes from the session
 * principal through the identity module's [SecurityContextRepository]; Spring Security never creates sessions itself.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class SecurityConfig {
    /**
     * Actuator (management port 8081, never proxied): only the health probes are anonymous; every other endpoint
     * is denied until an operator identity exists (Phase 1a/7).
     */
    @Bean
    @Order(1)
    fun managementSecurity(
        http: HttpSecurity,
        jsonMapper: JsonMapper,
    ): SecurityFilterChain {
        http
            .securityMatcher(EndpointRequest.toAnyEndpoint())
            .authorizeHttpRequests { auth ->
                auth.requestMatchers(EndpointRequest.to("health", "tenanthost")).permitAll()
                auth.anyRequest().denyAll()
            }.sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .exceptionHandling { it.authenticationEntryPoint(ProblemAuthenticationEntryPoint(jsonMapper)) }
            .httpBasic { it.disable() }
            .formLogin { it.disable() }
        return http.build()
    }

    @Bean
    @Order(2)
    fun apiSecurity(
        http: HttpSecurity,
        jsonMapper: JsonMapper,
        contextRepository: SecurityContextRepository,
        csrfTokenRepository: CsrfTokenRepository,
        @Value("\${memo.api-docs.enabled}") apiDocsEnabled: Boolean,
    ): SecurityFilterChain {
        http
            .authorizeHttpRequests { auth ->
                PUBLIC_ENDPOINTS.forEach { auth.requestMatchers(it).permitAll() }
                if (apiDocsEnabled) auth.requestMatchers("/v3/api-docs", "/v3/api-docs/**").permitAll()
                auth.requestMatchers("/api/v1/auth/logout").authenticated()
                auth.anyRequest().hasAuthority(AuthenticatedUser.SESSION_COMPLETE)
            }.securityContext { it.securityContextRepository(contextRepository) }
            .sessionManagement {
                it.sessionCreationPolicy(SessionCreationPolicy.NEVER)
                // The identity module starts a fresh session on every sign-in (SessionStore.start).
                it.sessionFixation { fixation -> fixation.none() }
            }.csrf {
                it.spa()
                it.csrfTokenRepository(csrfTokenRepository)
            }.addFilterBefore(UserActorFilter(), AuthorizationFilter::class.java)
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

    companion object {
        val PUBLIC_ENDPOINTS =
            listOf(
                "/api/v1/health",
                "/error",
                "/api/v1/auth/csrf",
                "/api/v1/auth/session",
                "/api/v1/auth/login",
            )
    }
}

/**
 * The `XSRF-TOKEN` cookie store, readable by the SPA (`csrf { spa() }`), host-only like the session cookie. Outside
 * [SecurityConfig] because the auth endpoints use it too, and they exist in every application mode.
 */
@Configuration(proxyBeanMethods = false)
class CsrfConfig {
    @Bean
    fun csrfTokenRepository(
        @Value("\${memo.security.cookie-secure:true}") secure: Boolean,
    ): CsrfTokenRepository =
        CookieCsrfTokenRepository.withHttpOnlyFalse().apply {
            setCookieCustomizer { it.secure(secure).sameSite("Lax").path("/") }
        }
}

/**
 * Rebinds the actor to the signed-in user for the rest of the request (ADR-0017). [ActorContextFilter] binds
 * [Actor.Anonymous] earlier, for everything that runs before authentication is known.
 */
class UserActorFilter : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        chain: FilterChain,
    ) {
        val user = SecurityContextHolder.getContext().authentication?.principal as? AuthenticatedUser
        if (user == null) {
            chain.doFilter(request, response)
        } else {
            ActorContext.call(
                ActorScope(Actor.User(user.userId), ActorContext.currentOrNull()?.client),
            ) { chain.doFilter(request, response) }
        }
    }
}
