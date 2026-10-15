package com.memo.perf

import io.gatling.javaapi.core.CoreDsl.global
import io.gatling.javaapi.core.CoreDsl.rampUsers
import io.gatling.javaapi.core.CoreDsl.scenario
import io.gatling.javaapi.core.Simulation
import io.gatling.javaapi.http.HttpDsl.http
import io.gatling.javaapi.http.HttpDsl.status

/**
 * Nightly smoke (TESTING_STRATEGY §7): small load on the public health endpoint, catching gross regressions.
 * Budget (E01 design §10, nfr.md): p95 < 300 ms, < 1 % errors. Later phases add one simulation per critical journey.
 */
class HealthSmokeSimulation : Simulation() {
    private val baseUrl = System.getProperty("baseUrl", "http://localhost:8080")
    private val users = Integer.getInteger("users", USERS)

    private val protocol =
        http
            .baseUrl(baseUrl)
            .acceptHeader("application/json")
            .userAgentHeader("memo-perf-smoke")

    private val health =
        scenario("health")
            .exec(http("GET /api/v1/health").get("/api/v1/health").check(status().shouldBe(OK)))

    init {
        setUp(health.injectOpen(rampUsers(users).during(RAMP_SECONDS)))
            .protocols(protocol)
            .assertions(
                global().responseTime().percentile(P95).lt(P95_BUDGET_MS),
                global().failedRequests().percent().lt(MAX_ERROR_PERCENT),
            )
    }

    private companion object {
        const val USERS = 20
        const val RAMP_SECONDS = 30L
        const val OK = 200
        const val P95 = 95.0
        const val P95_BUDGET_MS = 300
        const val MAX_ERROR_PERCENT = 1.0
    }
}
