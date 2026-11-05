package com.memo.testsupport

import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

/** [IntegrationTest] with object storage (SeaweedFS) and the malware scanner (clamd with test signatures) configured. */
@Suppress("UtilityClassWithPublicConstructor")
abstract class FilesIntegrationTest : IntegrationTest() {
    companion object {
        val spoolDir: java.nio.file.Path =
            java.nio.file.Files
                .createTempDirectory("memo-spool-test-")

        @JvmStatic
        @DynamicPropertySource
        fun filesProperties(registry: DynamicPropertyRegistry) {
            registry.add("memo.storage.endpoint") { TestObjectStorage.endpoint.toString() }
            registry.add("memo.storage.access-key") { TestObjectStorage.appKey }
            registry.add("memo.storage.secret-key") { TestObjectStorage.appSecret }
            TestMalwareScanner.properties().forEach { (key, value) -> registry.add(key) { value } }
            // Test JVMs share java.io.tmpdir: each gets its own spool, so one start-up sweep never removes another's uploads.
            registry.add("memo.files.spool-dir") { spoolDir.toString() }
        }
    }
}
