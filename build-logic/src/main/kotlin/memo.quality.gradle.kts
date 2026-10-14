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
