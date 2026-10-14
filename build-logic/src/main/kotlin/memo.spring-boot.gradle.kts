plugins {
    id("memo.kotlin-jvm")
    id("org.jetbrains.kotlin.plugin.spring")
    id("org.springframework.boot")
}

dependencies {
    "implementation"(platform(libs.spring.boot.dependencies))
    "implementation"(platform(libs.spring.modulith.bom))
    "testImplementation"(platform(libs.spring.boot.dependencies))
    "testImplementation"(platform(libs.spring.modulith.bom))
    "developmentOnly"(platform(libs.spring.boot.dependencies))

    // Security overrides (see gradle/libs.versions.toml): Gradle picks the highest version, so these lift the patched
    // releases above Boot's managed ones. The Trivy gate fails again if they are removed too early.
    "implementation"(platform(libs.security.jackson2.bom))
    "implementation"(platform(libs.security.jackson3.bom))
    constraints {
        "implementation"(libs.security.tomcat.embed.core) { because("CVE-2026-65182, CVE-2026-65905, CVE-2026-68525") }
    }
}

// SpotBugs + FindSecBugs on production bytecode (ADR-0013). Kotlin-specific false positives are excluded centrally.
apply(plugin = "com.github.spotbugs")

configure<com.github.spotbugs.snom.SpotBugsExtension> {
    toolVersion.set(libs.versions.spotbugs.asProvider().get())
    effort.set(com.github.spotbugs.snom.Effort.MAX)
    reportLevel.set(com.github.spotbugs.snom.Confidence.MEDIUM)
    excludeFilter.set(rootProject.layout.projectDirectory.file("config/spotbugs/exclude.xml"))
}

dependencies {
    "spotbugsPlugins"(libs.findsecbugs)
}

tasks.named("spotbugsTest") { enabled = false }

tasks.withType<com.github.spotbugs.snom.SpotBugsTask>().configureEach {
    reports.create("html") { required.set(true) }
}
