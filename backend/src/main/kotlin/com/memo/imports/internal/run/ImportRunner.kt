package com.memo.imports.internal.run

import com.github.kagkarlsson.scheduler.task.helper.CustomTask
import com.memo.audit.AuditLog
import com.memo.common.authz.Clearances
import com.memo.common.context.ActorContext
import com.memo.common.jobs.Jobs
import com.memo.common.jobs.TenantScopedPayload
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantTransactions
import com.memo.files.FileRenditions
import com.memo.imports.internal.BatchState
import com.memo.imports.internal.ImportBatch
import com.memo.imports.internal.ImportBatchStore
import com.memo.imports.internal.ImportBatchTransitions
import com.memo.imports.internal.ImportBatches
import com.memo.imports.internal.ImportMappingStore
import com.memo.imports.internal.ImportRecordPurpose
import com.memo.imports.internal.ImportViews
import com.memo.imports.internal.dryrun.Finding
import com.memo.imports.internal.dryrun.FindingStore
import com.memo.imports.internal.dryrun.ProblemLevel
import com.memo.imports.internal.dryrun.ProblemSheet
import com.memo.imports.internal.dryrun.ProblemStage
import com.memo.imports.internal.dryrun.StageFindings
import com.memo.imports.internal.dryrun.ValidationContext
import com.memo.imports.internal.report.ImportJobs
import com.memo.memo.LegacyMemos
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Service
import java.time.Duration
import java.util.UUID

/** Task data of one batch's import. */
data class ImportRunTask(
    override val tenantId: UUID,
    val batchId: UUID,
) : TenantScopedPayload

/**
 * The `imports-run` job (ADR-0055, design E06 §5.3, §15): one step per execution, as `System("legacy-import")`. A step
 * returns rows held too long by a job that died to READY, turns a STOPPING batch STOPPED, waits while the tenant's
 * `memo.legacy` renditions are behind, imports the next [chunkRows] READY rows one by one ([RowImport]), and moves the batch
 * to VERIFYING once no row is left. The answer is the delay before the next step, or null when the job is done.
 */
@Service
class ImportRunner(
    private val batches: ImportBatchStore,
    private val transitions: ImportBatchTransitions,
    private val rows: RunRows,
    private val rowImport: RowImport,
    private val mappings: ImportMappingStore,
    private val clearances: Clearances,
    private val legacy: LegacyMemos,
    private val renditions: FileRenditions,
    private val findings: FindingStore,
    private val views: ImportViews,
    private val audit: AuditLog,
    private val transactions: TenantTransactions,
    private val jobs: ImportJobs,
) {
    /** Rows per step (`imports.chunk-rows`). */
    @Volatile var chunkRows: Int = ImportRunLimits.CHUNK_ROWS

    /** Pending `memo.legacy` renditions at which the job waits (`imports.rendition-backlog`). */
    @Volatile var renditionBacklog: Int = ImportRunLimits.RENDITION_BACKLOG

    /** One step of [batchId]'s import, outside a transaction with the tenant bound. */
    fun step(batchId: UUID): Duration? =
        ActorContext.asSystem(ImportRecordPurpose.ACTOR) {
            val tenant = TenantContext.current()
            transactions.inTenant(tenant) { recover(batchId) }
            val batch = transactions.readInTenant(tenant) { batches.find(batchId) }
            when (batch?.state) {
                BatchState.STOPPING -> transactions.inTenant(tenant) { stopped(batchId) }
                BatchState.IMPORTING -> chunk(batch)
                else -> null
            }
        }

    /** Rows held for longer than [ImportRunLimits.STALE]: READY again, or FAILED `IMPORT_ERROR` after their last attempt. */
    private fun recover(batchId: UUID) {
        rows.recover(batchId, ImportRunLimits.STALE, ImportRunLimits.ATTEMPTS).forEach { rowId ->
            findings.add(batchId, rowId, listOf(Finding(ProblemLevel.ERROR, "IMPORT_ERROR", ProblemSheet.MEMOS)), ProblemStage.IMPORT)
        }
    }

    private fun chunk(batch: ImportBatch): Duration? {
        val tenant = TenantContext.current()
        val pending = transactions.readInTenant(tenant) { renditions.pendingCount(LegacyMemos.ORIGINAL_PURPOSE, renditionBacklog) }
        val throttled = pending >= renditionBacklog
        transactions.inTenant(tenant) { transitions.throttle(batch.id, throttled) }
        val next = if (throttled) emptyList() else transactions.readInTenant(tenant) { rows.next(batch.id, chunkRows) }
        return when {
            throttled -> {
                ImportRunLimits.THROTTLE_WAIT
            }

            next.isEmpty() -> {
                done(batch.id)
            }

            else -> {
                val ctx = transactions.readInTenant(tenant) { context(batch) }
                next.forEach { rowImport.run(ctx, it) }
                Duration.ZERO
            }
        }
    }

    /**
     * What the import checks a row against (as the dry run, design E06 §5.2): the batch, its mappings, the levels and the live
     * series, read again for each chunk. The cross-row facts are the dry run's (they cannot change once VALIDATED).
     */
    private fun context(batch: ImportBatch) =
        ValidationContext(
            batch = batch,
            mappings = mappings.all(batch.id),
            levels = clearances.levels(),
            series = legacy.series(),
            duplicateKeys = emptySet(),
            similarKeys = emptySet(),
            sharedRows = emptySet(),
            findings = StageFindings(),
        )

    /**
     * No READY row is left: VERIFYING once no row is held either (else wait for it), audited `import.batch.finished`, and
     * the verification scheduled in the same transaction.
     */
    private fun done(batchId: UUID): Duration? {
        val tenant = TenantContext.current()
        if (transactions.readInTenant(tenant) { rows.inFlight(batchId) } > 0) return ImportRunLimits.HELD_WAIT
        transactions.inTenant(tenant) {
            if (transitions.finish(batchId, BatchState.IMPORTING)) {
                audit.record(
                    "import.batch.finished",
                    ImportBatches.OBJECT_TYPE,
                    batchId.toString(),
                    before = mapOf("state" to BatchState.IMPORTING.name),
                    after = mapOf("state" to BatchState.VERIFYING.name, "rows" to views.rows(batchId)),
                )
                jobs.verify(batchId)
            }
        }
        return null
    }

    /** STOPPING → STOPPED between two chunks, audited `import.batch.stopped` with the rows so far. */
    private fun stopped(batchId: UUID): Duration? {
        if (transitions.move(batchId, listOf(BatchState.STOPPING), BatchState.STOPPED)) {
            transitions.throttle(batchId, false)
            audit.record(
                "import.batch.stopped",
                ImportBatches.OBJECT_TYPE,
                batchId.toString(),
                before = mapOf("state" to BatchState.STOPPING.name),
                after = mapOf("state" to BatchState.STOPPED.name, "rows" to views.rows(batchId)),
            )
        }
        return null
    }
}

/** The import's task (design E06 §15): instance `{batchId}`, one step per execution, rescheduled while there is work. */
@Configuration(proxyBeanMethods = false)
class ImportRunJobs {
    @Bean
    fun importsRunTask(
        jobs: Jobs,
        runner: ObjectProvider<ImportRunner>,
    ): CustomTask<ImportRunTask> =
        jobs.tenantStepTask(NAME, ImportRunTask::class.java, maxRetries = ImportRunLimits.STEP_RETRIES) { payload, _ ->
            runner.getObject().step(payload.batchId)
        }

    companion object {
        const val NAME = "imports-run"
    }
}
