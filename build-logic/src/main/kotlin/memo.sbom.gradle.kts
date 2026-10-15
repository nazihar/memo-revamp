import org.cyclonedx.gradle.CyclonedxDirectTask
import org.cyclonedx.model.Component

// CycloneDX SBOM (ADR-0013). Applied on the root project: `./gradlew cyclonedxBom` aggregates every subproject's
// *runtime* graph into build/reports/cyclonedx/bom.json. Test and tooling configurations are excluded on purpose —
// the SBOM describes what we ship.
plugins {
    id("org.cyclonedx.bom")
}

allprojects {
    tasks.withType<CyclonedxDirectTask>().configureEach {
        includeConfigs.set(listOf("runtimeClasspath", "jvmRuntimeClasspath"))
        skipConfigs.set(listOf("(?i).*test.*", "(?i).*detekt.*", "(?i).*ktlint.*", "(?i).*spotbugs.*"))
        projectType.set(Component.Type.APPLICATION)
        xmlOutput.unsetConvention()
    }
}

tasks.withType<org.cyclonedx.gradle.CyclonedxAggregateTask>().configureEach {
    projectType.set(Component.Type.APPLICATION)
    xmlOutput.unsetConvention()
}
