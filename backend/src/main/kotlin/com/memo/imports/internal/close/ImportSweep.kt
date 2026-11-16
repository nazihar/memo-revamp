package com.memo.imports.internal.close

import com.memo.common.context.ActorContext
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantTransactions
import com.memo.imports.internal.BatchState
import com.memo.imports.internal.ImportBatch
import com.memo.imports.internal.ImportBatchStore
import com.memo.imports.internal.ImportBatchTransitions
import com.memo.imports.internal.ImportRecordPurpose
import com.memo.imports.internal.StagedPurges
import com.memo.imports.internal.dryrun.Finding
import com.memo.imports.internal.dryrun.FindingStore
import com.memo.imports.internal.dryrun.ProblemLevel
import com.memo.imports.internal.dryrun.ProblemSheet
import com.memo.imports.internal.dryrun.ProblemStage
import com.memo.imports.internal.report.ImportEndLimits
import com.memo.imports.internal.report.ImportJobs
import com.memo.imports.internal.report.VerifyReads
import com.memo.imports.internal.run.ImportRunLimits
import com.memo.imports.internal.run.RunRows
import com.memo.memo.LegacyMemos
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant
import java.util.UUID

/** What one sweep of a tenant did (design E06 §15), for its log line and for tests. */
data class SweepResult(
    /** Batches whose import job was scheduled again (IMPORTING or STOPPING, no activity for 15 minutes). */
    val restarted: List<UUID> = emptyList(),
    /** VERIFYING batches whose verification or report was scheduled again. */
    val resumed: List<UUID> = emptyList(),
    /** Rows held IMPORTING for 15 minutes by a job that died, returned to READY (or FAILED after their last attempt). */
    val staleRows: Int = 0,
    /** Original versions of imported memos whose lock was lengthened to their type's years. */
    val retained: Int = 0,
    /** Replaced staged versions purged. */
    val superseded: Int = 0,
    /** Batches closed 30 days after completion, or abandoned after 30 idle days. */
    val ended: List<UUID> = emptyList(),
    /** CLOSED or ABANDONED batches whose purge was scheduled (again). */
    val purging: List<UUID> = emptyList(),
) {
    operator fun plus(o: SweepResult) =
        SweepResult(
            restarted + o.restarted,
            resumed + o.resumed,
            staleRows + o.staleRows,
            retained + o.retained,
            superseded + o.superseded,
            ended + o.ended,
            purging + o.purging,
        )
}

/**
 * The hourly `imports-sweep` of one tenant (ADR-0055, design E06 §13, §15), as `System("legacy-import")`, outside a
 * transaction. For each batch not yet closed and purged:
 * - IMPORTING or STOPPING: rows a dead job held for 15 minutes go back to READY (FAILED `IMPORT_ERROR` after their last
 *   attempt), and the import job is scheduled again when nothing moved for 15 minutes (a no-op while its execution exists);
 * - STOPPED: the same for held rows, and the imported memos' originals are kept for their type's years again (T7's
 *   retention after a commit is repeated, it is idempotent; verification repeats it for every finished batch);
 * - VERIFYING with nothing moving: the verification is scheduled again, or the report once verified;
 * - COMPLETED for 30 days: CLOSED; OPEN or VALIDATED without change for 30 days: ABANDONED (both then purge);
 * - CLOSED or ABANDONED and not purged yet: the purge is scheduled again;
 * - and replaced staged versions of every other batch are purged.
 */
@Service
class ImportSweep(
    private val batches: ImportBatchStore,
    private val reads: SweepReads,
    private val verified: VerifyReads,
    private val rows: RunRows,
    private val findings: FindingStore,
    private val closing: ImportClosing,
    private val staged: StagedPurges,
    private val legacy: LegacyMemos,
    private val jobs: ImportJobs,
    private val transactions: TenantTransactions,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun sweep(): SweepResult =
        ActorContext.asSystem(ImportRecordPurpose.ACTOR) {
            val now = Instant.now(clock)
            val ids = read { reads.sweepable() }
            ids
                .mapNotNull { id -> read { batches.find(id) } }
                .fold(SweepResult()) { result, batch -> result + one(batch, now) }
                .also { if (it != SweepResult()) log.info("Import sweep of tenant {}: {}", TenantContext.current(), it) }
        }

    private fun one(
        batch: ImportBatch,
        now: Instant,
    ): SweepResult {
        val quiet = now.minus(ImportRunLimits.STALE)
        val ended = batch.state in ImportBatchTransitions.ENDED
        val found =
            when (batch.state) {
                BatchState.IMPORTING, BatchState.STOPPING -> {
                    restart(batch, quiet) + stale(batch, quiet)
                }

                BatchState.STOPPED -> {
                    stale(batch, quiet) + SweepResult(retained = retain(batch))
                }

                BatchState.VERIFYING -> {
                    resume(batch, quiet)
                }

                BatchState.COMPLETED -> {
                    expire(batch, batch.end.completedAt?.isBefore(now.minus(ImportEndLimits.KEEP_STAGED)) == true)
                }

                BatchState.OPEN, BatchState.VALIDATED -> {
                    expire(batch, read { reads.lastActivity(batch.id) }.isBefore(now.minus(ImportEndLimits.IDLE)))
                }

                BatchState.CLOSED, BatchState.ABANDONED -> {
                    jobs.purge(batch.id)
                    SweepResult(purging = listOf(batch.id))
                }

                BatchState.DRY_RUN -> {
                    SweepResult()
                }
            }
        return if (ended) found else found + SweepResult(superseded = superseded(batch.id))
    }

    /** Rows held IMPORTING since before [quiet]: back to READY, or FAILED `IMPORT_ERROR` after their last attempt. */
    private fun stale(
        batch: ImportBatch,
        quiet: Instant,
    ): SweepResult =
        transactions.inTenant(TenantContext.current()) {
            val held = reads.staleRows(batch.id, quiet)
            if (held > 0) {
                rows.recover(batch.id, ImportRunLimits.STALE, ImportRunLimits.ATTEMPTS).forEach { rowId ->
                    findings.add(
                        batch.id,
                        rowId,
                        listOf(Finding(ProblemLevel.ERROR, "IMPORT_ERROR", ProblemSheet.MEMOS)),
                        ProblemStage.IMPORT,
                    )
                }
            }
            SweepResult(staleRows = held)
        }

    /** The import job again, when nothing of the batch moved since [quiet] (its execution is gone, or this is a no-op). */
    private fun restart(
        batch: ImportBatch,
        quiet: Instant,
    ): SweepResult {
        if (!read { reads.lastRunChange(batch.id) }.isBefore(quiet)) return SweepResult()
        jobs.run(batch.id)
        return SweepResult(restarted = listOf(batch.id))
    }

    /** The verification again, or the report once verified, when the batch has not changed since [quiet]. */
    private fun resume(
        batch: ImportBatch,
        quiet: Instant,
    ): SweepResult {
        if (!batch.updatedAt.isBefore(quiet)) return SweepResult()
        if (batch.end.verifiedAt == null) jobs.verify(batch.id) else jobs.report(batch.id)
        return SweepResult(resumed = listOf(batch.id))
    }

    private fun expire(
        batch: ImportBatch,
        due: Boolean,
    ): SweepResult {
        val ended = due && transactions.inTenant(TenantContext.current()) { closing.expire(batch) }
        return SweepResult(ended = listOfNotNull(batch.id.takeIf { ended }))
    }

    /** The originals of every memo the stopped batch imported, kept for their type's years (only ever lengthened). */
    private fun retain(batch: ImportBatch): Int {
        var retained = 0
        var after = 0
        do {
            val page = read { verified.importedAfter(batch.id, after, ImportEndLimits.PAGE) }
            retained += page.sumOf { legacy.retainOriginals(it.memoId) }
            after = page.lastOrNull()?.rowNumber ?: after
        } while (page.size == ImportEndLimits.PAGE)
        return retained
    }

    private fun superseded(batchId: UUID): Int {
        var purged = 0
        do {
            val page = staged.purgeSuperseded(batchId, ImportEndLimits.PURGE_PAGE)
            purged += page
        } while (page == ImportEndLimits.PURGE_PAGE)
        return purged
    }

    private fun <T> read(block: () -> T): T = transactions.readInTenant(TenantContext.current(), block)
}
