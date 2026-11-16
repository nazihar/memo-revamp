package com.memo.imports.internal

import com.memo.common.tenant.TenantDsl
import com.memo.files.Files
import com.memo.imports.internal.dryrun.ProblemLevel
import com.memo.imports.internal.dryrun.RowResult
import com.memo.imports.internal.run.RunProgress
import com.memo.jooq.tables.references.IMPORTS_PROBLEM
import com.memo.jooq.tables.references.IMPORTS_ROW
import io.swagger.v3.oas.annotations.media.Schema
import org.jooq.impl.DSL
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.util.UUID

/** What the batch's screen offers now (design E06 §10 `allowedActions`). */
enum class BatchAction { EDIT, ATTACH_SOURCE, REGISTER_ORIGINALS, MAP, DRY_RUN, START, STOP, RESUME, FINISH, ABANDON, CLOSE }

/** The report in a word (design E06 §8): whether it balances, and BagIt's complete and valid. */
data class ImportReportSummary(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val balanced: Boolean,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val complete: Boolean,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val valid: Boolean,
)

/** A started import's progress (design E06 §10): the rows it took on (READY at the start) and how they ended so far. */
data class ImportProgressView(
    /** Rows the import took on: those READY when it started. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val rows: Int,
    /** Imported, skipped, rejected or failed. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val done: Int,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val imported: Int,
    /** Already imported unchanged by an earlier batch. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val skipped: Int,
    /** A check failed at import time (`ALREADY_IMPORTED_CHANGED`, a number taken since the dry run, ...). */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val rejected: Int,
    /** An original was refused, or the row failed after its last attempt. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val failed: Int,
    /** Not reached: the import was finished after a stop. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val notStarted: Int,
    /** Still to import (ready, or being imported now). */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val left: Int,
    /** Rows done a minute, over the last ten minutes. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val ratePerMinute: Double,
    /** At that rate; absent when nothing is left, nothing moved lately, or the import is not running. */
    val estimatedEnd: Instant? = null,
    /** The job waits for the previews of imported originals to catch up (design E06 §5.3). */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val throttled: Boolean,
    val throttledSince: Instant? = null,
)

data class ImportSourceView(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val table: SourceTable,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val fileId: UUID,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val versionId: UUID,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val name: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val sizeBytes: Long,
    /** Lower-case hex SHA-256 of the content as received. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val sha256: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val uploadedAt: Instant,
)

/** The originals by intake state, with their bytes (registered sizes, received sizes once stored). */
data class ImportOriginalsView(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val expected: Int,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val received: Int,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val refused: Int,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val purged: Int,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val expectedBytes: Long,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val receivedBytes: Long,
)

/** A batch as its screen shows it (design E06 §10 `BatchView`); progress and the report come with T7 and T8. */
data class ImportBatchView(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val id: UUID,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val name: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val sourceCode: String,
    /** An IANA time zone, in which the legacy dates are days. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val timeZone: String,
    val defaultClassificationId: UUID?,
    val numberPrefix: String?,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val state: BatchState,
    /** The `ETag` clients send back in `If-Match`. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val version: Long,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val createdBy: UUID,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val createdAt: Instant,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val updatedAt: Instant,
    /** How the metadata came; null until a source is uploaded. */
    val sourceKind: SourceKind?,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val sources: List<ImportSourceView>,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val originals: ImportOriginalsView,
    /** Problems of the last dry run and of the import by level (BATCH, ERROR, WARNING, FILE, INFO). */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val problems: Map<String, Int>,
    /** Memos rows by result (PENDING, READY, REJECTED, ...). */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val rows: Map<String, Int>,
    val dryRunAt: Instant?,
    /** SHA-256 (hex) of what the last dry run read: sources, received originals, mappings and settings. */
    val dryRunHash: String? = null,
    /** The highest classification rank the import writes (the importer must be cleared to it). */
    val maxRank: Int? = null,
    /** Who started the import, when, and when their step-up was proven. */
    val startedBy: UUID? = null,
    val startedAt: Instant? = null,
    val stepUpAt: Instant? = null,
    /** When the import ended (its last row, or finished after a stop). */
    val finishedAt: Instant? = null,
    /** Present once the import started. */
    val progress: ImportProgressView? = null,
    /** When the verification pass ended. */
    val verifiedAt: Instant? = null,
    /** When the report was stored and the batch COMPLETED. */
    val completedAt: Instant? = null,
    /** Present once COMPLETED; the parts come from `GET …/report`. */
    val report: ImportReportSummary? = null,
    /** When the batch was CLOSED or ABANDONED, and why. */
    val closedAt: Instant? = null,
    val closeReason: String? = null,
    /** When its staged objects were purged (after it was closed or abandoned). */
    val purgedAt: Instant? = null,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val allowedActions: List<BatchAction>,
)

/** A batch in the list. */
data class ImportBatchSummary(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val id: UUID,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val name: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val sourceCode: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val state: BatchState,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val createdBy: UUID,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val createdAt: Instant,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val updatedAt: Instant,
)

data class ImportBatchPage(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val batches: List<ImportBatchSummary>,
    /** Pass as `cursor` for the next page; absent on the last. */
    val next: String? = null,
)

/** A registered original (design E06 §5.1). */
data class IntakeEntryView(
    /** The `owner` of its upload (`POST /files?purpose=imports.intake&owner={intakeId}`). */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val intakeId: UUID,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val path: String,
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val state: IntakeState,
    /** As registered; the received size once stored. */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val size: Long,
    val sha256: String? = null,
    val mediaType: String? = null,
    /** The pipeline's reason code for a REFUSED original (MALWARE, TYPE_NOT_ALLOWED, ENCRYPTED, ...). */
    val refusal: String? = null,
    /** The scanner's signature, when it named one. */
    val refusalSignature: String? = null,
    val receivedAt: Instant? = null,
)

data class IntakePage(
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val files: List<IntakeEntryView>,
    /** Pass as `cursor` for the next page; absent on the last. */
    val next: String? = null,
)

/** Builds the views; inside a tenant transaction. */
@Component
class ImportViews(
    private val entries: ImportFileStore,
    private val files: Files,
    private val tenantDsl: TenantDsl,
    private val progress: RunProgress,
    private val json: JsonMapper,
) {
    fun of(batch: ImportBatch): ImportBatchView {
        val counts = entries.counts(batch.id)

        fun count(state: IntakeState) = counts[state] ?: IntakeCount(0, 0)
        val problems = problems(batch.id)
        val rows = rows(batch.id)
        val versions = batch.sources.associateWith { files.version(it.versionId) }
        return ImportBatchView(
            id = batch.id,
            name = batch.settings.name,
            sourceCode = batch.settings.sourceCode,
            timeZone = batch.settings.timeZone.id,
            defaultClassificationId = batch.settings.defaultClassificationId,
            numberPrefix = batch.settings.numberPrefix,
            state = batch.state,
            version = batch.version,
            createdBy = batch.createdBy,
            createdAt = batch.createdAt,
            updatedAt = batch.updatedAt,
            sourceKind = batch.sourceKind,
            sources =
                versions.mapNotNull { (source, version) ->
                    version?.let { ImportSourceView(source.table, source.fileId, it.id, it.name, it.sizeBytes, it.sha256, it.uploadedAt) }
                },
            originals =
                ImportOriginalsView(
                    expected = count(IntakeState.EXPECTED).files,
                    received = count(IntakeState.RECEIVED).files,
                    refused = count(IntakeState.REFUSED).files,
                    purged = count(IntakeState.PURGED).files,
                    expectedBytes = count(IntakeState.EXPECTED).bytes,
                    receivedBytes = count(IntakeState.RECEIVED).bytes,
                ),
            problems = problems,
            rows = rows,
            dryRunAt = batch.dryRunAt,
            dryRunHash = batch.dryRunHash,
            maxRank = batch.maxRank,
            startedBy = batch.run.startedBy,
            startedAt = batch.run.startedAt,
            stepUpAt = batch.run.stepUpAt,
            finishedAt = batch.run.finishedAt,
            progress = progress.of(batch),
            verifiedAt = batch.end.verifiedAt,
            completedAt = batch.end.completedAt,
            report =
                batch.end.report?.let { json.readTree(it) }?.let {
                    ImportReportSummary(it["balanced"].asBoolean(), it["complete"].asBoolean(), it["valid"].asBoolean())
                },
            closedAt = batch.end.closedAt,
            closeReason = batch.end.closeReason,
            purgedAt = batch.end.purgedAt,
            allowedActions = actions(batch, problems, rows),
        )
    }

    /**
     * Every editing action while the batch is OPEN or VALIDATED, the dry run once its metadata is there, and the start of a
     * VALIDATED batch with READY rows and no batch problem; then stop while importing, and resume or finish once stopped.
     */
    private fun actions(
        batch: ImportBatch,
        problems: Map<String, Int>,
        rows: Map<String, Int>,
    ): List<BatchAction> {
        val startable =
            batch.state == BatchState.VALIDATED && (rows[RowResult.READY.name] ?: 0) > 0 && (problems[ProblemLevel.BATCH.name] ?: 0) == 0
        return when (batch.state) {
            BatchState.IMPORTING -> {
                listOf(BatchAction.STOP)
            }

            BatchState.STOPPED -> {
                listOf(BatchAction.RESUME, BatchAction.FINISH)
            }

            BatchState.COMPLETED -> {
                listOf(BatchAction.CLOSE)
            }

            else -> {
                EDITING.filter { batch.state.editable && (it != BatchAction.DRY_RUN || batch.sources.isNotEmpty()) } +
                    listOfNotNull(BatchAction.START.takeIf { startable }, BatchAction.ABANDON.takeIf { batch.state.editable })
            }
        }
    }

    fun summary(batch: ImportBatch) =
        ImportBatchSummary(
            batch.id,
            batch.settings.name,
            batch.settings.sourceCode,
            batch.state,
            batch.createdBy,
            batch.createdAt,
            batch.updatedAt,
        )

    fun entry(entry: IntakeEntry) =
        IntakeEntryView(
            intakeId = entry.id,
            path = entry.path,
            state = entry.state,
            size = entry.sizeBytes,
            sha256 = entry.sha256,
            mediaType = entry.mediaType,
            refusal = entry.refusal,
            refusalSignature = entry.refusalSignature,
            receivedAt = entry.receivedAt,
        )

    /** Problems of the last dry run and of the import by level. */
    fun problems(batchId: UUID): Map<String, Int> =
        tenantDsl
            .current()
            .select(IMPORTS_PROBLEM.LEVEL, DSL.count())
            .from(IMPORTS_PROBLEM)
            .where(IMPORTS_PROBLEM.BATCH_ID.eq(batchId))
            .groupBy(IMPORTS_PROBLEM.LEVEL)
            .fetch { r -> r.value1().orEmpty() to (r.value2() ?: 0) }
            .toMap()

    /** Memos rows by result. */
    fun rows(batchId: UUID): Map<String, Int> =
        tenantDsl
            .current()
            .select(IMPORTS_ROW.RESULT, DSL.count())
            .from(IMPORTS_ROW)
            .where(IMPORTS_ROW.BATCH_ID.eq(batchId))
            .groupBy(IMPORTS_ROW.RESULT)
            .fetch { r -> r.value1().orEmpty() to (r.value2() ?: 0) }
            .toMap()

    private companion object {
        /** The actions of a batch before its import starts. */
        val EDITING =
            listOf(BatchAction.EDIT, BatchAction.ATTACH_SOURCE, BatchAction.REGISTER_ORIGINALS, BatchAction.MAP, BatchAction.DRY_RUN)
    }
}
