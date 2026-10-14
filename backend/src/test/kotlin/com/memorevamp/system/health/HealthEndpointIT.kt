package com.memorevamp.system.health

import com.memorevamp.testsupport.IntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.assertj.MockMvcTester
import tools.jackson.databind.json.JsonMapper

/** Real PostgreSQL via Testcontainers; other integrations are not configured → DISABLED. */
class HealthEndpointIT : IntegrationTest() {
    @Autowired
    lateinit var mvc: MockMvcTester

    @Autowired
    lateinit var jsonMapper: JsonMapper

    @Test
    fun `reports UP with the database reachable and unconfigured integrations disabled`() {
        val result = mvc.get().uri("/api/v1/health").exchange()

        assertThat(result).hasStatus(HttpStatus.OK)
        assertThat(result).hasContentTypeCompatibleWith(MediaType.APPLICATION_JSON)
        val json = assertThat(result).bodyJson()
        json.extractingPath("$.status").isEqualTo("UP")
        json.extractingPath("$.components[0].name").isEqualTo("database")
        json.extractingPath("$.components[0].status").isEqualTo("UP")
        json.extractingPath("$.components[*].name").asArray().containsExactly(
            "database",
            "objectStorage",
            "documentServer",
            "malwareScanner",
            "mail",
        )
        json.extractingPath("$.components[1:].status").asArray().containsOnly("DISABLED")
    }

    @Test
    fun `response contains only the documented fields`() {
        val body =
            mvc
                .get()
                .uri("/api/v1/health")
                .exchange()
                .response.contentAsString
        val tree = jsonMapper.readTree(body)

        assertThat(tree.propertyNames()).containsExactlyInAnyOrder("status", "checkedAt", "components")
        tree.get("components").forEach { component ->
            assertThat(component.propertyNames()).containsExactlyInAnyOrder("name", "status")
        }
        // Threat model I1: no versions, hosts, ports or exception text.
        assertThat(body)
            .doesNotContainIgnoringCase("version")
            .doesNotContainIgnoringCase("postgres")
            .doesNotContainIgnoringCase("localhost")
            .doesNotContainIgnoringCase("exception")
            .doesNotContain("jdbc")
    }
}
