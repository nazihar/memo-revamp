package com.memo

import com.memo.identity.operator.OperatorBootstrap
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
    if (args.firstOrNull() == OPERATOR_INVITE) exitProcess(runOperatorInvite(args.drop(1)))
    runApplication<MemoApplication>(*args)
}

/** `operator-invite <email> [display name]`: the bootstrap command for platform operators (ADR-0031). */
internal const val OPERATOR_INVITE = "operator-invite"

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
            .run(
                *args,
                "--memo.demo.seed=false",
                "--spring.flyway.enabled=true",
                // A migration run starts no jobs and no cache listener (ADR-0018, ADR-0020).
                "--db-scheduler.enabled=false",
                "--memo.cache.listener.enabled=false",
            )
    return SpringApplication.exit(context)
}

/**
 * Creates or resets a platform operator and prints the one-time enrolment link to standard output, never to the log
 * (ADR-0031). Runs without a web server, jobs or seeding, with the deployment's normal configuration.
 */
internal fun runOperatorInvite(args: List<String>): Int {
    val email = args.firstOrNull()
    if (email == null || args.size > 2) {
        System.err.println("usage: $OPERATOR_INVITE <email> [display name]")
        return 2
    }
    val context =
        SpringApplicationBuilder(MemoApplication::class.java)
            .web(WebApplicationType.NONE)
            .run("--memo.demo.seed=false", "--db-scheduler.enabled=false", "--memo.cache.listener.enabled=false")
    val link = context.use { it.getBean(OperatorBootstrap::class.java).invite(email, args.getOrNull(1) ?: email.substringBefore('@')) }
    println("Enrolment link for $email (valid 24 hours, works once):")
    println(link)
    return 0
}
