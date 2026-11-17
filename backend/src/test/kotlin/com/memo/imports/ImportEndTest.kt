package com.memo.imports

import com.memo.common.context.ActorContext
import com.memo.common.tenant.TenantContext
import com.memo.files.FileRenditions
import com.memo.files.RenditionState
import com.memo.files.internal.RenditionRetry
import com.memo.files.internal.RenditionScheduler
import com.memo.files.internal.RenditionWorker
import com.memo.imports.internal.close.ImportClosing
import com.memo.imports.internal.close.ImportSweep
import com.memo.imports.internal.report.ImportReports
import com.memo.imports.internal.report.ImportVerification
import com.memo.jooq.tables.references.IMPORTS_BATCH
import com.memo.jooq.tables.references.IMPORTS_ROW_FILE
import jakarta.servlet.http.Cookie
import org.assertj.core.api.Assertions.assertThat
import org.jooq.Field
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import org.springframework.test.web.servlet.assertj.MvcTestResult
import tools.jackson.databind.JsonNode
import java.time.Duration
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * After the import in tests (design E06 §5.4, §8, §15): batches imported and VERIFYING, the verification, the report, the
 * purge and the sweep run by the test itself, and the imported originals' renditions built by hand (their scheduling is
 * off, so they stay PENDING until the test builds them).
 */
abstract class ImportEndTest : ImportRunTest() {
    @Autowired lateinit var verification: ImportVerification

    @Autowired lateinit var reports: ImportReports

    @Autowired lateinit var closing: ImportClosing

    @Autowired lateinit var sweeper: ImportSweep

    @Autowired lateinit var fileRenditions: FileRenditions

    @Autowired internal lateinit var worker: RenditionWorker

    @Autowired internal lateinit var renditionScheduling: RenditionScheduler

    @BeforeEach
    fun renditionsByHand() {
        renditionScheduling.jobs = false
    }

    @AfterEach
    fun renditionsBack() {
        renditionScheduling.jobs = true
    }

    /** A batch of [memos] imported to the end of its rows: VERIFYING; returns its id. */
    fun imported(
        memos: List<List<Any?>>,
        contents: Map<String, ByteArray> = emptyMap(),
    ): String {
        val id = validated(memos, contents = contents)
        started(id)
        runJob(id)
        assertThat(batch(id)["state"].asString()).isEqualTo("VERIFYING")
        return id
    }

    fun verify(batch: String) = TenantContext.call(tenant.id) { verification.verify(UUID.fromString(batch)) }

    /** One look of the report job; the delay before the next, or null when it is done. */
    fun reportStep(batch: String): Duration? = TenantContext.call(tenant.id) { reports.step(UUID.fromString(batch)) }

    /** Builds every pending rendition of the originals [batch] stored, as the rendition job would. */
    fun renditionsDone(batch: String) {
        val versions = storedVersions(batch)
        val pending =
            transactions.readInTenant(tenant.id) { fileRenditions.states(versions) }.flatMap { (version, kinds) ->
                kinds.filterValues { it == RenditionState.PENDING }.keys.map { version to it }
            }
        ActorContext.asSystem("files-rendition") {
            TenantContext.call(tenant.id) {
                pending.forEach { (version, kind) ->
                    run attempts@{
                        repeat(RenditionWorker.MAX_ATTEMPTS) {
                            try {
                                worker.run(version, kind)
                                return@attempts
                            } catch (_: RenditionRetry) {
                                // As the scheduler would: again.
                            }
                        }
                    }
                }
            }
        }
    }

    /** Verified, renditions built, report stored: COMPLETED; answers the batch. */
    fun completed(batch: String): JsonNode {
        verify(batch)
        renditionsDone(batch)
        assertThat(reportStep(batch)).isNull()
        val view = batch(batch)
        assertThat(view["state"].asString()).isEqualTo("COMPLETED")
        return view
    }

    fun report(
        batch: String,
        who: Cookie = session,
    ): MvcTestResult = read("/api/v1/admin/imports/$batch/report", who)

    /** The report's part of [kind], downloaded by [who]. */
    fun part(
        batch: String,
        kind: String,
        who: Cookie = session,
    ): MvcTestResult {
        val part = report(batch).json()["parts"].items().single { it["kind"].asString() == kind }
        return read("/api/v1/files/${part["fileId"].asString()}/versions/${part["versionId"].asString()}/content", who)
    }

    fun close(
        batch: String,
        reason: String? = "Migration checked",
        who: Cookie = session,
    ): MvcTestResult = post("/api/v1/admin/imports/$batch/close", who, json.writeValueAsString(mapOf("reason" to reason)))

    fun abandon(
        batch: String,
        who: Cookie = session,
    ): MvcTestResult = post("/api/v1/admin/imports/$batch/abandon", who, """{"reason":"Not needed"}""")

    /** Runs the purge job of [batch] to its end; returns how many steps it took. */
    fun purge(batch: String): Int {
        var steps = 0
        do {
            val next = TenantContext.call(tenant.id) { closing.purgeStep(UUID.fromString(batch)) }
            steps++
        } while (next == Duration.ZERO)
        return steps
    }

    /** The versions [batch] stored for its Files rows (bound or not). */
    fun storedVersions(batch: String): List<UUID> =
        transactions.readInTenant(tenant.id) {
            tenantDsl
                .current()
                .select(IMPORTS_ROW_FILE.STORED_VERSION_ID)
                .from(IMPORTS_ROW_FILE)
                .where(IMPORTS_ROW_FILE.BATCH_ID.eq(UUID.fromString(batch)), IMPORTS_ROW_FILE.STORED_VERSION_ID.isNotNull)
                .fetch { it.value1()!! }
        }

    /** Sets a time column of [batch] to [ago] before now (as if that much time had passed). */
    fun backdate(
        batch: String,
        column: Field<OffsetDateTime?>,
        ago: Duration,
    ) = transactions.inTenant(tenant.id) {
        tenantDsl
            .current()
            .update(IMPORTS_BATCH)
            .set(column, OffsetDateTime.now(ZoneOffset.UTC).minus(ago))
            .where(IMPORTS_BATCH.ID.eq(UUID.fromString(batch)))
            .execute()
    }

    fun ok(result: MvcTestResult): JsonNode {
        assertThat(result).hasStatus(HttpStatus.OK)
        return result.json()
    }
}
