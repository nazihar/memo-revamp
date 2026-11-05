package com.memo.files.internal

import com.github.kagkarlsson.scheduler.SchedulerClient
import com.github.kagkarlsson.scheduler.task.helper.OneTimeTask
import com.github.kagkarlsson.scheduler.task.helper.RecurringTask
import com.github.kagkarlsson.scheduler.task.schedule.Schedules
import com.memo.audit.AuditLog
import com.memo.common.jobs.ActiveTenants
import com.memo.common.jobs.Jobs
import com.memo.common.jobs.TenantScopedPayload
import com.memo.common.storage.ObjectStore
import com.memo.common.storage.Retention
import com.memo.common.storage.StorageUnavailableException
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantTransactions
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Component
import software.amazon.awssdk.core.exception.SdkException
import java.time.Clock
import java.time.Duration
import java.util.UUID

/** Task data of the per-tenant files tasks. */
data class FilesTenantTask(
    override val tenantId: UUID,
) : TenantScopedPayload

/**
 * Housekeeping of the files service (design §12), per tenant: finalise record locks that are still provisional, and
 * remove objects whose upload never committed. Runs as `System(<task>)` in the tenant's context.
 */
@Component
internal class FilesMaintenance(
    private val store: FileStore,
    private val pending: PendingUploads,
    private val retentions: Retentions,
    private val objects: ObjectStore,
    private val transactions: TenantTransactions,
    private val audit: AuditLog,
    private val clock: Clock,
    meters: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val stillProvisional = meters.counter("memo.files.retention.still.provisional")

    /** Extends every lock the upload itself could not; returns how many were finalised. */
    fun finaliseProvisional(): Int {
        val tenant = TenantContext.current()
        val now = clock.instant()
        val locks = transactions.readInTenant(tenant) { store.provisional(now.minus(FINALISE_AFTER), BATCH) }
        return locks.count { lock ->
            val done =
                retentions.finalise(
                    tenant,
                    lock.versionId,
                    lock.objectKey,
                    lock.objectVersion,
                    Retention(lock.mode, lock.retainUntil),
                )
            if (!done) {
                stillProvisional.increment()
                log.warn("File version {} keeps a provisional lock; retrying on the next run", lock.versionId)
            }
            done
        }
    }

    /** Removes what uploads left behind; returns how many leftovers were cleared. */
    fun sweepPending(): Int {
        val tenant = TenantContext.current()
        val now = clock.instant()
        val stale = transactions.readInTenant(tenant) { pending.stale(now.minus(PENDING_AFTER), BATCH) }
        return stale.count { upload ->
            val locked = upload.provisionalUntil?.isAfter(now) == true
            !locked && removeObject(upload) && clearRow(upload)
        }
    }

    private fun removeObject(upload: PendingUpload): Boolean =
        try {
            if (!transactions.readInTenant(TenantContext.current()) { pending.isReferenced(upload.bucket, upload.objectKey) }) {
                objects.deleteAllVersions(bucketOf(upload.bucket), upload.objectKey)
            }
            true
        } catch (e: SdkException) {
            // Storage refuses while its own clock still sees the provisional lock: the next run retries.
            log.info("Leftover upload object not removed yet: {}", e.javaClass.simpleName)
            false
        } catch (e: StorageUnavailableException) {
            log.warn("Leftover upload object not removed: {}", e.message)
            false
        }

    private fun clearRow(upload: PendingUpload): Boolean =
        transactions.inTenant(TenantContext.current()) {
            pending.delete(upload.id)
            audit.record("file.upload.leftover.removed", "file.upload", upload.id.toString(), after = mapOf("bucket" to upload.bucket))
            true
        }

    companion object {
        /** Uploads finalise their own lock right after commit; the job only takes what is clearly left over. */
        val FINALISE_AFTER: Duration = Duration.ofMinutes(2)
        val PENDING_AFTER: Duration = Duration.ofHours(1)
        private const val BATCH = 100
    }
}

/** The files tasks (ADR-0018): platform sweeps fan out to one task per active tenant. */
@Configuration(proxyBeanMethods = false)
internal class FilesJobs {
    @Bean
    fun filesFinaliseTask(
        jobs: Jobs,
        maintenance: FilesMaintenance,
    ): OneTimeTask<FilesTenantTask> = jobs.tenantTask(FINALISE, FilesTenantTask::class.java) { maintenance.finaliseProvisional() }

    @Bean
    fun filesPendingTask(
        jobs: Jobs,
        maintenance: FilesMaintenance,
    ): OneTimeTask<FilesTenantTask> = jobs.tenantTask(PENDING, FilesTenantTask::class.java) { maintenance.sweepPending() }

    @Bean
    fun filesFinaliseSweep(
        jobs: Jobs,
        sweeper: FilesSweeper,
    ): RecurringTask<Void> =
        jobs.platformTask("files-retention-sweep", Schedules.fixedDelay(FINALISE_EVERY)) {
            sweeper.fanOut(FINALISE)
        }

    @Bean
    fun filesPendingSweep(
        jobs: Jobs,
        sweeper: FilesSweeper,
    ): RecurringTask<Void> = jobs.platformTask("files-pending-sweep", Schedules.fixedDelay(Duration.ofHours(1))) { sweeper.fanOut(PENDING) }

    companion object {
        const val FINALISE = "files-retention-finalise"
        const val PENDING = "files-pending-cleanup"
        private val FINALISE_EVERY: Duration = Duration.ofMinutes(5)
    }
}

/** Schedules one task per active tenant; instance id = tenant id, so overlapping sweeps collapse. */
@Component
internal class FilesSweeper(
    private val tenants: ActiveTenants,
    private val scheduler: ObjectProvider<SchedulerClient>,
    private val tasks: ObjectProvider<OneTimeTask<FilesTenantTask>>,
) {
    fun fanOut(taskName: String) {
        val task = tasks.orderedStream().toList().single { it.name == taskName }
        val client = scheduler.getObject()
        tenants.ids().forEach { tenant ->
            client.scheduleIfNotExists(task.schedulableInstance(tenant.toString(), FilesTenantTask(tenant.value)))
        }
    }
}
