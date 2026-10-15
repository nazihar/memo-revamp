package com.memo.contract

import com.memo.testsupport.IntegrationTest
import com.memo.testsupport.repoRoot
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.assertj.MockMvcTester
import tools.jackson.databind.JsonNode
import tools.jackson.databind.SerializationFeature
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files

/**
 * OpenAPI drift check (design.md §13).
 *  - normal run: the generated spec must equal the committed `/contracts/openapi.json`;
 *  - `./gradlew :backend:updateContract`: writes the generated spec to `/contracts/openapi.json`.
 */
@TestPropertySource(properties = ["memo.api-docs.enabled=true"])
class OpenApiContractTest : IntegrationTest() {
    @Autowired
    lateinit var mvc: MockMvcTester

    private val mapper: JsonMapper =
        JsonMapper
            .builder()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build()

    private val contractFile = repoRoot.resolve("contracts/openapi.json")

    private fun generated(): JsonNode {
        val result = mvc.get().uri("/v3/api-docs").exchange()
        assertThat(result).hasStatus(HttpStatus.OK)
        return mapper.readTree(result.response.contentAsString)
    }

    /** Key-sorted, LF-only rendering so the committed file is identical on every OS. */
    private fun render(node: JsonNode): String =
        mapper.writeValueAsString(mapper.treeToValue(node, Map::class.java)).replace("\r\n", "\n") + "\n"

    @Test
    fun `committed contract matches the running application`() {
        val spec = generated()
        assertThat(spec.get("openapi").asString()).startsWith("3.1")

        if (System.getProperty("contracts.update") == "true") {
            Files.createDirectories(contractFile.parent)
            Files.writeString(contractFile, render(spec))
            return
        }

        assertThat(contractFile)
            .describedAs("contracts/openapi.json is missing: run ./gradlew :backend:updateContract")
            .exists()
        assertThat(render(spec))
            .describedAs("API changed without updating the contract: run ./gradlew :backend:updateContract and commit the diff")
            .isEqualTo(Files.readString(contractFile).replace("\r\n", "\n"))
    }
}
