// ktlint (formatting/style) + detekt (static analysis) for every Kotlin project.
plugins {
    id("org.jlleitschuh.gradle.ktlint")
    id("dev.detekt")
}

val ktlintVersion: String = libs.versions.ktlint.asProvider().get()

ktlint {
    version = ktlintVersion
    filter {
        exclude { it.file.path.contains("/build/") || it.file.path.contains("\\build\\") }
    }
}

detekt {
    buildUponDefaultConfig = true
    parallel = true
    config.setFrom(rootProject.layout.projectDirectory.file("config/detekt/detekt.yml"))
}

// Pin the full (transitive) dependency graph: AGENTS.md §4 "all versions pinned" (ADR-0013).
// Refresh after changing a dependency: ./gradlew resolveAll --write-locks
dependencyLocking {
    lockAllConfigurations()
}

tasks.register("resolveAll") {
    description = "Resolves every resolvable configuration (use with --write-locks to refresh gradle.lockfile)."
    group = "dependency locking"
    notCompatibleWithConfigurationCache("resolves configurations at execution time")
    doLast {
        project.configurations
            .filter { it.isCanBeResolved }
            .forEach { it.resolve() }
    }
}
