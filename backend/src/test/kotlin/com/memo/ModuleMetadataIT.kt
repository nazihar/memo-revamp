package com.memo

import com.memo.testsupport.IntegrationTest
import com.memo.testsupport.repoRoot
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.modulith.core.ApplicationModuleIdentifiers
import org.springframework.modulith.core.ApplicationModules
import org.springframework.modulith.core.util.ApplicationModulesExporter
import tools.jackson.databind.SerializationFeature
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files

/**
 * The application's modules, precomputed in `META-INF/spring-modulith/application-modules.json` (what Spring Modulith's
 * AOT step would write). Without that file, every start builds the full module model with an ArchUnit import of the
 * whole application, about 540 MiB of heap, only to learn the module names and their order (the order the migrate job
 * runs their Flyway migrations in, ADR-0007).
 *  - normal run: the committed file must equal the export of the modules as they are;
 *  - `./gradlew :backend:updateModuleMetadata`: writes the export.
 */
class ModuleMetadataIT : IntegrationTest() {
    @Autowired
    lateinit var identifiers: ApplicationModuleIdentifiers

    @Autowired
    lateinit var context: ConfigurableApplicationContext

    private val metadataFile =
        repoRoot.resolve("backend/src/main/resources/META-INF/spring-modulith/application-modules.json")

    // As the runtime detects them: the main class only (test classes are not on a deployed classpath).
    private val modules by lazy { ApplicationModules.of(MemoApplication::class.java) }

    private val mapper: JsonMapper = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build()

    /**
     * The export with a stable layout: its modules in their order (the runtime reads that order), everything inside a
     * module sorted (the exporter writes a dependency's fields in no fixed order), LF line ends.
     */
    private fun export(): String {
        val raw = mapper.readValue(ApplicationModulesExporter(modules).toJson(), LinkedHashMap::class.java)
        val stable = raw.entries.associateTo(LinkedHashMap()) { (name, module) -> name to sorted(module) }
        return mapper.writeValueAsString(stable).replace("\r\n", "\n") + "\n"
    }

    private fun sorted(value: Any?): Any? =
        when (value) {
            is Map<*, *> -> value.entries.associate { (k, v) -> k.toString() to sorted(v) }.toSortedMap()
            is List<*> -> value.map(::sorted).sortedBy { mapper.writeValueAsString(it) }
            else -> value
        }

    @Test
    fun `committed module metadata matches the modules`() {
        val json = export()
        if (System.getProperty("modules.update") == "true") {
            Files.createDirectories(metadataFile.parent)
            Files.writeString(metadataFile, json)
            return
        }
        assertThat(Files.readString(metadataFile).replace("\r\n", "\n"))
            .describedAs("Modules changed without updating their metadata: run ./gradlew :backend:updateModuleMetadata and commit the diff")
            .isEqualTo(json)
    }

    @Test
    fun `the runtime reads the modules from the metadata, in their order, without building the model`() {
        assertThat(identifiers.stream().map { it.toString() }.toList())
            .containsExactlyElementsOf(modules.stream().map { it.identifier.toString() }.toList())
        // The bootstrap that imports the application with ArchUnit is lazy: nothing asked for it.
        assertThat(context.beanFactory.containsSingleton("applicationModulesInitializer")).isFalse()
    }
}
