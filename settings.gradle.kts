pluginManagement {
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
    }
}

rootProject.name = "memo-revamp"

include(":backend", ":shared", ":perf")

// Optional out-of-tree build output (e.g. to coexist with an IDE that builds the same tree with another Gradle version).
providers.environmentVariable("MEMO_BUILD_ROOT").orNull?.let { root ->
    gradle.beforeProject {
        // Keep a trailing `/build` so tools that recognise build output by path (ArchUnit/Modulith "exclude tests") still work.
        layout.buildDirectory.set(file("$root/${rootProject.name}${path.replace(':', '/')}/build"))
    }
}
