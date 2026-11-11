package com.memo.docedit.internal

import com.memo.common.web.SecurityConfig
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.apache.catalina.connector.Connector
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory
import org.springframework.boot.web.server.WebServerFactoryCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

/**
 * The internal connector of ADR-0037: a second port that only the document server reaches (Compose internal network,
 * Kubernetes NetworkPolicy) and that serves only the paths under `/internal/docedit/`. Neither the edge nor the
 * ingress routes it.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class InternalConnectorConfig(
    private val properties: DocEditProperties,
) {
    @Bean
    fun doceditConnector(): WebServerFactoryCustomizer<TomcatServletWebServerFactory> =
        WebServerFactoryCustomizer { factory ->
            if (properties.enabled) factory.addAdditionalConnectors(Connector().apply { port = properties.internalPort })
        }

    /** No user session, no CSRF: every request is authenticated by a signed link or the document server's token. */
    @Bean
    @Order(SecurityConfig.TENANT_CHAIN_ORDER - 1)
    fun doceditInternalSecurity(http: HttpSecurity): SecurityFilterChain {
        http
            .securityMatcher("$PREFIX/**")
            .authorizeHttpRequests { it.anyRequest().permitAll() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .csrf { it.disable() }
            .httpBasic { it.disable() }
            .formLogin { it.disable() }
            .logout { it.disable() }
            .requestCache { it.disable() }
        return http.build()
    }

    companion object {
        const val PREFIX = "/internal/docedit"
    }
}

/**
 * Keeps the two connectors apart: paths under `/internal/` answer 404 on the public port, and the internal port
 * answers 404 to everything else. The first filter in the chain, so nothing else sees a request on the wrong port.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class InternalPortFilter(
    private val properties: DocEditProperties,
) : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        chain: FilterChain,
    ) {
        val path = request.requestURI.removePrefix(request.contextPath)
        val internalPath = path.startsWith("/internal/")
        val internalPort = properties.enabled && request.localPort == properties.internalPort
        val allowed =
            if (internalPort) {
                path.startsWith("${InternalConnectorConfig.PREFIX}/")
            } else {
                !internalPath
            }
        if (allowed) chain.doFilter(request, response) else response.sendError(HttpServletResponse.SC_NOT_FOUND)
    }
}
