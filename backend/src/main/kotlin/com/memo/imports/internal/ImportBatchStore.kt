package com.memo.imports.internal

import com.memo.common.tenant.TenantDsl
import com.memo.jooq.tables.records.ImportsBatchRecord
import com.memo.jooq.tables.references.IMPORTS_BATCH
import org.jooq.Field
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.HexFormat
import java.util.UUID

/** A batch's lifecycle (design E06 §4.1). */
enum class BatchState {
    OPEN,
    DRY_RUN,
    VALIDATED,
    IMPORTING,
    STOPPING,
    STOPPED,
    VERIFYING,
    COMPLETED,
    CLOSED,
    ABANDONED,
    ;

    /** Sources, originals, mappings and settings may change only now; a change moves VALIDATED back to OPEN. */
    val editable: Boolean get() = this == OPEN || this == VALIDATED

    /** Counts toward the tenant's open batches (all but CLOSED and ABANDONED). */
    val counted: Boolean get() = this != CLOSED && this != ABANDONED
}

/** How the metadata came: one workbook, or CSV files. */
enum class SourceKind { XLSX, CSV }

/** What a staged source is: the workbook, or the CSV file of one table. The `owner` of a source upload ends in it. */
enum class SourceTable {
    WORKBOOK,
    MEMOS,
    FILES,
    APPROVALS,
    ;

    val kind: SourceKind get() = if (this == WORKBOOK) SourceKind.XLSX else SourceKind.CSV
}

/** A batch's settings, as given at creation or changed while it is editable. */
data class BatchSettings(
    val name: String,
    val sourceCode: String,
    val timeZone: ZoneId,
    val defaultClassificationId: UUID?,
    val numberPrefix: String?,
)

/** A staged source: the file of purpose `imports.source` and the version the batch uses. */
data class BatchSource(
    val table: SourceTable,
    val fileId: UUID,
    val versionId: UUID,
)

data class ImportBatch(
    val id: UUID,
    val settings: BatchSettings,
    val state: BatchState,
    val version: Long,
    val createdBy: UUID,
    val createdAt: Instant,
    val updatedAt: Instant,
    val sourceKind: SourceKind?,
    val sources: List<BatchSource>,
    val dryRunAt: Instant?,
    val maxRank: Int?,
    /** SHA-256 (hex) of what the last dry run read: sources, received originals, mappings and settings (design E06 §5.2). */
    val dryRunHash: String? = null,
    /** The import (design E06 §5.3): who started it, when, and when their step-up was proven. */
    val run: BatchRun = BatchRun(),
)

/** A batch's import as far as it went (design E06 §5.3): all null until it starts. */
data class BatchRun(
    val startedBy: UUID? = null,
    val startedAt: Instant? = null,
    val stepUpAt: Instant? = null,
    val finishedAt: Instant? = null,
    /** Since when the job has waited for the `memo.legacy` renditions to catch up; null while it is not waiting. */
    val throttledAt: Instant? = null,
)

/** How a dry run ended, as the batch records it. */
data class DryRunOutcome(
    val state: BatchState,
    /** What the validation read (lower-case hex SHA-256). */
    val dryRunHash: String,
    /** The same, read again when the dry run ended; different only if something changed meanwhile. */
    val inputHash: String,
    /** The totals as JSON (rows by result, problems by level, Files rows by match). */
    val totals: String,
    val maxRank: Int?,
)

/** `imports_batch`, inside a tenant transaction. Changes are guarded on the state or the version (AGENTS §4). */
@Repository
class ImportBatchStore(
    private val tenantDsl: TenantDsl,
    private val clock: Clock,
) {
    fun insert(
        id: UUID,
        settings: BatchSettings,
        createdBy: UUID,
    ) {
        val now = now()
        tenantDsl
            .current()
            .insertInto(IMPORTS_BATCH)
            .set(IMPORTS_BATCH.ID, id)
            .set(IMPORTS_BATCH.TENANT_ID, tenantDsl.tenant().value)
            .set(IMPORTS_BATCH.NAME, settings.name)
            .set(IMPORTS_BATCH.SOURCE_CODE, settings.sourceCode)
            .set(IMPORTS_BATCH.TIME_ZONE, settings.timeZone.id)
            .set(IMPORTS_BATCH.DEFAULT_CLASSIFICATION_ID, settings.defaultClassificationId)
            .set(IMPORTS_BATCH.NUMBER_PREFIX, settings.numberPrefix)
            .set(IMPORTS_BATCH.STATE, BatchState.OPEN.name)
            .set(IMPORTS_BATCH.CREATED_BY, createdBy)
            .set(IMPORTS_BATCH.CREATED_AT, now)
            .set(IMPORTS_BATCH.UPDATED_AT, now)
            .execute()
    }

    fun find(id: UUID): ImportBatch? =
        tenantDsl
            .current()
            .selectFrom(IMPORTS_BATCH)
            .where(IMPORTS_BATCH.ID.eq(id))
            .fetchOne(::batch)

    /** [find] with the row locked for the rest of the transaction (registering paths, binding uploads). */
    fun lock(id: UUID): ImportBatch? =
        tenantDsl
            .current()
            .selectFrom(IMPORTS_BATCH)
            .where(IMPORTS_BATCH.ID.eq(id))
            .forUpdate()
            .fetchOne(::batch)

    /** Batches newest first, after the keyset position [after] (`created_at`, id) when given. */
    fun page(
        after: Pair<Instant, UUID>?,
        limit: Int,
    ): List<ImportBatch> =
        tenantDsl
            .current()
            .selectFrom(IMPORTS_BATCH)
            .where(
                after?.let { (at, id) ->
                    DSL.row(IMPORTS_BATCH.CREATED_AT, IMPORTS_BATCH.ID).lt(at.atOffset(ZoneOffset.UTC), id)
                } ?: DSL.noCondition(),
            ).orderBy(IMPORTS_BATCH.CREATED_AT.desc(), IMPORTS_BATCH.ID.desc())
            .limit(limit)
            .fetch(::batch)

    /** Batches that count toward the tenant's limit (not CLOSED or ABANDONED). */
    fun countCounted(): Int =
        tenantDsl.current().fetchCount(
            IMPORTS_BATCH,
            IMPORTS_BATCH.STATE.notIn(BatchState.entries.filterNot { it.counted }.map { it.name }),
        )

    /** New settings, guarded on [version] and an editable state; a VALIDATED batch goes back to OPEN. */
    fun updateSettings(
        id: UUID,
        version: Long,
        settings: BatchSettings,
    ): Boolean =
        tenantDsl
            .current()
            .update(IMPORTS_BATCH)
            .set(IMPORTS_BATCH.NAME, settings.name)
            .set(IMPORTS_BATCH.SOURCE_CODE, settings.sourceCode)
            .set(IMPORTS_BATCH.TIME_ZONE, settings.timeZone.id)
            .set(IMPORTS_BATCH.DEFAULT_CLASSIFICATION_ID, settings.defaultClassificationId)
            .set(IMPORTS_BATCH.NUMBER_PREFIX, settings.numberPrefix)
            .set(IMPORTS_BATCH.STATE, BatchState.OPEN.name)
            .set(IMPORTS_BATCH.VERSION, IMPORTS_BATCH.VERSION.plus(1))
            .set(IMPORTS_BATCH.UPDATED_AT, now())
            .where(IMPORTS_BATCH.ID.eq(id), IMPORTS_BATCH.VERSION.eq(version), IMPORTS_BATCH.STATE.`in`(EDITABLE))
            .execute() == 1

    /**
     * Binds [source] in place of the table's earlier source; a workbook clears the CSV files and the reverse. Guarded on
     * an editable state; a VALIDATED batch goes back to OPEN. False when the batch is not editable.
     */
    fun attach(
        id: UUID,
        source: BatchSource,
    ): Boolean {
        val columns = SOURCE_COLUMNS.getValue(source.table)
        val cleared = SOURCE_COLUMNS.filterKeys { it != source.table && it.kind != source.table.kind }.values
        var update =
            tenantDsl
                .current()
                .update(IMPORTS_BATCH)
                .set(columns.first, source.fileId)
                .set(columns.second, source.versionId)
                .set(IMPORTS_BATCH.SOURCE_KIND, source.table.kind.name)
        cleared.forEach { (file, version) -> update = update.setNull(file).setNull(version) }
        return update
            .set(IMPORTS_BATCH.STATE, BatchState.OPEN.name)
            .set(IMPORTS_BATCH.VERSION, IMPORTS_BATCH.VERSION.plus(1))
            .set(IMPORTS_BATCH.UPDATED_AT, now())
            .where(IMPORTS_BATCH.ID.eq(id), IMPORTS_BATCH.STATE.`in`(EDITABLE))
            .execute() == 1
    }

    /** VALIDATED → OPEN after an original changed (nothing else of the batch changes; no version bump otherwise). */
    fun reopen(id: UUID): Boolean =
        tenantDsl
            .current()
            .update(IMPORTS_BATCH)
            .set(IMPORTS_BATCH.STATE, BatchState.OPEN.name)
            .set(IMPORTS_BATCH.VERSION, IMPORTS_BATCH.VERSION.plus(1))
            .set(IMPORTS_BATCH.UPDATED_AT, now())
            .where(IMPORTS_BATCH.ID.eq(id), IMPORTS_BATCH.STATE.eq(BatchState.VALIDATED.name))
            .execute() == 1

    private fun batch(r: ImportsBatchRecord): ImportBatch =
        ImportBatch(
            id = r.id,
            settings =
                BatchSettings(
                    name = r.name,
                    sourceCode = r.sourceCode,
                    timeZone = ZoneId.of(r.timeZone),
                    defaultClassificationId = r.defaultClassificationId,
                    numberPrefix = r.numberPrefix,
                ),
            state = BatchState.valueOf(r.state ?: BatchState.OPEN.name),
            version = r.version ?: 0,
            createdBy = r.createdBy,
            createdAt = r.createdAt.toInstant(),
            updatedAt = r.updatedAt.toInstant(),
            sourceKind = r.sourceKind?.let(SourceKind::valueOf),
            sources = sources(r),
            dryRunAt = r.dryRunAt?.toInstant(),
            maxRank = r.maxRank,
            dryRunHash = r.dryRunHash?.let { HexFormat.of().formatHex(it) },
            run =
                BatchRun(
                    startedBy = r.startedBy,
                    startedAt = r.startedAt?.toInstant(),
                    stepUpAt = r.stepUpAt?.toInstant(),
                    finishedAt = r.finishedAt?.toInstant(),
                    throttledAt = r.throttledAt?.toInstant(),
                ),
        )

    private fun sources(r: ImportsBatchRecord): List<BatchSource> =
        SOURCE_COLUMNS.mapNotNull { (table, columns) ->
            val file = r.get(columns.first)
            val version = r.get(columns.second)
            if (file != null && version != null) BatchSource(table, file, version) else null
        }

    private fun now(): OffsetDateTime = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC)

    private companion object {
        val EDITABLE = BatchState.entries.filter { it.editable }.map { it.name }

        val SOURCE_COLUMNS: Map<SourceTable, Pair<Field<UUID?>, Field<UUID?>>> =
            mapOf(
                SourceTable.WORKBOOK to (IMPORTS_BATCH.WORKBOOK_FILE_ID to IMPORTS_BATCH.WORKBOOK_VERSION_ID),
                SourceTable.MEMOS to (IMPORTS_BATCH.MEMOS_FILE_ID to IMPORTS_BATCH.MEMOS_VERSION_ID),
                SourceTable.FILES to (IMPORTS_BATCH.FILES_FILE_ID to IMPORTS_BATCH.FILES_VERSION_ID),
                SourceTable.APPROVALS to (IMPORTS_BATCH.APPROVALS_FILE_ID to IMPORTS_BATCH.APPROVALS_VERSION_ID),
            )
    }
}
