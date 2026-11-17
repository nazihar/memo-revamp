package com.memo.perf

import io.gatling.javaapi.core.ChainBuilder
import io.gatling.javaapi.core.CoreDsl.StringBody
import io.gatling.javaapi.core.CoreDsl.atOnceUsers
import io.gatling.javaapi.core.CoreDsl.details
import io.gatling.javaapi.core.CoreDsl.exec
import io.gatling.javaapi.core.CoreDsl.global
import io.gatling.javaapi.core.CoreDsl.jsonPath
import io.gatling.javaapi.core.CoreDsl.nothingFor
import io.gatling.javaapi.core.CoreDsl.rampUsers
import io.gatling.javaapi.core.CoreDsl.scenario
import io.gatling.javaapi.core.Simulation
import io.gatling.javaapi.http.HttpDsl.headerRegex
import io.gatling.javaapi.http.HttpDsl.http
import io.gatling.javaapi.http.HttpDsl.status
import java.time.Clock
import java.time.Duration

/**
 * Weekly load (Phase 3 E05 T8, test plan "Load"): on synthetic volume V2 (`memo.volume.v2`: 10,000 memos in review,
 * everyone seated in one division under a head), the approvers read their inbox by due time, search and open their
 * dashboard, while the division's head reads the team view, the unit dashboard and searches the whole division.
 *
 * Budgets (p95): search 300 ms for each reader profile (a participant; a unit reader, whose candidates are capped at
 * 2,000), inbox by due 150 ms, team view 300 ms, dashboard 800 ms; under 1 % errors. Search is POST with a JSON body
 * (the words never travel in a URL) and is limited to 30 a minute per person: each person searches about once in three
 * seconds.
 *
 * `-DbaseUrl`, `-DtenantHost` (`volume.localhost` by default), the volume users' password in `MEMO_DEMO_PASSWORD` and
 * the head's TOTP secret in `MEMO_DEMO_TOTP_SECRET` (both never on the command line), as for [InboxLoadSimulation].
 */
class SearchAndTeamLoadSimulation : Simulation() {
    private val baseUrl = System.getProperty("baseUrl", "http://localhost:8080")
    private val tenantHost = System.getProperty("tenantHost", "volume.localhost")
    private val users = Integer.getInteger("users", APPROVERS)
    private val rounds = Integer.getInteger("rounds", ROUNDS)
    private val password = requireNotNull(System.getenv("MEMO_DEMO_PASSWORD")) { "set MEMO_DEMO_PASSWORD" }
    private val clock = Clock.systemUTC()
    private val totpSecret = requireNotNull(System.getenv("MEMO_DEMO_TOTP_SECRET")) { "set MEMO_DEMO_TOTP_SECRET" }

    private val protocol =
        http
            .baseUrl(baseUrl)
            .header("Host", tenantHost)
            .acceptHeader("application/json")
            .contentTypeHeader("application/json")
            .userAgentHeader("memo-perf-search")

    private val approvers = (1..APPROVERS).map { mapOf<String, Any>("email" to "approver$it@volume.example") }.iterator()

    // The cookies are carried by hand from Set-Cookie, as in InboxLoadSimulation (Secure cookies over plain HTTP).
    private fun signIn(): ChainBuilder =
        exec(
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
        ).exec(
            // Signing in may rotate the CSRF token: searches (POST) take a fresh one.
            http("GET /auth/csrf (signed in)")
                .get("/api/v1/auth/csrf")
                .header("Cookie", "$SESSION_COOKIE=#{session}")
                .check(status().shouldBe(NO_CONTENT), headerRegex("Set-Cookie", "XSRF-TOKEN=([^;]+)").saveAs("xsrf")),
        )

    /** Every other round searches broadly (every memo matches); the others name one memo by its number. */
    private fun words(round: Int): String = if (round % 2 == 0) "volume memo" else "memo ${round * PRIME % MEMOS + 1}"

    private fun search(name: String) =
        http(name)
            .post("/api/v1/search")
            .header("Cookie", "$SESSION_COOKIE=#{session}; XSRF-TOKEN=#{xsrf}")
            .header("X-XSRF-TOKEN", "#{xsrf}")
            .body(StringBody { session -> """{"q":"${words(session.getInt("round"))}"}""" })
            .check(status().shouldBe(OK), jsonPath("\$.count").exists())

    private fun read(
        name: String,
        path: String,
    ) = http(name).get(path).header("Cookie", "$SESSION_COOKIE=#{session}")

    /** An approver: 200 decisions due, a participant in 200 memos. */
    private val approver =
        scenario("approvers")
            .feed(approvers)
            .exec(signIn())
            .repeat(rounds, "round")
            .on(
                exec(
                    read(INBOX_BY_DUE, "/api/v1/inbox/tasks?limit=25&sort=due")
                        .check(status().shouldBe(OK), jsonPath("\$.items[24].dueAt").exists()),
                ).exec(search(SEARCH_PARTICIPANT))
                    .exec(read(DASHBOARD_ME, "/api/v1/dashboards/approvals?scope=me").check(status().shouldBe(OK)))
                    .pause(Duration.ofMillis(PAUSE_MS)),
            )

    /** The division's head: the team view over all 10,000 tasks, the unit dashboard and a search over every memo. */
    private val head =
        scenario("head")
            .exec { session -> session.set("email", "head@volume.example") }
            .exec(signIn())
            .exec(
                // The head holds a privileged permission, so a second factor: the volume enrols them with the demo secret.
                http("POST /auth/mfa")
                    .post("/api/v1/auth/mfa")
                    .header("Cookie", "$SESSION_COOKIE=#{session}; XSRF-TOKEN=#{xsrf}")
                    .header("X-XSRF-TOKEN", "#{xsrf}")
                    .body(StringBody { _ -> """{"code":"${Totp.code(totpSecret, clock.instant().epochSecond)}"}""" })
                    .check(status().shouldBe(OK), headerRegex("Set-Cookie", "$SESSION_COOKIE=([^;]+)").optional().saveAs("session")),
            ).exec(
                read(TEAM, "/api/v1/inbox/team?limit=25")
                    .check(status().shouldBe(OK), jsonPath("\$.units[0].id").saveAs("unit")),
            ).repeat(rounds, "round")
            .on(
                exec(read(TEAM, "/api/v1/inbox/team?limit=25").check(status().shouldBe(OK), jsonPath("\$.items[24]").exists()))
                    .exec(read(DASHBOARD_UNIT, "/api/v1/dashboards/approvals?scope=unit:#{unit}").check(status().shouldBe(OK)))
                    .exec(search(SEARCH_UNIT_READER))
                    .pause(Duration.ofMillis(PAUSE_MS)),
            )

    init {
        setUp(
            approver.injectOpen(rampUsers(users).during(RAMP_SECONDS)),
            // The head starts once most approvers are reading, so the team view is measured under their load.
            head.injectOpen(nothingFor(Duration.ofSeconds(HEAD_DELAY_SECONDS)), atOnceUsers(1)),
        ).protocols(protocol)
            .assertions(
                details(SEARCH_PARTICIPANT).responseTime().percentile(P95).lt(SEARCH_BUDGET_MS),
                details(SEARCH_UNIT_READER).responseTime().percentile(P95).lt(SEARCH_BUDGET_MS),
                details(INBOX_BY_DUE).responseTime().percentile(P95).lt(INBOX_BUDGET_MS),
                details(TEAM).responseTime().percentile(P95).lt(TEAM_BUDGET_MS),
                details(DASHBOARD_ME).responseTime().percentile(P95).lt(DASHBOARD_BUDGET_MS),
                details(DASHBOARD_UNIT).responseTime().percentile(P95).lt(DASHBOARD_BUDGET_MS),
                global().failedRequests().percent().lt(MAX_ERROR_PERCENT),
            )
    }

    private companion object {
        const val SEARCH_PARTICIPANT = "POST /search (participant)"
        const val SEARCH_UNIT_READER = "POST /search (unit reader)"
        const val INBOX_BY_DUE = "GET /inbox/tasks?sort=due"
        const val TEAM = "GET /inbox/team"
        const val DASHBOARD_ME = "GET /dashboards/approvals (me)"
        const val DASHBOARD_UNIT = "GET /dashboards/approvals (unit)"
        const val SESSION_COOKIE = "MEMO_SESSION"
        const val APPROVERS = 50
        const val MEMOS = 10_000
        const val PRIME = 7919

        // Sign-ins are limited to 20 a minute per address (all users come from one): the ramp stays under it.
        const val RAMP_SECONDS = 165L
        const val HEAD_DELAY_SECONDS = 120L

        // Three requests and a pause of 3 s a round: about 18 searches a minute per person, under the limit of 30.
        const val ROUNDS = 80
        const val PAUSE_MS = 3000L
        const val OK = 200
        const val NO_CONTENT = 204
        const val P95 = 95.0
        const val SEARCH_BUDGET_MS = 300
        const val INBOX_BUDGET_MS = 150
        const val TEAM_BUDGET_MS = 300
        const val DASHBOARD_BUDGET_MS = 800
        const val MAX_ERROR_PERCENT = 1.0
    }
}
