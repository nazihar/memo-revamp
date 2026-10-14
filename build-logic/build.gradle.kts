plugins {
    `kotlin-dsl`
}

dependencies {
    implementation(libs.kotlin.gradle.plugin)
    implementation(libs.kotlin.allopen.plugin)
    implementation(libs.spring.boot.gradle.plugin)
    implementation(libs.ktlint.gradle.plugin)
    implementation(libs.detekt.gradle.plugin)
    implementation(libs.cyclonedx.gradle.plugin)
    implementation(libs.spotbugs.gradle.plugin)
    // Exposes the `libs` catalog to precompiled script plugins.
    implementation(files(libs.javaClass.superclass.protectionDomain.codeSource.location))
}

// Build tooling is pinned transitively too (ADR-0013).
dependencyLocking {
    lockAllConfigurations()
}
