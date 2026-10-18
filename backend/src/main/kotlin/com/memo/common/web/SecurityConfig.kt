package com.memo.common.web

import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.annotation.Order
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter
import tools.jackson.databind.json.JsonMapper

/**
 * Deny-by-default (AGENTS.md §3.4). Only the endpoints listed in [PUBLIC_ENDPOINTS] are anonymous; everything else needs an
 * authenticated principal. No authentication mechanism exists yet (Phase 1a), so every other request is rejected with 401.
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
        @Value("\${memo.api-docs.enabled}") apiDocsEnabled: Boolean,
    ): SecurityFilterChain {
        http
            .authorizeHttpRequests { auth ->
                PUBLIC_ENDPOINTS.forEach { auth.requestMatchers(it).permitAll() }
                if (apiDocsEnabled) auth.requestMatchers("/v3/api-docs", "/v3/api-docs/**").permitAll()
                auth.anyRequest().authenticated()
            }.sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
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
        val PUBLIC_ENDPOINTS = listOf("/api/v1/health", "/error")
    }
}
