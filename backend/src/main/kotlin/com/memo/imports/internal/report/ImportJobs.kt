package com.memo.imports.internal.report

import com.github.kagkarlsson.scheduler.SchedulerClient
import com.github.kagkarlsson.scheduler.task.Task
import com.github.kagkarlsson.scheduler.task.helper.CustomTask
import com.github.kagkarlsson.scheduler.task.helper.OneTimeTask
import com.github.kagkarlsson.scheduler.task.helper.RecurringTask
import com.github.kagkarlsson.scheduler.task.schedule.Schedules
import com.memo.common.jobs.ActiveTenants
import com.memo.common.jobs.Jobs
import com.memo.common.jobs.TenantScopedPayload
import com.memo.common.tenant.TenantContext
import com.memo.imports.internal.close.ImportClosing
import com.memo.imports.internal.close.ImportSweep
import com.memo.imports.internal.run.ImportRunTask
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.UUID

/** Task data of a batch's verification, report or purge. */
data class ImportBatchTask(
    override val tenantId: UUID,
    val batchId: UUID,
) : TenantScopedPayload

/** Task data of a tenant's import sweep. */
data class ImportSweepTask(
    override val tenantId: UUID,
) : TenantScopedPayload

/**
 * Schedules a batch's background work after the import (design E06 §15), each at most once per batch: the import job
 * (`imports-run`, instance `{batchId}`, restarted by the sweep), `imports-verify` (`{batchId}:verify`), `imports-report`
 * (`{batchId}:report`) and `imports-purge` (`{batchId}:purge`). With the tenant bound; inside or outside a transaction.
 */
@Component
class ImportJobs(
    private val scheduler: ObjectProvider<SchedulerClient>,
    @param:Qualifier("importsRunTask") private val runTask: ObjectProvider<CustomTask<ImportRunTask>>,
    @param:Qualifier("importsVerifyTask") private val verifyTask: ObjectProvider<OneTimeTask<ImportBatchTask>>,
    @param:Qualifier("importsReportTask") private val reportTask: ObjectProvider<CustomTask<ImportBatchTask>>,
    @param:Qualifier("importsPurgeTask") private val purgeTask: ObjectProvider<CustomTask<ImportBatchTask>>,
) {
    /** False only while a test runs the work itself (test contexts share the scheduler table). */
    @Volatile var jobs: Boolean = true

    fun run(batchId: UUID) = schedule(runTask.ifAvailable, batchId.toString(), ImportRunTask(tenant(), batchId))

    fun verify(batchId: UUID) = schedule(verifyTask.ifAvailable, "$batchId:verify", ImportBatchTask(tenant(), batchId))

    fun report(batchId: UUID) = schedule(reportTask.ifAvailable, "$batchId:report", ImportBatchTask(tenant(), batchId))

    fun purge(batchId: UUID) = schedule(purgeTask.ifAvailable, "$batchId:purge", ImportBatchTask(tenant(), batchId))

    private fun <T> schedule(
        task: Task<T>?,
        instance: String,
        data: T,
    ) {
        val client = scheduler.ifAvailable?.takeIf { jobs } ?: return
        if (task != null) client.scheduleIfNotExists(task.schedulableInstance(instance, data))
    }

    private fun tenant(): UUID = TenantContext.current().value
}

/**
 * The tasks after the import (design E06 §15): verification (one-off; a retry starts again), the report (a step task
 * that looks again every [ImportEndLimits.REPORT_WAIT] while renditions are pending), the purge of a closed batch's staged
 * objects (a step task, a page per step), and the hourly sweep, which fans out to a tenant task per active tenant.
 */
@Configuration(proxyBeanMethods = false)
class ImportEndJobs {
    @Bean
    fun importsVerifyTask(
        jobs: Jobs,
        verification: ObjectProvider<ImportVerification>,
    ): OneTimeTask<ImportBatchTask> = jobs.tenantTask(VERIFY, ImportBatchTask::class.java) { verification.getObject().verify(it.batchId) }

    @Bean
    fun importsReportTask(
        jobs: Jobs,
        reports: ObjectProvider<ImportReports>,
    ): CustomTask<ImportBatchTask> =
        jobs.tenantStepTask(REPORT, ImportBatchTask::class.java) { payload, _ -> reports.getObject().step(payload.batchId) }

    @Bean
    fun importsPurgeTask(
        jobs: Jobs,
        closing: ObjectProvider<ImportClosing>,
    ): CustomTask<ImportBatchTask> =
        jobs.tenantStepTask(PURGE, ImportBatchTask::class.java) { payload, _ -> closing.getObject().purgeStep(payload.batchId) }

    @Bean
    fun importsSweepTenantTask(
        jobs: Jobs,
        sweep: ObjectProvider<ImportSweep>,
    ): OneTimeTask<ImportSweepTask> = jobs.tenantTask(SWEEP_TENANT, ImportSweepTask::class.java) { sweep.getObject().sweep() }

    @Bean
    fun importsSweep(
        jobs: Jobs,
        tenants: ActiveTenants,
        scheduler: ObjectProvider<SchedulerClient>,
        @Qualifier("importsSweepTenantTask") task: ObjectProvider<OneTimeTask<ImportSweepTask>>,
    ): RecurringTask<Void> =
        jobs.platformTask(SWEEP, Schedules.fixedDelay(EVERY)) {
            val client = scheduler.getObject()
            val sweep = task.getObject()
            tenants.ids().forEach { tenant ->
                client.scheduleIfNotExists(sweep.schedulableInstance(tenant.toString(), ImportSweepTask(tenant.value)))
            }
        }

    companion object {
        const val VERIFY = "imports-verify"
        const val REPORT = "imports-report"
        const val PURGE = "imports-purge"
        const val SWEEP = "imports-sweep"
        const val SWEEP_TENANT = "imports-sweep-tenant"
        val EVERY: Duration = Duration.ofHours(1)
    }
}

/** The bounds of the work after the import (design E06 §13, §15). */
object ImportEndLimits {
    /** How long the report waits between looks while renditions are pending. */
    val REPORT_WAIT: Duration = Duration.ofMinutes(10)

    /** The report is built at the latest this long after the import finished, pending renditions stated (`imports.report.max-wait`). */
    val REPORT_MAX_WAIT: Duration = Duration.ofHours(72)

    /** A COMPLETED batch is closed this long after completion (`imports.keep-staged-days`). */
    val KEEP_STAGED: Duration = Duration.ofDays(30)

    /** An OPEN or VALIDATED batch without change this long is ABANDONED (`imports.idle-days`). */
    val IDLE: Duration = Duration.ofDays(30)

    /** Rows of the verification and the report read per transaction. */
    const val PAGE = 500

    /** Staged versions purged per step (design E06 §15). */
    const val PURGE_PAGE = 500
}
