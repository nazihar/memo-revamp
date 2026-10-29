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
    // The plugins pull Jackson in transitively; hold build tooling to the same patched versions as the app.
    implementation(platform(libs.security.jackson2.bom))
    implementation(platform(libs.security.jackson3.bom))
    constraints {
        implementation(libs.security.guava) { because("CVE-2026-102554 (Guava < 33.7.2); pulled in by the build plugins") }
    }
    // Exposes the `libs` catalog to precompiled script plugins.
    implementation(files(libs.javaClass.superclass.protectionDomain.codeSource.location))
}

// Build tooling is pinned transitively too (ADR-0013).
dependencyLocking {
    lockAllConfigurations()
}
