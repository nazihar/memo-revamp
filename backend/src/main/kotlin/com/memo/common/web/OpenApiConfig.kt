package com.memo.common.web

import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.info.License
import io.swagger.v3.oas.models.servers.Server
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** Stable, environment-independent metadata so that `/contracts/openapi.json` is reproducible (drift check). */
@Configuration(proxyBeanMethods = false)
class OpenApiConfig {
    @Bean
    fun openApi(): OpenAPI =
        OpenAPI()
            .info(
                Info()
                    .title("Memo & Meeting Management API")
                    .version("v1")
                    .description("REST API. Errors use RFC 9457 Problem Details (application/problem+json).")
                    .license(License().name("Proprietary")),
            ).servers(listOf(Server().url("/").description("Same origin")))
}
