package com.memo.perf

import io.gatling.javaapi.core.CoreDsl.StringBody
import io.gatling.javaapi.core.CoreDsl.details
import io.gatling.javaapi.core.CoreDsl.exec
import io.gatling.javaapi.core.CoreDsl.global
import io.gatling.javaapi.core.CoreDsl.rampUsers
import io.gatling.javaapi.core.CoreDsl.scenario
import io.gatling.javaapi.core.Simulation
import io.gatling.javaapi.http.HttpDsl.CookieKey
import io.gatling.javaapi.http.HttpDsl.getCookieValue
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

    private val inbox =
        scenario("inbox")
            .feed(approvers)
            .exec(http("GET /auth/csrf").get("/api/v1/auth/csrf").check(status().shouldBe(OK)))
            .exec(getCookieValue(CookieKey("XSRF-TOKEN").saveAs("xsrf")))
            .exec(
                http("POST /auth/login")
                    .post("/api/v1/auth/login")
                    .header("X-XSRF-TOKEN", "#{xsrf}")
                    .body(StringBody { session -> """{"email":"${session.getString("email")}","password":"$password"}""" })
                    .check(status().shouldBe(OK)),
            ).repeat(reads)
            .on(
                exec(http(INBOX).get("/api/v1/inbox/tasks?limit=25").check(status().shouldBe(OK)))
                    .pause(Duration.ofMillis(PAUSE_MS)),
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
        const val APPROVERS = 50
        const val READS = 20
        const val PAUSE_MS = 500L
        const val RAMP_SECONDS = 30L
        const val OK = 200
        const val P95 = 95.0
        const val P95_BUDGET_MS = 300
        const val MAX_ERROR_PERCENT = 1.0
    }
}
