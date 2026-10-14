plugins {
    id("memo.spring-boot")
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
    implementation(libs.aws.s3) {
        // We use the JDK-based client only; keep Netty and Apache HTTP off the classpath.
        exclude(group = "software.amazon.awssdk", module = "netty-nio-client")
        exclude(group = "software.amazon.awssdk", module = "apache-client")
    }
    implementation(libs.aws.url.connection.client)
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
}

// Regenerates contracts/openapi.json from the running application (see OpenApiContractTest).
tasks.register<Test>("updateContract") {
    description = "Writes the generated OpenAPI spec to /contracts (the drift check runs in the normal test task)."
    group = "contracts"
    val testSourceSet = sourceSets.test.get()
    testClassesDirs = testSourceSet.output.classesDirs
    classpath = testSourceSet.runtimeClasspath
    useJUnitPlatform()
    filter { includeTestsMatching("*OpenApiContractTest") }
    systemProperty("contracts.update", "true")
    outputs.upToDateWhen { false }
}

tasks.withType<Test>().configureEach {
    val repoRoot = rootProject.layout.projectDirectory
    // Tests read the committed contract and the same deploy scripts (DB roles, bucket bootstrap) that real installs use.
    systemProperty("repo.root", repoRoot.asFile.absolutePath)
    inputs.dir(repoRoot.dir("deploy/db")).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.dir(repoRoot.dir("deploy/seaweedfs")).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files(repoRoot.file("contracts/openapi.json")).withPathSensitivity(PathSensitivity.RELATIVE).optional()
}
