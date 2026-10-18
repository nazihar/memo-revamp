import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("memo.quality")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(libs.versions.java.get())
    }
}

kotlin {
    jvmToolchain(libs.versions.java.get().toInt())
    compilerOptions {
        jvmTarget = JvmTarget.fromTarget(libs.versions.java.get())
        allWarningsAsErrors = true
        // JSpecify nullness annotations (Spring 7) are enforced as errors.
        freeCompilerArgs.add("-Xjsr305=strict")
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    // Nightly long property runs: -PjqwikTries=1000 (TESTING_STRATEGY §2).
    providers.gradleProperty("jqwikTries").orNull?.let { systemProperty("jqwik.tries.default", it) }
    // Nightly audit verification throughput on a synthetic chain (E02 design §10): -PauditVolume=200000.
    providers.gradleProperty("auditVolume").orNull?.let { systemProperty("memo.test.audit-volume", it) }
    testLogging {
        events("failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
