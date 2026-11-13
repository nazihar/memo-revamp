package com.memo.perf

import io.gatling.javaapi.core.CoreDsl.StringBody
import io.gatling.javaapi.core.CoreDsl.details
import io.gatling.javaapi.core.CoreDsl.exec
import io.gatling.javaapi.core.CoreDsl.global
import io.gatling.javaapi.core.CoreDsl.jsonPath
import io.gatling.javaapi.core.CoreDsl.rampUsers
import io.gatling.javaapi.core.CoreDsl.scenario
import io.gatling.javaapi.core.Simulation
import io.gatling.javaapi.http.HttpDsl.headerRegex
import io.gatling.javaapi.http.HttpDsl.http
import io.gatling.javaapi.http.HttpDsl.status
import java.time.Duration

/**
 * Weekly load (phase 2 overview E05, NFR P1): the approvers of synthetic volume V2 (10,000 memos in review with their
 * timers, `memo.volume.v2`) sign in and read their inboxes. Budget: inbox p95 < 300 ms, < 1 % errors.
 *
 * `-DbaseUrl` (the on-prem stack), `-DtenantHost` (`volume.localhost` by default: tenants are told apart by host) and
 * the volume users' password in `MEMO_DEMO_PASSWORD` (never on the command line).
 */
class InboxLoadSimulation : Simulation() {
    private val baseUrl = System.getProperty("baseUrl", "http://localhost:8080")
    private val tenantHost = System.getProperty("tenantHost", "volume.localhost")
    private val users = Integer.getInteger("users", APPROVERS)
    private val reads = Integer.getInteger("reads", READS)
    private val p95BudgetMs = Integer.getInteger("p95BudgetMs", P95_BUDGET_MS)
    private val password = requireNotNull(System.getenv("MEMO_DEMO_PASSWORD")) { "set MEMO_DEMO_PASSWORD" }

    private val protocol =
        http
            .baseUrl(baseUrl)
            .header("Host", tenantHost)
            .acceptHeader("application/json")
            .contentTypeHeader("application/json")
            .userAgentHeader("memo-perf-inbox")

    private val approvers = (1..APPROVERS).map { mapOf<String, Any>("email" to "approver$it@volume.example") }.iterator()

    /*
     * The cookies are Secure, and Gatling's cookie jar sends Secure cookies over plain HTTP only to "localhost" names,
     * not to 127.0.0.1 (the stack listens on IPv4 only). So the scenario carries them itself, from Set-Cookie.
     */
    private val inbox =
        scenario("inbox")
            .feed(approvers)
            .exec(
                http("GET /auth/csrf")
                    .get("/api/v1/auth/csrf")
                    .check(status().shouldBe(NO_CONTENT), headerRegex("Set-Cookie", "XSRF-TOKEN=([^;]+)").saveAs("xsrf")),
            ).exec(
                http("POST /auth/login")
                    .post("/api/v1/auth/login")
                    .header("Cookie", "XSRF-TOKEN=#{xsrf}")
                    .header("X-XSRF-TOKEN", "#{xsrf}")
                    .body(StringBody { session -> """{"email":"${session.getString("email")}","password":"$password"}""" })
                    .check(status().shouldBe(OK), headerRegex("Set-Cookie", "$SESSION_COOKIE=([^;]+)").saveAs("session")),
            ).repeat(reads)
            .on(
                exec(
                    http(INBOX)
                        .get("/api/v1/inbox/tasks?limit=25")
                        .header("Cookie", "$SESSION_COOKIE=#{session}")
                        // A full page of real work: each approver holds 200 decisions, each with its due time.
                        .check(status().shouldBe(OK), jsonPath("\$.items[24].dueAt").exists()),
                ).pause(Duration.ofMillis(PAUSE_MS)),
            )

    init {
        setUp(inbox.injectOpen(rampUsers(users).during(RAMP_SECONDS)))
            .protocols(protocol)
            .assertions(
                details(INBOX).responseTime().percentile(P95).lt(p95BudgetMs),
                global().failedRequests().percent().lt(MAX_ERROR_PERCENT),
            )
    }

    private companion object {
        const val INBOX = "GET /inbox/tasks"
        const val SESSION_COOKIE = "MEMO_SESSION"
        const val APPROVERS = 50

        // Sign-ins are limited to 20 a minute per address (LOGIN_PER_IP) and every user comes from this one: the ramp
        // stays under it, and each approver keeps reading for about three minutes, so all of them overlap at the end.
        const val READS = 360
        const val PAUSE_MS = 500L
        const val RAMP_SECONDS = 165L
        const val OK = 200
        const val NO_CONTENT = 204
        const val P95 = 95.0
        const val P95_BUDGET_MS = 300
        const val MAX_ERROR_PERCENT = 1.0
    }
}
