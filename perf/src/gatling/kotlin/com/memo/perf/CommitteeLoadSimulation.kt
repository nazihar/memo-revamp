package com.memo.perf

import io.gatling.javaapi.core.ChainBuilder
import io.gatling.javaapi.core.CoreDsl.StringBody
import io.gatling.javaapi.core.CoreDsl.details
import io.gatling.javaapi.core.CoreDsl.doIfOrElse
import io.gatling.javaapi.core.CoreDsl.exec
import io.gatling.javaapi.core.CoreDsl.global
import io.gatling.javaapi.core.CoreDsl.jsonPath
import io.gatling.javaapi.core.CoreDsl.rampUsers
import io.gatling.javaapi.core.CoreDsl.scenario
import io.gatling.javaapi.core.Simulation
import io.gatling.javaapi.http.HttpDsl.headerRegex
import io.gatling.javaapi.http.HttpDsl.http
import io.gatling.javaapi.http.HttpDsl.status
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Properties

/**
 * Weekly committee load (Phase 4 E08 T5, test plan "Load", design E08 §8): on the committee volume (3,600 meetings of 30
 * committees, about 54,000 decisions in the register, 29,000 actions, attendance histories), 50 people at once, ten in
 * each of the register's five reader profiles (a secretary of three committees, a member of two, an auditor with
 * `forum.read`, a member of two recused from some items, a `forums.manage` holder), each in turn searching the decision
 * register (newest first, or words), reading "My actions", their committee's action tracker, and the analytics overview
 * or their committee's detail.
 *
 * Budgets (p95, design E08 §8 and E07 §8, within NFR P1's 300 ms for interactive reads): the register newest first 150 ms
 * and with words 300 ms for every profile, "My actions" 150 ms, the tracker 200 ms, the overview 800 ms and a
 * committee's detail 500 ms; under 1 % errors. Searches are POST with a JSON body and limited to 30 a minute per person:
 * each person searches about 15 times a minute.
 *
 * The volume is written by the backend's test-side generators, so the host is the backend test `CommitteeLoadHostIT`,
 * started in the same Gradle invocation (`--parallel`, `load.yml` job `committee-load`): this simulation waits for its
 * `ready.properties` in `COMMITTEE_LOAD_DIR` (the organisation's host and each person's email, profile and committee)
 * and writes `done` there when it ends, so the host stops. `-DbaseUrl` (`http://127.0.0.1:18200` by default); the
 * people's password in `MEMO_LOAD_PASSWORD` and the TOTP secret in `MEMO_LOAD_TOTP_SECRET` (never on the command line).
 */
class CommitteeLoadSimulation : Simulation() {
    private val baseUrl = System.getProperty("baseUrl", "http://127.0.0.1:18200")
    private val rounds = Integer.getInteger("rounds", ROUNDS)
    private val dir = Path.of(requireNotNull(System.getenv("COMMITTEE_LOAD_DIR")) { "set COMMITTEE_LOAD_DIR" })
    private val password = requireNotNull(System.getenv("MEMO_LOAD_PASSWORD")) { "set MEMO_LOAD_PASSWORD" }
    private val totpSecret = requireNotNull(System.getenv("MEMO_LOAD_TOTP_SECRET")) { "set MEMO_LOAD_TOTP_SECRET" }
    private val clock = Clock.systemUTC()
    private val ready = waitForHost()
    private val tenantHost = ready.getProperty("tenantHost")

    /** Each person of the host's file: email, profile, a committee they read. */
    private val people =
        ready
            .stringPropertyNames()
            .filter { it.startsWith("person.") }
            .sortedBy { it.removePrefix("person.").toInt() }
            .map { key ->
                val (email, profile, forum) = ready.getProperty(key).split(";")
                mapOf<String, Any>("email" to email, "profile" to profile, "forum" to forum)
            }

    private val protocol =
        http
            .baseUrl(baseUrl)
            .header("Host", tenantHost)
            .acceptHeader("application/json")
            .contentTypeHeader("application/json")
            .userAgentHeader("memo-perf-committee")

    /** Blocks until the host has written its file (the volume takes a while), at most [WAIT_MINUTES]. */
    private fun waitForHost(): Properties {
        val file = dir.resolve("ready.properties")
        val until = Instant.now().plus(Duration.ofMinutes(Integer.getInteger("waitMinutes", WAIT_MINUTES).toLong()))
        while (!Files.exists(file)) {
            check(Instant.now().isBefore(until)) { "the load host wrote no $file" }
            Thread.sleep(POLL_MS)
        }
        return Properties().apply { Files.newBufferedReader(file).use { load(it) } }
    }

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
                .check(
                    status().shouldBe(OK),
                    headerRegex("Set-Cookie", "$SESSION_COOKIE=([^;]+)").saveAs("session"),
                    jsonPath("\$.state").saveAs("state"),
                ),
        ).exec(csrf("GET /auth/csrf (signed in)"))
            .doIf { session -> session.getString("state") == "MFA_REQUIRED" }
            .then(
                // Holders of privileged permissions (forums.manage, forum.read) need a second factor (the host enrols everyone),
                // sent with the token of the signed-in session; the session is renewed, and with it the token.
                exec(
                    http("POST /auth/mfa")
                        .post("/api/v1/auth/mfa")
                        .header("Cookie", "$SESSION_COOKIE=#{session}; XSRF-TOKEN=#{xsrf}")
                        .header("X-XSRF-TOKEN", "#{xsrf}")
                        .body(StringBody { _ -> """{"code":"${Totp.code(totpSecret, clock.instant().epochSecond)}"}""" })
                        .check(status().shouldBe(OK), headerRegex("Set-Cookie", "$SESSION_COOKIE=([^;]+)").optional().saveAs("session")),
                ).exec(csrf("GET /auth/csrf (after MFA)")),
            )

    /** A fresh CSRF token for the signed-in session (signing in rotates it): POSTs send it. */
    private fun csrf(name: String) =
        http(name)
            .get("/api/v1/auth/csrf")
            .header("Cookie", "$SESSION_COOKIE=#{session}")
            // A token still valid is not set again (after the second factor): the session keeps the one it has.
            .check(status().shouldBe(NO_CONTENT), headerRegex("Set-Cookie", "XSRF-TOKEN=([^;]+)").optional().saveAs("xsrf"))

    /** Even rounds search newest first (no words); odd ones search words in turn. */
    private fun body(round: Int): String = if (round % 2 == 0) "{}" else """{"text":"${WORDS[(round / 2) % WORDS.size]}"}"""

    private fun search(kind: String) =
        http("$SEARCH (#{profile}, $kind)")
            .post("/api/v1/decision-register/search")
            .header("Cookie", "$SESSION_COOKIE=#{session}; XSRF-TOKEN=#{xsrf}")
            .header("X-XSRF-TOKEN", "#{xsrf}")
            .body(StringBody { session -> body(session.getInt("round")) })
            .check(status().shouldBe(OK), jsonPath("\$.total").exists())

    private fun read(
        name: String,
        path: String,
    ) = http(name).get(path).header("Cookie", "$SESSION_COOKIE=#{session}").check(status().shouldBe(OK))

    private val person =
        scenario("committee people")
            .feed(people.iterator())
            .exec(signIn())
            .repeat(rounds, "round")
            .on(
                // The round's search: newest first on even rounds, words on odd ones (each its own request name).
                doIfOrElse { session -> session.getInt("round") % 2 == 0 }
                    .then(exec(search(NEWEST)))
                    .orElse(exec(search(WORDS_KIND)))
                    .exec(read(MY_ACTIONS, "/api/v1/me/actions?tab=MINE&limit=50"))
                    .exec(read(TRACKER, "/api/v1/forums/#{forum}/actions?limit=50"))
                    .doIf { session -> session.getInt("round") % 4 == 0 }
                    .then(exec(read(OVERVIEW, "/api/v1/analytics/committees")))
                    .doIf { session -> session.getInt("round") % 4 == 2 }
                    .then(exec(read(DETAIL, "/api/v1/analytics/committees/#{forum}")))
                    .pause(Duration.ofMillis(PAUSE_MS)),
            )

    /** Writes `done`, so the host stops serving. */
    override fun after() {
        Files.writeString(dir.resolve("done"), Instant.now().toString())
    }

    init {
        require(people.size == USERS) { "the host's file lists ${people.size} people, not $USERS" }
        setUp(person.injectOpen(rampUsers(people.size).during(RAMP_SECONDS)))
            .protocols(protocol)
            .assertions(
                PROFILES.flatMap { profile ->
                    listOf(
                        details("$SEARCH ($profile, $NEWEST)").responseTime().percentile(P95).lt(NEWEST_BUDGET_MS),
                        details("$SEARCH ($profile, $WORDS_KIND)").responseTime().percentile(P95).lt(WORDS_BUDGET_MS),
                    )
                } +
                    listOf(
                        details(MY_ACTIONS).responseTime().percentile(P95).lt(MY_ACTIONS_BUDGET_MS),
                        details(TRACKER).responseTime().percentile(P95).lt(TRACKER_BUDGET_MS),
                        details(OVERVIEW).responseTime().percentile(P95).lt(OVERVIEW_BUDGET_MS),
                        details(DETAIL).responseTime().percentile(P95).lt(DETAIL_BUDGET_MS),
                        global().failedRequests().percent().lt(MAX_ERROR_PERCENT),
                    ),
            )
    }

    private companion object {
        const val SEARCH = "POST /decision-register/search"
        const val NEWEST = "newest"
        const val WORDS_KIND = "words"
        const val MY_ACTIONS = "GET /me/actions"
        const val TRACKER = "GET /forums/{id}/actions"
        const val OVERVIEW = "GET /analytics/committees"
        const val DETAIL = "GET /analytics/committees/{id}"
        const val SESSION_COOKIE = "MEMO_SESSION"

        /** The host's profiles (`CommitteeVolumeSupport`), ten people each. */
        val PROFILES = listOf("secretary of three", "member of two", "auditor (forum.read)", "recused member of two", "forums.manage")
        const val USERS = 50
        val WORDS = listOf("covenants", "proposal", "quarterly review")

        // Sign-ins are limited to 20 a minute per address (all users come from one): the ramp stays under it.
        const val RAMP_SECONDS = 165L

        // One search, two or three reads and a pause of 3 s a round: about 15 searches a minute per person, under 30.
        const val ROUNDS = 60
        const val PAUSE_MS = 3000L
        const val WAIT_MINUTES = 90
        const val POLL_MS = 5000L
        const val OK = 200
        const val NO_CONTENT = 204
        const val P95 = 95.0
        const val NEWEST_BUDGET_MS = 150
        const val WORDS_BUDGET_MS = 300
        const val MY_ACTIONS_BUDGET_MS = 150
        const val TRACKER_BUDGET_MS = 200
        const val OVERVIEW_BUDGET_MS = 800
        const val DETAIL_BUDGET_MS = 500
        const val MAX_ERROR_PERCENT = 1.0
    }
}
