plugins {
    id("memo.spring-boot")
    alias(libs.plugins.pitest)
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
    implementation(libs.spring.boot.starter.session.jdbc)
    implementation(libs.spring.boot.starter.mail)
    // SMTP reply codes (permanent vs transient failures, ADR-0030); version from the Boot BOM.
    implementation(libs.angus.mail)
    implementation(libs.spring.boot.starter.jooq)
    implementation(libs.spring.boot.starter.flyway)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.opentelemetry)
    implementation(libs.spring.modulith.starter.core)
    implementation(libs.spring.modulith.starter.jdbc)
    implementation(libs.db.scheduler.spring.boot.starter)
    implementation(libs.caffeine)
    // Argon2id password hashing (ADR-0023).
    implementation(libs.bcprov)
    implementation(libs.bucket4j.postgresql)
    implementation(libs.zxing.core)
    // Envelope encryption of files: streaming AEAD with seekable decryption (ADR-0028).
    implementation(libs.tink)
    // Content detection by magic only, no parsers (ADR-0029); zip central directories; PDF inspection with limits.
    implementation(libs.tika.core)
    implementation(libs.commons.compress)
    implementation(libs.pdfbox)
    // Final records (ADR-0053): PDF/A metadata, and veraPDF to validate each record in the job and in tests.
    implementation(libs.xmpbox)
    // stax-utils (BSD-4-Clause, not on our allow-list) only writes veraPDF's XML reports, which we never produce.
    implementation(libs.verapdf.validation) {
        exclude(group = "net.java.dev.stax-utils")
    }
    // .docx templates (ADR-0048): poi-tl with its default tag grammar only, on Apache POI. Batik (SVG pictures) is unused.
    implementation(libs.poi.tl) {
        exclude(group = "org.apache.xmlgraphics")
    }
    implementation(libs.poi.ooxml)
    // Legacy import metadata as CSV (ADR-0055): the strict RFC 4180 profile, UTF-8 only.
    implementation(libs.commons.csv)
    // Meeting invitations (ADR-0063): iCalendar written and validated by ical4j, with its tzurl.org updater switched off.
    implementation(libs.ical4j)
    // Workflow definitions: JSON Schema validation (ADR-0038) and CEL conditions without macros (ADR-0039).
    implementation(libs.json.schema.validator)
    implementation(libs.cel)
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
    // Compile scope for PGConnection (LISTEN/NOTIFY, ADR-0020).
    implementation(libs.postgresql)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.boot.starter.webmvc.test)
    testImplementation(libs.spring.boot.starter.security.test)
    testImplementation(libs.spring.boot.testcontainers)
    testImplementation(libs.spring.modulith.starter.test)
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.mockk)
    testImplementation(libs.jqwik)
    // Fuzz targets (TESTING_STRATEGY §3.6): regression inputs in the PR stage, time-boxed fuzzing in the nightly.
    testImplementation(libs.jazzer.junit)
    // A second, independent iCalendar parser for the invitation tests only (ADR-0063); never on the runtime classpath.
    testImplementation(libs.biweekly)
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
registerUpdateTask(
    "updateModuleMetadata",
    "*ModuleMetadataIT",
    "modules.update",
    "the precomputed Spring Modulith module metadata",
)
registerUpdateTask("updateJooq", "*JooqCodegenTest", "jooq.update", "backend/src/main/jooq from the migrated schema")
registerUpdateTask(
    "updateMinutesTemplates",
    "*MinutesDefaultTemplateTest",
    "minutes.templates.update",
    "the built-in minutes templates (backend/src/main/resources/forum)",
)
registerUpdateTask(
    "updateBreachedPasswords",
    "*BreachedPasswordFilterTest",
    "breached.update",
    "the common-password Bloom filter",
).configure {
    providers.gradleProperty("breached.source").orNull?.let { systemProperty("breached.source", it) }
}

tasks.withType<Test>().configureEach {
    // Gradle's default of 512 MiB became too tight with E04's suite: master CI ran out of heap on a tree that had passed.
    // 1 GiB became too tight in Phase 3 E02 (CI ran out of heap in ArchUnit's import, with the cached Spring contexts):
    // the runner has 16 GB, and each test context holds its own pools and caches. 2 GiB became too tight in Phase 4 E06
    // (about 3,800 tests; CI ran out of heap on #218), so 3 GiB, with a heap dump kept for the next time.
    maxHeapSize = "3g"
    jvmArgs("-XX:+HeapDumpOnOutOfMemoryError", "-XX:HeapDumpPath=build/test-heap")
    // Tests run in UTC on every machine, as on CI. On a developer machine in Asia/Dhaka, the JVM's zone also set the
    // database session's zone (pgjdbc), so a fixture's `current_date` or `LocalDate.now()` was a day ahead of a test
    // tenant's today (UTC without `default.timezone`) from 18:00 to 24:00 UTC (ActionReportIT, DecisionRegisterIT, #247).
    // Tests about a zone set it explicitly (a tenant's `default.timezone`, a pinned clock); none rely on the machine's.
    systemProperty("user.timezone", "UTC")
    val repoRoot = rootProject.layout.projectDirectory
    // Tests read the committed contract and the same deploy scripts (DB roles, bucket bootstrap) that real installs use.
    systemProperty("repo.root", repoRoot.asFile.absolutePath)
    // The stall watchdog's quiet period (testsupport/StallWatchdog), e.g. -Pmemo.test.stall-minutes=1 to try it.
    providers.gradleProperty("memo.test.stall-minutes").orNull?.let { systemProperty("memo.test.stall-minutes", it) }
    inputs.dir(repoRoot.dir("deploy/db")).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.dir(repoRoot.dir("deploy/seaweedfs")).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files(repoRoot.file("contracts/openapi.json")).withPathSensitivity(PathSensitivity.RELATIVE).optional()
}

// Mutation testing (TESTING_STRATEGY §4): nightly, on the pure cores of critical modules. Starts with the workflow engine
// (Phase 2 E01); authz, audit and numbering join as their logic moves into pure packages.
pitest {
    pitestVersion = libs.versions.pitest.core
    junit5PluginVersion = libs.versions.pitest.junit5
    // The workflow engine (Phase 2), the numbering formats (Phase 3 E01, ADR-0044) and the quorum (ADR-0057, ADR-0064: the
    // evaluator, the presence builder and a snapshot's canonical inputs, Phase 4 E05 T5): pure code where a surviving mutant
    // is a bug.
    targetClasses.set(
        listOf(
            "com.memo.workflow.engine.*",
            "com.memo.memo.internal.NumberFormats*",
            "com.memo.forum.Quorum",
            "com.memo.forum.Quorum$*",
            "com.memo.meeting.internal.quorum.PresenceBuilder*",
            "com.memo.meeting.QuorumSnapshotInputs*",
            // Resolution numbering and the way back to the memo (ADR-0065, Phase 4 E06 T4): the order numbers are given in, the
            // register check and the release mapping.
            "com.memo.minutes.internal.ResolutionOrder*",
            "com.memo.minutes.internal.ResolutionChecks*",
            "com.memo.minutes.internal.ReleaseMapping*",
            "com.memo.minutes.internal.ReleaseCommand*",
            // The analytics measures' definitions (ADR-0070, Phase 4 E08 T4): one definition for screen, export and tests.
            "com.memo.analytics.measures.*",
        ),
    )
    targetTests.set(
        listOf(
            "com.memo.workflow.engine.*",
            "com.memo.memo.internal.NumberFormatTest",
            "com.memo.forum.QuorumTest",
            "com.memo.forum.QuorumProperties",
            "com.memo.meeting.internal.quorum.PresenceBuilderProperties",
            "com.memo.meeting.internal.quorum.ItemQuorumProperties",
            "com.memo.minutes.internal.ReleaseMappingTest",
            "com.memo.minutes.internal.ResolutionChecksTest",
            "com.memo.minutes.internal.AgendaOrderProperties",
            "com.memo.analytics.measures.*",
        ),
    )
    threads.set(4)
    outputFormats.set(listOf("HTML", "XML"))
    timestampedReports.set(false)
    mutationThreshold.set(70)
}
