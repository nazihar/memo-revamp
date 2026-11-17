package com.memo.imports

import com.memo.common.context.Actor
import com.memo.common.context.ActorContext
import com.memo.common.context.ActorScope
import com.memo.common.tenant.TenantContext
import com.memo.imports.internal.report.ImportEndLimits
import com.memo.jooq.tables.references.IMPORTS_BATCH
import com.memo.testsupport.AppRoleJdbc
import com.memo.testsupport.AppRoleJdbc.long
import com.memo.testsupport.ImportSheets
import org.apache.poi.xssf.streaming.SXSSFWorkbook
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.http.HttpStatus
import java.io.ByteArrayOutputStream
import java.time.Duration
import java.util.Random
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * The import at the stated volume (design E06 §14; test plan E06 T10 `ImportVolumeIT`): `IMPORT_VOLUME` memos (10,000 for
 * the recorded run) with three originals each (`IMPORT_ORIGINAL_BYTES`, 50,000 by default: about 1.5 GB at 10,000), through
 * the real pipeline in the test containers (clamd scans every original twice, at intake and at promotion; SeaweedFS holds
 * the staged and the stored copies). It measures and prints the intake, both dry runs, the import, the verification (every
 * original read back and hashed), the report and the purge, and asserts §14's budgets: a dry run under 5 minutes and the
 * import under 2 hours, renditions excluded. The throttle engages once 200 renditions are pending (rendition jobs are off
 * here, so they stay pending); the test then lifts it, as renditions are not part of the budget. A second test parses and
 * dry-runs `IMPORT_METADATA_ROWS` (50,000) Memos rows without originals. Not part of the normal check: it runs only when
 * `IMPORT_VOLUME` is set (on demand and before an epic closes; the weekly `load` workflow runs it at 10,000).
 */
@EnabledIfEnvironmentVariable(named = "IMPORT_VOLUME", matches = "[1-9][0-9]*")
class ImportVolumeIT : ImportEndTest() {
    private val memos = System.getenv("IMPORT_VOLUME")?.toIntOrNull() ?: 0
    private val originalBytes = System.getenv("IMPORT_ORIGINAL_BYTES")?.toIntOrNull() ?: ORIGINAL_BYTES
    private val metadataRows = System.getenv("IMPORT_METADATA_ROWS")?.toIntOrNull() ?: METADATA_ROWS

    @Test
    fun `an import of the stated volume meets the design's budgets`() {
        val heap = HeapSampler().also(Thread::start)
        val id = newBatch()
        val numbers = (1..memos).map { "VOL/%06d".format(it) }
        val files =
            numbers.flatMapIndexed { i, n ->
                (0 until PER_MEMO).map { k -> fileRow(n, path(i, k), if (k == 0) "MAIN" else "ATTACHMENT") }
            }
        val book = streamed(numbers.map { memoRow(it, subject = "Volume memo $it") }, files)
        attach(id, book)
        val intake = timed { receive(id, numbers.indices.flatMap { i -> (0 until PER_MEMO).map { k -> path(i, k) } }) }

        signInAgain()
        val firstDryRun = timed { dryRun(id) }
        confirmAll(id)
        val dryRun = timed { dryRun(id) }
        val validated = batch(id)
        assertThat(validated["state"].asString()).isEqualTo("VALIDATED")
        val ready = validated["rows"]["READY"].asInt()
        assertThat(ready).isEqualTo(memos)

        // Sessions idle out after 30 minutes and the step-up window is 5: each phase after a long one signs in afresh.
        signInAgain()
        assertThat(start(id, ready)).hasStatus(HttpStatus.ACCEPTED)
        val (import, throttledAfter) = importAll(id)
        signInAgain()
        assertThat(batch(id)["state"].asString()).isEqualTo("VERIFYING")
        assertThat(imported(id)).isEqualTo(memos)

        val verify = timed { verify(id) }
        backdate(id, IMPORTS_BATCH.FINISHED_AT, ImportEndLimits.REPORT_MAX_WAIT.plusMinutes(1))
        val report = timed { assertThat(reportStep(id)).isNull() }
        val done = ok(report(id))
        assertThat(done["balanced"].asBoolean()).isTrue()
        assertThat(done["totals"]["fixity"]["verified"].asInt()).isEqualTo(memos * PER_MEMO)

        signInAgain()
        assertThat(close(id)).hasStatus(HttpStatus.OK)
        val purge = timed { purge(id) }
        val purged = AppRoleJdbc.inTransaction(tenant.id) { c -> c.long(PURGED, UUID.fromString(id)) }
        heap.interrupt()

        val originals = memos * PER_MEMO
        val database = AppRoleJdbc.connect { c -> c.long("select pg_database_size(current_database())") }
        println(
            """
            |ImportVolumeIT: $memos memos, $originals originals of $originalBytes bytes (${originals.toLong() * originalBytes / MB} MB),
            |  workbook ${book.size / KB} KB
            |  intake (register + upload, $UPLOADERS holders): ${seconds(intake)} s, ${perMinute(originals, intake)} originals/min
            |  dry run (proposals): ${seconds(firstDryRun)} s; dry run (confirmed): ${seconds(dryRun)} s
            |  import: ${seconds(import)} s, ${perMinute(memos, import)} memos/min, ${perMinute(originals, import)} originals/min
            |  throttle engaged after ${throttledAfter ?: "never"} memos imported
            |  verification: ${seconds(verify)} s, ${perMinute(originals, verify)} originals/min
            |  report: ${seconds(report)} s
            |  purge: ${seconds(purge)} s, $purged staged versions, ${perMinute(purged.toInt(), purge)} per min
            |  peak heap used: ${heap.peak.get() / MB} MB; database size: ${database / MB} MB
            """.trimMargin(),
        )
        assertThat(firstDryRun).describedAs("design E06 §14: dry run").isLessThan(DRY_RUN_BUDGET)
        assertThat(dryRun).describedAs("design E06 §14: dry run").isLessThan(DRY_RUN_BUDGET)
        assertThat(import).describedAs("design E06 §14: import, renditions excluded").isLessThan(IMPORT_BUDGET)
    }

    @Test
    fun `a metadata-only workbook of 50,000 memos parses and dry-runs`() {
        val id = newBatch()
        val numbers = (1..metadataRows).map { "META/%06d".format(it) }
        val book = streamed(numbers.map { memoRow(it) }, numbers.map { fileRow(it, "meta/${it.replace('/', '-')}.pdf") })
        attach(id, book)
        // Before the mappings are confirmed every row has several problems, past the 250,000 the batch keeps.
        val proposals = timed { dryRun(id) }
        confirmAll(id)
        val dryRun = timed { dryRun(id) }
        val view = batch(id)
        println(
            "ImportVolumeIT: $metadataRows Memos and Files rows (${book.size / KB} KB workbook): dry run (proposals) " +
                "${seconds(proposals)} s, dry run (confirmed) ${seconds(dryRun)} s",
        )
        assertThat(view["problems"]["BATCH"]?.asInt() ?: 0).describedAs("no batch problem (the parse within its 120 s)").isZero()
        assertThat(view["rows"]["REJECTED"].asInt()).describedAs("no original received").isEqualTo(metadataRows)
    }

    /** Registers [paths] a thousand at a time and uploads each, [UPLOADERS] holders at once (each has its own rate). */
    private fun receive(
        batch: String,
        paths: List<String>,
    ) {
        val ids = mutableMapOf<String, String>()
        paths.chunked(REGISTER_CHUNK).forEach { chunk ->
            val body = json.writeValueAsString(mapOf("files" to chunk.map { mapOf("path" to it, "size" to originalBytes) }))
            val registered = post("/api/v1/admin/imports/$batch/files", session, body).json()
            (0 until registered.size()).forEach { ids[registered[it]["path"].asString()] = registered[it]["intakeId"].asString() }
        }
        val holders = (1..UPLOADERS).map { imports.importer(mvc, tenant).first }
        val failed = AtomicInteger()
        val pool = Executors.newFixedThreadPool(UPLOADERS)
        paths.forEachIndexed { i, path ->
            pool.execute {
                val intakeId = ids.getValue(path)
                val bytes = content(i)
                val name = path.substringAfterLast('/')
                val stored =
                    (1..ATTEMPTS).any { attempt ->
                        runCatching {
                            asHolder(holders[i % UPLOADERS]) {
                                fileUploads.upload(INTAKE, intakeId, name, bytes, "imp-$intakeId-$originalBytes")
                            }
                        }.onFailure { if (attempt < ATTEMPTS) Thread.sleep(RETRY_MILLIS * attempt) }.isSuccess
                    }
                if (!stored) failed.incrementAndGet()
            }
        }
        pool.shutdown()
        check(pool.awaitTermination(INTAKE_LIMIT_MINUTES, TimeUnit.MINUTES)) { "the intake did not end" }
        assertThat(failed.get()).describedAs("originals refused at intake").isZero()
    }

    /** Text of [originalBytes] characters, different for every original (its own seed), so no two share a hash. */
    private fun content(index: Int): ByteArray {
        val random = Random(index.toLong())
        return ByteArray(originalBytes) { i ->
            if (i % LINE == LINE - 1) NEWLINE else LETTERS[random.nextInt(LETTERS.length)].code.toByte()
        }
    }

    /** A workbook of text cells written as a stream (SXSSF), so tens of thousands of rows do not sit in the heap. */
    private fun streamed(
        memos: List<List<Any?>>,
        files: List<List<Any?>>,
    ): ByteArray =
        SXSSFWorkbook(WINDOW).use { book ->
            mapOf("Memos" to listOf(ImportSheets.MEMO_HEADER) + memos, "Files" to listOf(ImportSheets.FILE_HEADER) + files)
                .forEach { (name, rows) ->
                    val sheet = book.createSheet(name)
                    rows.forEachIndexed { r, values ->
                        val row = sheet.createRow(r)
                        values.forEachIndexed { c, value -> if (value != null) row.createCell(c).setCellValue(value.toString()) }
                    }
                }
            ByteArrayOutputStream().also(book::write).toByteArray()
        }

    private fun path(
        memo: Int,
        file: Int,
    ): String = "vol/${memo / PER_FOLDER}/m$memo/${if (file == 0) "memo" else "annex-$file"}.txt"

    /**
     * Runs the import's steps to the end; answers how long it took and after how many memos the throttle engaged. The
     * rendition backlog (200 pending) stops the import once, since rendition jobs are off; renditions are not in the
     * budget, so the backlog is then lifted.
     */
    private fun importAll(batch: String): Pair<Duration, Int?> {
        var throttledAfter: Int? = null
        val took =
            timed {
                do {
                    val next = step(batch)
                    if (next != null && next > Duration.ZERO) {
                        check(throttledAfter == null) { "the import waited again after the throttle was lifted: $next" }
                        throttledAfter = imported(batch)
                        runner.renditionBacklog = Int.MAX_VALUE
                    }
                } while (next != null)
            }
        return took to throttledAfter
    }

    /** A holder of `memo.import` who has just signed in with a second factor, cleared to Confidential, as [session]. */
    private fun signInAgain() {
        val (user, cookie) = imports.importer(mvc, tenant)
        clear(user, CONFIDENTIAL)
        session = cookie
    }

    private fun imported(batch: String): Int =
        AppRoleJdbc.inTransaction(tenant.id) { c -> c.long(IMPORTED_ROWS, UUID.fromString(batch)) }.toInt()

    private fun <T> asHolder(
        user: UUID,
        block: () -> T,
    ): T = ActorContext.call(ActorScope(Actor.User(user), null)) { TenantContext.call(tenant.id, block) }

    private fun timed(block: () -> Unit): Duration {
        val started = System.nanoTime()
        block()
        return Duration.ofNanos(System.nanoTime() - started)
    }

    private fun seconds(d: Duration): String = "%.1f".format(d.toMillis() / MILLIS)

    private fun perMinute(
        count: Int,
        d: Duration,
    ): Long = if (d.isZero) 0 else count * MINUTE_MILLIS / d.toMillis().coerceAtLeast(1)

    /** The highest heap in use, sampled twice a second while the import runs. */
    private class HeapSampler : Thread("import-volume-heap") {
        val peak = AtomicLong()

        init {
            isDaemon = true
        }

        override fun run() {
            val runtime = Runtime.getRuntime()
            while (!isInterrupted) {
                peak.accumulateAndGet(runtime.totalMemory() - runtime.freeMemory(), ::maxOf)
                try {
                    sleep(SAMPLE_MILLIS)
                } catch (_: InterruptedException) {
                    return
                }
            }
        }
    }

    private companion object {
        const val PER_MEMO = 3
        const val WINDOW = 500
        const val PER_FOLDER = 1000
        const val ORIGINAL_BYTES = 50_000
        const val METADATA_ROWS = 50_000
        const val UPLOADERS = 8
        const val ATTEMPTS = 5
        const val RETRY_MILLIS = 2000L
        const val INTAKE = "imports.intake"
        const val REGISTER_CHUNK = 1000
        const val INTAKE_LIMIT_MINUTES = 240L
        const val LINE = 80
        const val NEWLINE: Byte = 10
        const val LETTERS = "abcdefghijklmnopqrstuvwxyz      "
        const val KB = 1024
        const val MB = 1024L * 1024
        const val MILLIS = 1000.0
        const val MINUTE_MILLIS = 60_000L
        const val SAMPLE_MILLIS = 500L
        val DRY_RUN_BUDGET: Duration = Duration.ofMinutes(5)
        val IMPORT_BUDGET: Duration = Duration.ofHours(2)
        const val IMPORTED_ROWS = "select count(*) from imports_row where batch_id = ? and result = 'IMPORTED'"
        const val PURGED = "select count(*) from imports_staged where batch_id = ? and purged_at is not null"
    }
}
