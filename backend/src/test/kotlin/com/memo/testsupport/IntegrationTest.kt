package com.memo.testsupport

import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

/**
 * Full application against a real PostgreSQL (Testcontainers), connected as the non-owner application role.
 * Optional integrations (storage, ONLYOFFICE, ClamAV, SMTP) are not configured, so they report DISABLED unless a test
 * registers them.
 *
 * (Suppression: an abstract base with only a companion is not a utility class.)
 */
@Suppress("UtilityClassWithPublicConstructor")
@SpringBootTest
@AutoConfigureMockMvc
@ExtendWith(TestActorExtension::class)
abstract class IntegrationTest {
    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun databaseProperties(registry: DynamicPropertyRegistry) {
            TestDatabase.properties().forEach { (key, value) -> registry.add(key) { value } }
        }
    }
}
