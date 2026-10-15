import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Performance tests (TESTING_STRATEGY §7): Gatling simulations in Kotlin. Not part of `./gradlew build`.
//   ./gradlew :perf:gatlingRun --simulation com.memo.perf.HealthSmokeSimulation -DbaseUrl=https://dev.example
plugins {
    id("memo.kotlin-jvm")
    alias(libs.plugins.gatling)
}

// Gatling forks the simulation with Gradle's own JVM (no toolchain support), so simulations target Java 17 — the
// minimum JVM Gradle runs on — instead of the product's Java 25.
java.toolchain.languageVersion = JavaLanguageVersion.of(17)
kotlin {
    jvmToolchain(17)
    compilerOptions.jvmTarget = JvmTarget.JVM_17
}
