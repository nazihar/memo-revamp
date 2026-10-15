package com.memo.system

import com.memo.testsupport.TestDatabase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/** ADR-0016: liveness/readiness on the management port only; actuator unreachable on the public port. */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["management.server.port=0"],
)
class ProbesIT {
    @LocalServerPort
    var port: Int = 0

    @Value("\${local.management.port}")
    var managementPort: Int = 0

    private val http = HttpClient.newHttpClient()

    private fun get(url: String): HttpResponse<String> =
        http.send(HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofString())

    @Test
    fun `liveness and readiness are UP on the management port`() {
        listOf("liveness", "readiness").forEach { probe ->
            val response = get("http://localhost:$managementPort/actuator/health/$probe")
            assertThat(response.statusCode()).describedAs(probe).isEqualTo(200)
            assertThat(response.body()).contains("\"status\":\"UP\"").doesNotContain("details")
        }
    }

    @Test
    fun `other actuator endpoints are denied even on the management port`() {
        assertThat(get("http://localhost:$managementPort/actuator/env").statusCode()).isIn(401, 403, 404)
    }

    @Test
    fun `actuator is not served on the public port`() {
        assertThat(get("http://localhost:$port/actuator/health").statusCode()).isIn(401, 404)
    }

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun databaseProperties(registry: DynamicPropertyRegistry) {
            TestDatabase.properties().forEach { (key, value) -> registry.add(key) { value } }
        }
    }
}
