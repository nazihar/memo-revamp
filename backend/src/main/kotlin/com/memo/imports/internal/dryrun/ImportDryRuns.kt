package com.memo.imports.internal.dryrun

import com.github.kagkarlsson.scheduler.SchedulerClient
import com.github.kagkarlsson.scheduler.task.helper.OneTimeTask
import com.memo.audit.AuditLog
import com.memo.common.context.ActorContext
import com.memo.common.jobs.Jobs
import com.memo.common.jobs.TenantScopedPayload
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantDsl
import com.memo.common.tenant.TenantTransactions
import com.memo.common.web.refuseIf
import com.memo.imports.internal.BatchState
import com.memo.imports.internal.DryRunOutcome
import com.memo.imports.internal.ImportBatch
import com.memo.imports.internal.ImportBatchNotFoundException
import com.memo.imports.internal.ImportBatchStore
import com.memo.imports.internal.ImportBatchTransitions
import com.memo.imports.internal.ImportBatches
import com.memo.imports.internal.ImportRecordPurpose
import com.memo.imports.internal.ImportViews
import com.memo.imports.internal.SourceKind
import com.memo.imports.internal.SourceTable
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Service
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/** Task data of one dry run. */
data class DryRunTask(
    override val tenantId: UUID,
    val batchId: UUID,
) : TenantScopedPayload

/**
 * The dry run (ADR-0055, design E06 §5.2): requested by a holder of `memo.import`, run by the `imports-dry-run` job as
 * `System("legacy-import")`. It stages the sources' rows, groups them and matches paths, proposes mappings, validates every
 * row in read-only transactions, and records the problems, the totals and the input hash. The batch becomes VALIDATED
 * when no batch-level problem stops it, otherwise OPEN; after [DryRunLimits.ATTEMPTS] failed attempts it is OPEN with
 * `DRY_RUN_FAILED`. Nothing but staging rows, problems, mappings and the batch row is written.
 */
@Service
class ImportDryRuns(
    private val batches: ImportBatchStore,
    private val transitions: ImportBatchTransitions,
    private val stager: DryRunStager,
    private val staging: StagingStore,
    private val matching: PathMatching,
    private val proposals: MappingProposals,
    private val validator: DryRunValidator,
    private val inputs: InputHash,
    private val findings: FindingStore,
    private val views: ImportViews,
    private val audit: AuditLog,
    private val transactions: TenantTransactions,
    private val tenantDsl: TenantDsl,
    private val json: JsonMapper,
    private val scheduler: ObjectProvider<SchedulerClient>,
    @param:Qualifier("importsDryRunTask") private val task: ObjectProvider<OneTimeTask<DryRunTask>>,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** False only while a test runs the dry run itself (test contexts share the scheduler table). */
    @Volatile var jobs: Boolean = true

    /**
     * OPEN or VALIDATED → DRY_RUN, audited, and the job scheduled; a batch already in DRY_RUN is answered as it is (one dry
     * run per batch at a time). Inside a tenant transaction. A seeder that runs the dry run itself ([run]) passes
     * [scheduleJob] false, so no job runs it a second time.
     */
    fun request(
        batchId: UUID,
        scheduleJob: Boolean = true,
    ): ImportBatch {
        val batch = batches.lock(batchId) ?: throw ImportBatchNotFoundException()
        if (batch.state == BatchState.DRY_RUN) return batch
        refuseIf(!batch.state.editable, "WRONG_STATE") { "A dry run runs only before the import starts." }
        refuseIf(!complete(batch), "SOURCE_MISSING") { "Upload the workbook, or the Memos and Files CSV files, first." }
        check(transitions.move(batchId, ImportBatchTransitions.EDITABLE, BatchState.DRY_RUN)) { "batch $batchId changed state" }
        audit.record(
            "import.dry_run.requested",
            ImportBatches.OBJECT_TYPE,
            batchId.toString(),
            before = mapOf("state" to batch.state.name),
            after = mapOf("state" to BatchState.DRY_RUN.name),
        )
        if (scheduleJob) schedule(batchId, batch.version + 1)
        return checkNotNull(batches.find(batchId))
    }

    /**
     * Runs the dry run of [batchId] as the job does, outside a transaction with the tenant bound. [failures] is how many
     * attempts failed before: on the last one a failure ends the dry run with `DRY_RUN_FAILED` instead of a retry.
     */
    @Suppress("TooGenericExceptionCaught") // any failure of the last attempt must leave the batch OPEN, not stuck in DRY_RUN
    fun run(
        batchId: UUID,
        failures: Int = 0,
    ) {
        ActorContext.asSystem(ImportRecordPurpose.ACTOR) {
            val tenant = TenantContext.current()
            val batch = transactions.readInTenant(tenant) { batches.find(batchId) }
            if (batch?.state != BatchState.DRY_RUN) return@asSystem
            try {
                steps(batch)
            } catch (e: RuntimeException) {
                if (failures + 1 < DryRunLimits.ATTEMPTS) throw e
                log.error("Dry run of import batch {} failed for good", batchId, e)
                transactions.inTenant(tenant) { failed(batchId) }
            }
        }
    }

    private fun steps(batch: ImportBatch) {
        val tenant = TenantContext.current()
        val staged = stager.stage(batch.id)
        if (staged.problems.isNotEmpty()) {
            transactions.inTenant(tenant) { findings.add(batch.id, null, staged.problems) }
            finish(batch.id, null, tooManyProblems = false)
            return
        }
        transactions.inTenant(tenant) { staging.group(batch.id) }
        var after: UUID? = null
        do {
            after = transactions.inTenant(tenant) { matching.page(batch.id, after) }
        } while (after != null)
        transactions.inTenant(tenant) { proposals.propose(batch.id) }
        val hash = transactions.readInTenant(tenant) { inputs.of(checkNotNull(batches.find(batch.id))) }
        val result = validator.validate(batch.id, staged.findings)
        if (result.tooManyProblems) transactions.inTenant(tenant) { findings.add(batch.id, null, listOf(DryRunLimits.tooManyProblems())) }
        finish(batch.id, hash, result.tooManyProblems, result.maxRank)
    }

    /**
     * Records the outcome: VALIDATED when no batch-level problem was found and nothing the dry run read changed since
     * [validated] was taken (it is null when staging stopped), else OPEN; audited `import.dry_run.finished`.
     */
    private fun finish(
        batchId: UUID,
        validated: String?,
        tooManyProblems: Boolean,
        maxRank: Int? = null,
    ) = transactions.inTenant(TenantContext.current()) {
        val batch = checkNotNull(batches.lock(batchId))
        val input = inputs.of(batch)
        val problems = views.problems(batchId)
        val rows = views.rows(batchId)
        val blocked = tooManyProblems || validated == null || validated != input || (problems[BATCH] ?: 0) > 0
        val state = if (blocked) BatchState.OPEN else BatchState.VALIDATED
        val totals = mapOf("rows" to rows, "problems" to problems)
        check(
            transitions.finishDryRun(batchId, DryRunOutcome(state, validated ?: input, input, json.writeValueAsString(totals), maxRank)),
        ) {
            "batch $batchId left DRY_RUN"
        }
        audit.record(
            "import.dry_run.finished",
            ImportBatches.OBJECT_TYPE,
            batchId.toString(),
            after = mapOf("state" to state.name, "rows" to rows, "problems" to problems, "inputHash" to input, "maxRank" to maxRank),
        )
    }

    /** The last attempt failed: the staging rows go, `DRY_RUN_FAILED` stays, and the batch is OPEN again. */
    private fun failed(batchId: UUID) {
        staging.clear(batchId)
        findings.add(batchId, null, listOf(Finding(ProblemLevel.BATCH, "DRY_RUN_FAILED", ProblemSheet.BATCH)))
        if (transitions.move(batchId, listOf(BatchState.DRY_RUN), BatchState.OPEN)) {
            audit.record(
                "import.dry_run.finished",
                ImportBatches.OBJECT_TYPE,
                batchId.toString(),
                after = mapOf("state" to BatchState.OPEN.name, "failed" to true),
            )
        }
    }

    private fun schedule(
        batchId: UUID,
        version: Long,
    ) {
        val client = scheduler.ifAvailable.takeIf { jobs }
        val dryRun = task.ifAvailable
        if (client != null && dryRun != null) {
            client.scheduleIfNotExists(dryRun.schedulableInstance("$batchId:$version", DryRunTask(tenantDsl.tenant().value, batchId)))
        }
    }

    private companion object {
        const val BATCH = "BATCH"

        /** A workbook, or at least the Memos and Files CSV files (Approvals may be left out). */
        fun complete(batch: ImportBatch): Boolean {
            val tables = batch.sources.map { it.table }.toSet()
            return when (batch.sourceKind) {
                SourceKind.XLSX -> SourceTable.WORKBOOK in tables
                SourceKind.CSV -> SourceTable.MEMOS in tables && SourceTable.FILES in tables
                null -> false
            }
        }
    }
}

/** The dry run's task (design E06 §15): one per request, three attempts, then `DRY_RUN_FAILED`. */
@Configuration(proxyBeanMethods = false)
class ImportDryRunJobs {
    @Bean
    fun importsDryRunTask(
        jobs: Jobs,
        dryRuns: ObjectProvider<ImportDryRuns>,
    ): OneTimeTask<DryRunTask> =
        jobs.tenantTaskCounting(NAME, DryRunTask::class.java, maxRetries = DryRunLimits.ATTEMPTS) { payload, failures ->
            dryRuns.getObject().run(payload.batchId, failures)
        }

    companion object {
        const val NAME = "imports-dry-run"
    }
}
