package com.memo.imports.internal

import com.memo.common.tenant.TenantDsl
import com.memo.files.Files
import com.memo.jooq.tables.references.IMPORTS_PROBLEM
import com.memo.jooq.tables.references.IMPORTS_ROW
import io.swagger.v3.oas.annotations.media.Schema
import org.jooq.impl.DSL
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

/** What the batch's screen offers now (design E06 §10 `allowedActions`). The dry run and import add theirs (T6, T7). */
enum class BatchAction { EDIT, ATTACH_SOURCE, REGISTER_ORIGINALS }

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
    /** Problems of the last dry run by level (BATCH, ERROR, WARNING, FILE, INFO). */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val problems: Map<String, Int>,
    /** Memos rows by result (PENDING, READY, REJECTED, ...). */
    @field:Schema(requiredMode = Schema.RequiredMode.REQUIRED) val rows: Map<String, Int>,
    val dryRunAt: Instant?,
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
) {
    fun of(batch: ImportBatch): ImportBatchView {
        val counts = entries.counts(batch.id)

        fun count(state: IntakeState) = counts[state] ?: IntakeCount(0, 0)
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
            problems = problems(batch.id),
            rows = rows(batch.id),
            dryRunAt = batch.dryRunAt,
            allowedActions = if (batch.state.editable) BatchAction.entries else emptyList(),
        )
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

    private fun problems(batchId: UUID): Map<String, Int> =
        tenantDsl
            .current()
            .select(IMPORTS_PROBLEM.LEVEL, DSL.count())
            .from(IMPORTS_PROBLEM)
            .where(IMPORTS_PROBLEM.BATCH_ID.eq(batchId))
            .groupBy(IMPORTS_PROBLEM.LEVEL)
            .fetch { r -> r.value1().orEmpty() to (r.value2() ?: 0) }
            .toMap()

    private fun rows(batchId: UUID): Map<String, Int> =
        tenantDsl
            .current()
            .select(IMPORTS_ROW.RESULT, DSL.count())
            .from(IMPORTS_ROW)
            .where(IMPORTS_ROW.BATCH_ID.eq(batchId))
            .groupBy(IMPORTS_ROW.RESULT)
            .fetch { r -> r.value1().orEmpty() to (r.value2() ?: 0) }
            .toMap()
}
