plugins {
    id("memo.spring-boot")
}

// jOOQ classes generated from the migrated schema and committed (ADR-0007); refreshed by `updateJooq`.
val jooqGeneratedDir = layout.projectDirectory.dir("src/main/jooq")

kotlin {
    sourceSets.named("main") {
        kotlin.srcDir(jooqGeneratedDir)
    }
}

ktlint {
    filter {
        exclude {
            it.file.path
                .replace('\\', '/')
                .contains("/src/main/jooq/")
        }
    }
}

tasks.withType<dev.detekt.gradle.Detekt>().configureEach {
    exclude {
        it.file.path
            .replace('\\', '/')
            .contains("/src/main/jooq/")
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(platform(libs.aws.sdk.bom))

    implementation(libs.spring.boot.starter.webmvc)
    implementation(libs.spring.boot.starter.security)
    implementation(libs.spring.boot.starter.jooq)
    implementation(libs.spring.boot.starter.flyway)
    implementation(libs.spring.modulith.starter.core)
    implementation(libs.springdoc.webmvc.api)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.kotlin.reflect)
    implementation(libs.jug)
    implementation(libs.aws.s3) {
        // We use the JDK-based client only; keep Netty and Apache HTTP off the classpath.
        exclude(group = "software.amazon.awssdk", module = "netty-nio-client")
        exclude(group = "software.amazon.awssdk", module = "apache-client")
        exclude(group = "software.amazon.awssdk", module = "apache5-client")
    }
    implementation(libs.aws.url.connection.client)
    runtimeOnly(libs.spring.modulith.runtime)
    runtimeOnly(libs.flyway.database.postgresql)
    runtimeOnly(libs.postgresql)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.boot.starter.webmvc.test)
    testImplementation(libs.spring.boot.starter.security.test)
    testImplementation(libs.spring.boot.testcontainers)
    testImplementation(libs.spring.modulith.starter.test)
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.mockk)
    testImplementation(libs.jqwik)
    testImplementation(libs.jooq.codegen)
    testImplementation(libs.jooq.meta)
}

/** A test task that runs one generator test in "update" mode (writes instead of checking for drift). */
fun registerUpdateTask(
    name: String,
    testPattern: String,
    flag: String,
    what: String,
) = tasks.register<Test>(name) {
    description = "Regenerates $what (the drift check runs in the normal test task)."
    group = "generation"
    val testSourceSet = sourceSets.test.get()
    testClassesDirs = testSourceSet.output.classesDirs
    classpath = testSourceSet.runtimeClasspath
    useJUnitPlatform()
    filter { includeTestsMatching(testPattern) }
    systemProperty(flag, "true")
    outputs.upToDateWhen { false }
}

registerUpdateTask("updateContract", "*OpenApiContractTest", "contracts.update", "/contracts/openapi.json")
registerUpdateTask("updateJooq", "*JooqCodegenTest", "jooq.update", "backend/src/main/jooq from the migrated schema")

tasks.withType<Test>().configureEach {
    val repoRoot = rootProject.layout.projectDirectory
    // Tests read the committed contract and the same deploy scripts (DB roles, bucket bootstrap) that real installs use.
    systemProperty("repo.root", repoRoot.asFile.absolutePath)
    inputs.dir(repoRoot.dir("deploy/db")).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.dir(repoRoot.dir("deploy/seaweedfs")).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files(repoRoot.file("contracts/openapi.json")).withPathSensitivity(PathSensitivity.RELATIVE).optional()
}
