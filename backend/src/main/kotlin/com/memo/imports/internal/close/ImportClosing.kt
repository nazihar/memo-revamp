package com.memo.imports.internal.close

import com.memo.audit.AuditLog
import com.memo.common.context.ActorContext
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantTransactions
import com.memo.common.web.FieldProblem
import com.memo.common.web.refuseIf
import com.memo.files.FilePurges
import com.memo.imports.internal.BatchState
import com.memo.imports.internal.ImportBatch
import com.memo.imports.internal.ImportBatchNotFoundException
import com.memo.imports.internal.ImportBatchStore
import com.memo.imports.internal.ImportBatchTransitions
import com.memo.imports.internal.ImportBatches
import com.memo.imports.internal.ImportRecordPurpose
import com.memo.imports.internal.ImportRejectedException
import com.memo.imports.internal.report.ImportEndLimits
import com.memo.imports.internal.report.ImportJobs
import org.springframework.stereotype.Service
import java.time.Duration
import java.util.UUID

/**
 * Closing and abandoning batches (ADR-0055, design E06 §4.1, §10, §12). A COMPLETED batch is CLOSED by a holder of
 * `memo.import` with a reason, or by the sweep 30 days after completion; an OPEN or VALIDATED batch is ABANDONED by a
 * holder, or by the sweep after 30 idle days, and its rows the import never reached are NOT_STARTED. Either way the
 * batch's staged objects (sources and originals in `imports.source` and `imports.intake`, replaced copies included) are
 * then purged by the `imports-purge` job through the files service's purge: the objects leave the `work` bucket, the
 * rows and their hashes stay (the chain of custody). Closing and abandoning run inside a tenant transaction; the purge
 * outside one.
 */
@Service
class ImportClosing(
    private val batches: ImportBatchStore,
    private val transitions: ImportBatchTransitions,
    private val store: ClosingStore,
    private val purges: FilePurges,
    private val audit: AuditLog,
    private val jobs: ImportJobs,
    private val transactions: TenantTransactions,
) {
    /** COMPLETED → CLOSED with [reason] (1 to 500 characters), then its staged objects are purged. */
    fun close(
        batchId: UUID,
        reason: String?,
    ): ImportBatch {
        val text = checked(reason, required = true)
        val batch = batches.lock(batchId) ?: throw ImportBatchNotFoundException()
        refuseIf(batch.state != BatchState.COMPLETED, "WRONG_STATE") { "Only a completed batch can be closed." }
        return end(batch, BatchState.CLOSED, text)
    }

    /** OPEN or VALIDATED → ABANDONED with an optional [reason]; nothing was imported, the rows left are NOT_STARTED. */
    fun abandon(
        batchId: UUID,
        reason: String?,
    ): ImportBatch {
        val text = checked(reason, required = false)
        val batch = batches.lock(batchId) ?: throw ImportBatchNotFoundException()
        refuseIf(!batch.state.editable, "WRONG_STATE") { "Only a batch whose import has not started can be abandoned." }
        return end(batch, BatchState.ABANDONED, text)
    }

    /**
     * The sweep's ending of [batch], as the sweep read it (inside a tenant transaction): CLOSED after completion, ABANDONED
     * when idle; nothing when the batch changed since. Returns whether it ended.
     */
    fun expire(batch: ImportBatch): Boolean {
        val locked = batches.lock(batch.id)?.takeIf { it.version == batch.version && it.state == batch.state }
        val ending = locked?.let { ending(it.state) }
        if (locked != null && ending != null) {
            ActorContext.asSystem(ImportRecordPurpose.ACTOR) { end(locked, ending.first, ending.second) }
        }
        return ending != null
    }

    /**
     * One step of the `imports-purge` job: the next page of [batchId]'s staged versions not yet purged, each purged through
     * the files service and marked; ZERO while more are left, else the batch is marked purged (audited `import.batch.purged`)
     * and the job ends. Does nothing for a batch that is not CLOSED or ABANDONED. As `System("legacy-import")`, outside a
     * transaction.
     */
    fun purgeStep(batchId: UUID): Duration? = ActorContext.asSystem(ImportRecordPurpose.ACTOR) { purgePage(batchId) }

    private fun purgePage(batchId: UUID): Duration? {
        val tenant = TenantContext.current()
        val batch = transactions.readInTenant(tenant) { batches.find(batchId) }
        val purging = batch != null && batch.state in ImportBatchTransitions.ENDED && batch.end.purgedAt == null
        val page = if (purging) transactions.readInTenant(tenant) { store.unpurged(batchId, ImportEndLimits.PURGE_PAGE) } else emptyList()
        page.forEach { version ->
            purges.purge(version)
            transactions.inTenant(tenant) { store.purged(version) }
        }
        return when {
            !purging -> {
                null
            }

            page.size == ImportEndLimits.PURGE_PAGE -> {
                Duration.ZERO
            }

            else -> {
                transactions.inTenant(tenant) { purged(batchId) }
                null
            }
        }
    }

    /** Every staged object of [batchId] is purged: marked on the batch, audited `import.batch.purged` with the count. */
    private fun purged(batchId: UUID) {
        if (transitions.purged(batchId)) {
            audit.record(
                "import.batch.purged",
                ImportBatches.OBJECT_TYPE,
                batchId.toString(),
                after = mapOf("stagedVersions" to store.purgedCount(batchId)),
            )
        }
    }

    /** Ends [batch] in [to] (its row locked), audits it with what is left to purge, and schedules the purge. */
    private fun end(
        batch: ImportBatch,
        to: BatchState,
        reason: String?,
    ): ImportBatch {
        check(transitions.end(batch.id, listOf(batch.state), to, reason)) { "batch ${batch.id} changed state" }
        val notStarted = if (to == BatchState.ABANDONED) store.notStarted(batch.id) else 0
        audit.record(
            if (to == BatchState.CLOSED) "import.batch.closed" else "import.batch.abandoned",
            ImportBatches.OBJECT_TYPE,
            batch.id.toString(),
            before = mapOf("state" to batch.state.name),
            after =
                mapOf(
                    "state" to to.name,
                    "reason" to reason,
                    "notStarted" to notStarted,
                    "stagedToPurge" to store.unpurgedCount(batch.id),
                ),
        )
        jobs.purge(batch.id)
        return checkNotNull(batches.find(batch.id))
    }

    private fun checked(
        reason: String?,
        required: Boolean,
    ): String? {
        val text = reason?.trim()?.takeIf { it.isNotEmpty() }
        val problem =
            when {
                text == null && required -> FieldProblem("reason", "REQUIRED", "Say why the batch is closed.")
                text != null && text.length > MAX_REASON -> FieldProblem("reason", "TOO_LONG", "At most $MAX_REASON characters.")
                else -> null
            }
        if (problem != null) throw ImportRejectedException(listOf(problem))
        return text
    }

    private companion object {
        const val MAX_REASON = 500

        /** How the sweep ends a batch in [state], and why; null when it does not end it. */
        fun ending(state: BatchState): Pair<BatchState, String>? =
            when (state) {
                BatchState.COMPLETED -> BatchState.CLOSED to "Closed by the system $KEEP_DAYS days after completion."
                BatchState.OPEN, BatchState.VALIDATED -> BatchState.ABANDONED to "Abandoned by the system after $IDLE_DAYS idle days."
                else -> null
            }

        val KEEP_DAYS = ImportEndLimits.KEEP_STAGED.toDays()
        val IDLE_DAYS = ImportEndLimits.IDLE.toDays()
    }
}
