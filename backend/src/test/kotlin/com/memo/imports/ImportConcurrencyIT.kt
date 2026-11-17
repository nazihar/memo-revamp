package com.memo.imports

import com.memo.common.tenant.TenantContext
import com.memo.testsupport.Audit
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * One running import per tenant, and each row imported once (ADR-0055, design E06 §4.1, §4.2; test plan E06 T7
 * `ImportConcurrencyIT`): two starts of one batch, starts of two batches, a resume while another batch runs, and two
 * executions of the job on one batch at the same time.
 */
class ImportConcurrencyIT : ImportRunTest() {
    @Test
    fun `of two starts of one batch at once, one starts it and the other is refused`() {
        val id = validated(listOf(memoRow("C/1")))
        val version = batch(id)["version"].asLong()
        val results = together({ start(id, 1, version = version) }, { start(id, 1, version = version) })
        assertThat(results.map { it.response.status }).containsExactlyInAnyOrder(HttpStatus.ACCEPTED.value(), HttpStatus.CONFLICT.value())
        assertThat(
            results.single { it.response.status == HttpStatus.CONFLICT.value() }.json()["reason"].asString(),
        ).isEqualTo("WRONG_STATE")
        assertThat(Audit.entries(tenant.id).count { it.action == "import.batch.started" && it.objectId == id }).isOne()
    }

    @Test
    fun `only one batch of a tenant imports at a time, also when two start at once or a stopped one resumes`() {
        val first = validated(listOf(memoRow("O/1")))
        val second = validated(listOf(memoRow("O/2")))
        val third = validated(listOf(memoRow("O/3")))
        val (v1, v2) = listOf(first, second).map { batch(it)["version"].asLong() }
        val results = together({ start(first, 1, version = v1) }, { start(second, 1, version = v2) })
        assertThat(results.map { it.response.status }).containsExactlyInAnyOrder(HttpStatus.ACCEPTED.value(), HttpStatus.CONFLICT.value())
        assertThat(results.single { it.response.status == HttpStatus.CONFLICT.value() }.json()["reason"].asString())
            .isEqualTo("ANOTHER_IMPORT_RUNNING")
        val running = if (results[0].response.status == HttpStatus.ACCEPTED.value()) first else second
        val waiting = if (running == first) second else first
        assertThat(start(third, 1).json()["reason"].asString()).isEqualTo("ANOTHER_IMPORT_RUNNING")

        // A stopped import does not count, but cannot resume while another one runs.
        assertThat(act(running, "stop")).hasStatus(HttpStatus.ACCEPTED)
        assertThat(step(running)).isNull()
        assertThat(start(waiting, 1)).hasStatus(HttpStatus.ACCEPTED)
        assertThat(act(running, "resume").json()["reason"].asString()).isEqualTo("ANOTHER_IMPORT_RUNNING")
        assertThat(act(running, "finish").json()["reason"].asString())
            .describedAs("another batch runs")
            .isEqualTo("ANOTHER_IMPORT_RUNNING")
        assertThat(batch(running)["state"].asString()).isEqualTo("STOPPED")
    }

    @Test
    fun `two executions of the job on one batch never import a row twice`() {
        val numbers = (1..ROWS).map { "J/$it" }
        val id = validated(numbers.map { memoRow(it) })
        started(id)
        runner.chunkRows = 2
        together({ drain(id) }, { drain(id) })

        val rows = runRows(id)
        assertThat(rows.values.map { it.result to it.attempts }).containsOnly("IMPORTED" to 1)
        val memos = rows.values.map { it.memoId.toString() }.toSet()
        assertThat(
            Audit.entries(tenant.id).filter { it.action == "memo.imported" }.map { it.objectId },
        ).containsExactlyInAnyOrderElementsOf(memos)
        assertThat(batch(id)["state"].asString()).isEqualTo("VERIFYING")
    }

    /** Steps until the job is done, waiting briefly while the other execution holds rows. */
    private fun drain(batch: String) {
        var steps = 0
        while (true) {
            val next = TenantContext.call(tenant.id) { runner.step(UUID.fromString(batch)) } ?: return
            if (next != Duration.ZERO) Thread.sleep(HELD_PAUSE_MS)
            check(++steps < MAX_STEPS) { "the job did not end" }
        }
    }

    /** Runs [tasks] on threads of their own, released together; their results in order. */
    private fun <T> together(vararg tasks: () -> T): List<T> {
        val pool = Executors.newFixedThreadPool(tasks.size)
        val gate = CountDownLatch(1)
        try {
            val futures = tasks.map { task -> pool.submit<T> { gate.await().let { task() } } }
            gate.countDown()
            return futures.map { it.get(TIMEOUT_SECONDS, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
    }

    private companion object {
        const val ROWS = 6
        const val MAX_STEPS = 200
        const val HELD_PAUSE_MS = 50L
        const val TIMEOUT_SECONDS = 120L
    }
}
