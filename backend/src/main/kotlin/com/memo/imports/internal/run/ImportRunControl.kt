package com.memo.imports.internal.run

import com.github.kagkarlsson.scheduler.SchedulerClient
import com.github.kagkarlsson.scheduler.task.helper.CustomTask
import com.memo.audit.AuditLog
import com.memo.common.authz.Policy
import com.memo.common.concurrency.ConcurrentModificationException
import com.memo.common.tenant.TenantDsl
import com.memo.common.web.FieldProblem
import com.memo.common.web.RefusedException
import com.memo.common.web.refuseIf
import com.memo.imports.internal.BatchState
import com.memo.imports.internal.ImportBatch
import com.memo.imports.internal.ImportBatchNotFoundException
import com.memo.imports.internal.ImportBatchStore
import com.memo.imports.internal.ImportBatchTransitions
import com.memo.imports.internal.ImportBatches
import com.memo.imports.internal.ImportRejectedException
import com.memo.imports.internal.ImportViews
import com.memo.imports.internal.dryrun.InputHash
import com.memo.imports.internal.dryrun.ProblemLevel
import com.memo.imports.internal.report.ImportJobs
import org.jooq.exception.IntegrityConstraintViolationException
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.dao.DuplicateKeyException
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.UUID

/**
 * Starting, stopping, resuming and finishing an import (ADR-0055, design E06 §4.1, §5.3, §10), as holders of
 * `memo.import` ask; the controller checks the permission and, for start and resume, the step-up. Each is a guarded
 * transition of the locked batch, audited, and start and resume schedule the `imports-run` job. One import runs per tenant
 * (`imports_batch_one_running`). Inside a tenant transaction.
 */
@Service
class ImportRunControl(
    private val batches: ImportBatchStore,
    private val transitions: ImportBatchTransitions,
    private val rows: RunRows,
    private val inputs: InputHash,
    private val views: ImportViews,
    private val policy: Policy,
    private val audit: AuditLog,
    private val tenantDsl: TenantDsl,
    private val scheduler: ObjectProvider<SchedulerClient>,
    @param:Qualifier("importsRunTask") private val task: ObjectProvider<CustomTask<ImportRunTask>>,
    private val endJobs: ImportJobs,
) {
    /** False only while a test runs the job itself (test contexts share the scheduler table). */
    @Volatile var jobs: Boolean = true

    /**
     * VALIDATED → IMPORTING at [version] when the batch has no batch problem, [expectedRows] READY rows (the count the
     * screen showed), an importer cleared to every level it writes, and nothing changed since its dry run; each READY row
     * gets its memo id. [stepUpAt] is when the importer's step-up was proven.
     */
    fun start(
        batchId: UUID,
        version: Long,
        expectedRows: Int,
        stepUpAt: Instant?,
    ): ImportBatch {
        val batch = batches.lock(batchId) ?: throw ImportBatchNotFoundException()
        refuseIf(batch.state != BatchState.VALIDATED, "WRONG_STATE") { "Only a validated batch can be imported; run the dry run." }
        if (batch.version != version) throw ConcurrentModificationException("import batch $batchId", version)
        val ready = rows.readyCount(batchId)
        rejectIf((views.problems(batchId)[ProblemLevel.BATCH.name] ?: 0) > 0, "problems", "BATCH_PROBLEMS", "Fix the batch problems first.")
        rejectIf(ready == 0, "expectedRows", "NOTHING_TO_IMPORT", "No row is ready to import.")
        refuseIf(expectedRows != ready, "ROWS_CHANGED") { "$ready rows are ready now; review the batch again." }
        cleared(batch)
        refuseIf(inputs.of(batch) != batch.dryRunHash, "DRY_RUN_STALE") { "Something changed since the dry run; run it again." }
        running(batchId) { transitions.start(batchId, version, ImportBatches.user(), stepUpAt) }
        rows.reserve(batchId)
        audit.record(
            "import.batch.started",
            ImportBatches.OBJECT_TYPE,
            batchId.toString(),
            before = mapOf("state" to batch.state.name),
            after =
                mapOf(
                    "state" to BatchState.IMPORTING.name,
                    "stepUpAt" to stepUpAt?.toString(),
                    "rows" to ready,
                    "inputHash" to batch.dryRunHash,
                    "maxRank" to batch.maxRank,
                ),
        )
        schedule(batchId)
        return checkNotNull(batches.find(batchId))
    }

    /** IMPORTING → STOPPING; the job stops between two chunks (STOPPED). A batch already STOPPING is answered as it is. */
    fun stop(batchId: UUID): ImportBatch {
        val batch = batches.lock(batchId) ?: throw ImportBatchNotFoundException()
        if (batch.state == BatchState.STOPPING) return batch
        refuseIf(batch.state != BatchState.IMPORTING, "WRONG_STATE") { "Only a running import can be stopped." }
        check(transitions.move(batchId, listOf(BatchState.IMPORTING), BatchState.STOPPING)) { "batch $batchId changed state" }
        audited("import.batch.stopped", batchId, BatchState.IMPORTING, BatchState.STOPPING)
        // The job turns it STOPPED; scheduled again in case it is not there to do so.
        schedule(batchId)
        return checkNotNull(batches.find(batchId))
    }

    /** STOPPED → IMPORTING, with a fresh step-up proven at [stepUpAt], for an importer cleared to the batch's levels. */
    fun resume(
        batchId: UUID,
        stepUpAt: Instant?,
    ): ImportBatch {
        val batch = batches.lock(batchId) ?: throw ImportBatchNotFoundException()
        refuseIf(batch.state != BatchState.STOPPED, "WRONG_STATE") { "Only a stopped import can be resumed." }
        cleared(batch)
        running(batchId) { transitions.move(batchId, listOf(BatchState.STOPPED), BatchState.IMPORTING) }
        audited("import.batch.resumed", batchId, BatchState.STOPPED, BatchState.IMPORTING, mapOf("stepUpAt" to stepUpAt?.toString()))
        schedule(batchId)
        return checkNotNull(batches.find(batchId))
    }

    /**
     * STOPPED → VERIFYING: the rows the import did not reach are NOT_STARTED, and the batch goes on to its verification and
     * report (T8's `imports-verify` takes it from VERIFYING).
     */
    fun finish(batchId: UUID): ImportBatch {
        val batch = batches.lock(batchId) ?: throw ImportBatchNotFoundException()
        refuseIf(batch.state != BatchState.STOPPED, "WRONG_STATE") { "Only a stopped import can be finished early." }
        running(batchId) { transitions.finish(batchId, BatchState.STOPPED) }
        val notStarted = rows.notStarted(batchId)
        audited(
            "import.batch.finished",
            batchId,
            BatchState.STOPPED,
            BatchState.VERIFYING,
            mapOf("rows" to views.rows(batchId), "notStarted" to notStarted),
        )
        endJobs.verify(batchId)
        return checkNotNull(batches.find(batchId))
    }

    /** The importer must be cleared to the highest level the batch writes (design E06 §5.2, `IMPORTER_NOT_CLEARED`). */
    private fun cleared(batch: ImportBatch) =
        rejectIf(
            policy.clearanceRank() < (batch.maxRank ?: 0),
            "clearance",
            "IMPORTER_NOT_CLEARED",
            "Your clearance is below the highest level in this batch.",
        )

    /**
     * Enters a state of one running import per tenant through [move]: refused with `ANOTHER_IMPORT_RUNNING` when another
     * batch runs, also when it started meanwhile (the unique index refuses the update then).
     */
    private fun running(
        batchId: UUID,
        move: () -> Boolean,
    ) {
        refuseIf(transitions.anotherRunning(batchId), ANOTHER) { RUNNING_MESSAGE }
        val moved =
            try {
                move()
            } catch (e: DuplicateKeyException) {
                throw RefusedException(ANOTHER, RUNNING_MESSAGE).apply { initCause(e) }
            } catch (e: IntegrityConstraintViolationException) {
                throw RefusedException(ANOTHER, RUNNING_MESSAGE).apply { initCause(e) }
            }
        check(moved) { "batch $batchId changed state" }
    }

    private fun audited(
        action: String,
        batchId: UUID,
        from: BatchState,
        to: BatchState,
        more: Map<String, Any?> = emptyMap(),
    ) = audit.record(
        action,
        ImportBatches.OBJECT_TYPE,
        batchId.toString(),
        before = mapOf("state" to from.name),
        after =
            mapOf("state" to to.name) + more,
    )

    /** Schedules the job for [batchId] unless it is there already (it reschedules itself while there is work). */
    private fun schedule(batchId: UUID) {
        val client = scheduler.ifAvailable.takeIf { jobs }
        val run = task.ifAvailable
        if (client != null && run != null) {
            client.scheduleIfNotExists(run.schedulableInstance(batchId.toString(), ImportRunTask(tenantDsl.tenant().value, batchId)))
        }
    }

    private companion object {
        const val ANOTHER = "ANOTHER_IMPORT_RUNNING"
        const val RUNNING_MESSAGE = "Another batch is being imported in this organisation; wait until it is verified."

        /** A 422 with one field and code (design E06 §10: `NOTHING_TO_IMPORT`, `BATCH_PROBLEMS`, `IMPORTER_NOT_CLEARED`). */
        fun rejectIf(
            condition: Boolean,
            field: String,
            code: String,
            message: String,
        ) {
            if (condition) throw ImportRejectedException(listOf(FieldProblem(field, code, message)))
        }
    }
}
