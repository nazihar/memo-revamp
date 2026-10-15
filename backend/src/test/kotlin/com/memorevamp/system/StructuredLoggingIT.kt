package com.memorevamp.system

import com.memorevamp.common.tenant.TenantTransactions
import com.memorevamp.common.web.CorrelationIdFilter
import com.memorevamp.tenancy.TenantRegistry
import com.memorevamp.testsupport.IntegrationTest
import com.memorevamp.testsupport.aTenant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.test.context.TestPropertySource
import tools.jackson.databind.json.JsonMapper

/** ADR-0016: deployed environments log ECS JSON lines carrying the correlation id and the bound tenant id. */
@ExtendWith(OutputCaptureExtension::class)
@TestPropertySource(properties = ["logging.structured.format.console=ecs"])
class StructuredLoggingIT : IntegrationTest() {
    @Autowired lateinit var transactions: TenantTransactions

    @Autowired lateinit var registry: TenantRegistry

    @Autowired lateinit var jsonMapper: JsonMapper

    private val log = LoggerFactory.getLogger(javaClass)

    @Test
    fun `log lines are ECS JSON with correlation and tenant ids`(output: CapturedOutput) {
        val tenant = registry.aTenant("logs")
        MDC.put(CorrelationIdFilter.MDC_KEY, "8d2a6f0e-1c3b-4a8e-9f1d-2b7c5e4a9d10")
        try {
            transactions.inTenant(tenant) { log.info("structured-logging-probe") }
        } finally {
            MDC.remove(CorrelationIdFilter.MDC_KEY)
        }

        val line = output.out.lines().single { it.contains("structured-logging-probe") }
        val json = jsonMapper.readTree(line)
        assertThat(json.path("message").asString()).isEqualTo("structured-logging-probe")
        assertThat(json.path("log").path("level").asString()).isEqualTo("INFO")
        // ECS nests dotted MDC keys: correlation.id → {"correlation":{"id":…}}
        assertThat(json.path("correlation").path("id").asString()).isEqualTo("8d2a6f0e-1c3b-4a8e-9f1d-2b7c5e4a9d10")
        assertThat(json.path("tenant").path("id").asString()).isEqualTo(tenant.toString())
    }
}
