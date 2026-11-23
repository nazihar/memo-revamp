package com.memo.analytics

import com.memo.forum.ForumDirectory
import com.memo.identity.UserId
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * The host of the weekly committee load (test plan E08 T5, `perf`'s `CommitteeLoadSimulation`): the application on a
 * real port (`COMMITTEE_LOAD_PORT`, 18200 by default) over the committee volume ([CommitteeVolumeSupport], at
 * `COMMITTEE_VOLUME` meetings, 3,600 by default) with ten people in each of the five reader profiles, who sign in with
 * `MEMO_LOAD_PASSWORD` (and the TOTP secret `MEMO_LOAD_TOTP_SECRET` where a second factor is needed); both come from
 * the environment, never the command line or a file.
 *
 * The committee volume is written by the test-side generators as the schema owner, which an application started from its
 * image cannot do, so the simulation runs against this test's application instead of the on-prem stack. Once the volume
 * is ready it writes `ready.properties` (the organisation's host and, for each person, the email, the profile and a
 * committee they read; no secret) to `COMMITTEE_LOAD_DIR`, then serves until the simulation writes `done` there, or for
 * at most `COMMITTEE_LOAD_MINUTES` (60). Run with the simulation in one Gradle invocation (`--parallel`; `load.yml`,
 * job `committee-load`). Skipped unless `COMMITTEE_LOAD` is `true`.
 */
@EnabledIfEnvironmentVariable(named = "COMMITTEE_LOAD", matches = "true")
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT,
    properties = ["server.port=\${COMMITTEE_LOAD_PORT:18200}"],
)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CommitteeLoadHostIT : CommitteeVolumeSupport() {
    @Autowired lateinit var directory: ForumDirectory

    @Test
    fun `serves the committee volume to the load simulation`() {
        val dir = Path.of(requireNotNull(System.getenv("COMMITTEE_LOAD_DIR")) { "set COMMITTEE_LOAD_DIR" })
        Files.createDirectories(dir)
        val ready = dir.resolve(READY)
        val done = dir.resolve(DONE)
        Files.deleteIfExists(ready)
        Files.deleteIfExists(done)
        // The people sign in with these (CommitteeVolumeSupport reads them), so both must be set.
        requireNotNull(System.getenv("MEMO_LOAD_PASSWORD")) { "set MEMO_LOAD_PASSWORD" }
        requireNotNull(System.getenv("MEMO_LOAD_TOTP_SECRET")) { "set MEMO_LOAD_TOTP_SECRET" }
        generateCommittees("committeeload")
        val lines =
            profiles.byName().flatMap { (profile, users) ->
                users.map { user ->
                    val email = asUser(people.admin) { checkNotNull(accounts.find(UserId(user))).email }
                    "$email;$profile;${committeeOf(user)}"
                }
            }
        val properties =
            listOf("tenantHost=${tenant.slug}.localhost") + lines.mapIndexed { i, line -> "person.${i + 1}=$line" }
        // Written whole, then moved into place: the simulation never reads half a file.
        val partial = dir.resolve("$READY.partial")
        Files.write(partial, properties)
        Files.move(partial, ready)
        println("$label ready: ${lines.size} people on ${tenant.slug}.localhost")
        val until = Instant.now().plus(Duration.ofMinutes(System.getenv("COMMITTEE_LOAD_MINUTES")?.toLongOrNull() ?: MINUTES))
        while (!Files.exists(done) && Instant.now().isBefore(until)) Thread.sleep(POLL_MS)
        println("$label done: ${if (Files.exists(done)) "the simulation finished" else "timed out"}")
    }

    /** A committee [user] reads, for the tracker and a committee's analytics: their first officer or member seat, else C1. */
    private fun committeeOf(user: UUID): UUID = asUser(user) { directory.rolesOf(user).keys }.firstOrNull() ?: committees.first().first

    private companion object {
        const val READY = "ready.properties"
        const val DONE = "done"
        const val MINUTES = 60L
        const val POLL_MS = 2_000L
    }
}
