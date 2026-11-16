package com.memo.imports

import com.memo.common.tenant.TenantContext
import com.memo.imports.internal.run.ImportRunControl
import com.memo.imports.internal.run.ImportRunLimits
import com.memo.imports.internal.run.ImportRunner
import com.memo.jooq.tables.references.IMPORTS_FILE
import com.memo.jooq.tables.references.IMPORTS_ROW
import com.memo.jooq.tables.references.IMPORTS_ROW_FILE
import com.memo.testsupport.sendJson
import jakarta.servlet.http.Cookie
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.test.web.servlet.assertj.MvcTestResult
import java.security.MessageDigest
import java.time.Duration
import java.util.HexFormat
import java.util.UUID

/**
 * Imports in tests (design E06 §5.3): validated batches with one text original per row, the import started over HTTP by
 * a holder of `memo.import` cleared to Confidential, and the `imports-run` job's steps run by the test itself (test
 * contexts share the scheduler table, so nothing is scheduled while these tests run).
 */
abstract class ImportRunTest : DryRunTest() {
    @Autowired lateinit var control: ImportRunControl

    @Autowired lateinit var runner: ImportRunner

    @BeforeEach
    fun importJobs() {
        control.jobs = false
        clear(holder, CONFIDENTIAL)
    }

    @AfterEach
    fun importJobsBack() {
        control.jobs = true
        runner.chunkRows = ImportRunLimits.CHUNK_ROWS
        runner.renditionBacklog = ImportRunLimits.RENDITION_BACKLOG
    }

    /** The path of [number]'s main original. */
    fun pathOf(
        number: String,
        extension: String = "txt",
    ): String = "originals/${number.replace('/', '-')}.$extension"

    /**
     * A VALIDATED batch of [memos] (Memos rows) whose Files rows list one main original each at [pathOf], received with
     * the content [text] of the number unless [contents] says otherwise; returns its id.
     */
    fun validated(
        memos: List<List<Any?>>,
        approvals: List<List<Any?>> = emptyList(),
        extraFiles: Map<String, Pair<String, ByteArray>> = emptyMap(),
        contents: Map<String, ByteArray> = emptyMap(),
        extension: String = "txt",
    ): String {
        val numbers = memos.map { it.first().toString() }
        val id = newBatch()
        val files =
            numbers.map { fileRow(it, pathOf(it, extension)) } +
                extraFiles.map { (number, file) -> fileRow(number, file.first, "ATTACHMENT") }
        attach(id, workbook(memos, files, approvals))
        originals(id, numbers.associate { pathOf(it, extension) to (contents[it] ?: text(it)) } + extraFiles.values.associate { it })
        val view = confirmedDryRun(id)
        assertThat(view["state"].asString()).describedAs("the batch is validated").isEqualTo("VALIDATED")
        return id
    }

    /** POST `…/start` with `If-Match` at [version] (the batch's current one by default; null: none). */
    fun start(
        batch: String,
        expectedRows: Int,
        who: Cookie = session,
        version: Long? = batch(batch)["version"].asLong(),
    ): MvcTestResult =
        mvc.sendJson(
            HttpMethod.POST,
            tenant,
            "/api/v1/admin/imports/$batch/start",
            who,
            """{"expectedRows":$expectedRows}""",
            version?.let { mapOf("If-Match" to "\"$it\"") }.orEmpty(),
        )

    /** Starts [batch] with its READY count, expecting 202. */
    fun started(batch: String) {
        val ready = rows(batch).values.count { it["result"].asString() == "READY" }
        assertThat(start(batch, ready)).hasStatus(HttpStatus.ACCEPTED)
    }

    /** POST `…/{action}` (stop, resume, finish). */
    fun act(
        batch: String,
        action: String,
        who: Cookie = session,
    ): MvcTestResult = post("/api/v1/admin/imports/$batch/$action", who)

    /** One step of the job, as the scheduler runs it; the delay before the next, or null when it is done. */
    fun step(batch: String): Duration? = TenantContext.call(tenant.id) { runner.step(UUID.fromString(batch)) }

    /** Steps until the job is done (or waits); returns how many steps ran. */
    fun runJob(batch: String): Int {
        var steps = 0
        do {
            val next = step(batch)
            steps++
            check(steps < MAX_STEPS) { "the job did not end" }
        } while (next == Duration.ZERO)
        return steps
    }

    /** The rows of [batch] by legacy number: result, attempts and reserved memo id. */
    fun runRows(batch: String): Map<String, RunRow> =
        transactions.readInTenant(tenant.id) {
            tenantDsl
                .current()
                .select(IMPORTS_ROW.LEGACY_NUMBER, IMPORTS_ROW.RESULT, IMPORTS_ROW.ATTEMPTS, IMPORTS_ROW.MEMO_ID, IMPORTS_ROW.ID)
                .from(IMPORTS_ROW)
                .where(IMPORTS_ROW.BATCH_ID.eq(UUID.fromString(batch)))
                .fetch { RunRow(it.value1()!!, it.value2()!!, it.value3()!!, it.value4(), it.value5()!!) }
                .associateBy { it.number }
        }

    /** Each Files row of [rowId] with its staged intake version and the stored (promoted) file and version. */
    fun promoted(rowId: UUID): List<PromotedFile> =
        transactions.readInTenant(tenant.id) {
            val f = IMPORTS_ROW_FILE
            val i = IMPORTS_FILE
            tenantDsl
                .current()
                .select(f.ID, f.PATH, i.VERSION_ID, i.SHA256, f.STORED_FILE_ID, f.STORED_VERSION_ID)
                .from(f)
                .join(i)
                .on(i.ID.eq(f.FILE_ID))
                .where(f.ROW_ID.eq(rowId))
                .orderBy(f.ROW_NUMBER)
                .fetch {
                    PromotedFile(
                        it.value1()!!,
                        it.value2()!!,
                        it.value3()!!,
                        HexFormat.of().formatHex(it.value4()!!),
                        it.value5(),
                        it.value6(),
                    )
                }
        }

    fun sha256(bytes: ByteArray): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

    data class RunRow(
        val number: String,
        val result: String,
        val attempts: Int,
        val memoId: UUID?,
        val id: UUID,
    )

    data class PromotedFile(
        val rowFileId: UUID,
        val path: String,
        val intakeVersionId: UUID,
        val intakeSha256: String,
        val storedFileId: UUID?,
        val storedVersionId: UUID?,
    )

    companion object {
        const val CONFIDENTIAL = 2
        private const val MAX_STEPS = 100
    }
}
