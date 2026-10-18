package com.memo

import org.springframework.boot.SpringApplication
import org.springframework.boot.WebApplicationType
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication
import kotlin.system.exitProcess

@SpringBootApplication
@ConfigurationPropertiesScan
class MemoApplication

fun main(args: Array<String>) {
    if (isMigrateMode(args)) exitProcess(runMigrations(args))
    runApplication<MemoApplication>(*args)
}

/** `--memo.mode=migrate` or `MEMO_MODE=migrate` (ADR-0016). */
internal fun isMigrateMode(
    args: Array<String>,
    env: Map<String, String> = System.getenv(),
): Boolean = args.contains("--memo.mode=migrate") || env["MEMO_MODE"] == "migrate"

/**
 * Migration job: starts the application without a web server so Spring Modulith runs the per-module Flyway migrations
 * as the migrator role, then shuts down and returns the exit code. Deployed app instances run with Flyway disabled and
 * only the application role's credentials (ADR-0016).
 */
internal fun runMigrations(args: Array<String>): Int {
    val context =
        SpringApplicationBuilder(MemoApplication::class.java)
            .web(WebApplicationType.NONE)
            // Command-line arguments: highest precedence, so the environment cannot re-enable seeding or disable Flyway.
            .run(*args, "--memo.demo.seed=false", "--spring.flyway.enabled=true", "--db-scheduler.enabled=false")
    return SpringApplication.exit(context)
}
