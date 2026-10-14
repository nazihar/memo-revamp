dependencyResolutionManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "build-logic"

// See the root settings.gradle.kts.
providers.environmentVariable("MEMO_BUILD_ROOT").orNull?.let { root ->
    gradle.beforeProject { layout.buildDirectory.set(file("$root/build-logic/build")) }
}
