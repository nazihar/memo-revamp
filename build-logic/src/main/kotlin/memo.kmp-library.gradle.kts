import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Kotlin Multiplatform library. JVM target only until Phase 6a adds iOS/Android targets (Phase 0 Q4).
plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("memo.quality")
}

kotlin {
    jvmToolchain(libs.versions.java.get().toInt())
    jvm {
        compilerOptions {
            jvmTarget = JvmTarget.fromTarget(libs.versions.java.get())
        }
    }
    compilerOptions {
        allWarningsAsErrors = true
    }
    sourceSets {
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
