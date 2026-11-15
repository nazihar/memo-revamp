package com.memo.memo.internal.rendition

import com.github.kagkarlsson.scheduler.SchedulerClient
import com.github.kagkarlsson.scheduler.task.helper.OneTimeTask
import com.github.kagkarlsson.scheduler.task.helper.RecurringTask
import com.github.kagkarlsson.scheduler.task.schedule.Schedules
import com.memo.audit.AuditLog
import com.memo.common.jobs.ActiveTenants
import com.memo.common.jobs.Jobs
import com.memo.common.jobs.TenantScopedPayload
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantDsl
import com.memo.common.tenant.TenantTransactions
import com.memo.memo.internal.Memos
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Service
import java.time.Duration
import java.util.UUID

/** Task data of one record build. */
data class RenditionTask(
    override val tenantId: UUID,
    val renditionId: UUID,
) : TenantScopedPayload

/** Task data of a tenant's backfill. */
data class RenditionBackfillTask(
    override val tenantId: UUID,
) : TenantScopedPayload

/**
 * Final records of memos (ADR-0053): asked for at approval, by the backfill, and (T5) by regeneration; each request is a
 * new PENDING version and a scheduled build. Inside a tenant transaction.
 */
@Service
class MemoRenditions(
    private val store: MemoRenditionStore,
    private val queries: MemoRenditionQueries,
    private val tenantDsl: TenantDsl,
    private val audit: AuditLog,
    private val scheduler: ObjectProvider<SchedulerClient>,
    @param:Qualifier("memoFinalRenditionTask") private val task: ObjectProvider<OneTimeTask<RenditionTask>>,
) {
    /** False only while a test runs the builder itself (test contexts share the scheduler table). */
    @Volatile var jobs: Boolean = true

    /** Version 1 of [memoId]'s record, unless it has one (the approval's transaction, or the backfill). */
    fun first(
        memoId: UUID,
        trigger: RenditionTrigger,
    ): MemoRendition? {
        if (store.of(memoId).isNotEmpty()) return null
        return request(memoId, trigger, null, null)
    }

    /** A new PENDING version and its build; the caller has checked nothing is open and who may ask. */
    fun request(
        memoId: UUID,
        trigger: RenditionTrigger,
        reason: String?,
        requestedBy: UUID?,
    ): MemoRendition {
        val rendition = store.request(memoId, trigger, reason, requestedBy)
        audit.record(
            "memo.rendition.requested",
            Memos.OBJECT_TYPE,
            memoId.toString(),
            after = mapOf("version" to rendition.version, "trigger" to trigger.name, "reason" to reason),
        )
        schedule(rendition)
        return rendition
    }

    fun schedule(rendition: MemoRendition) {
        val client = scheduler.ifAvailable.takeIf { jobs }
        val build = task.ifAvailable
        if (client != null && build != null) {
            val id = "${rendition.memoId}:${rendition.version}:${rendition.attempts}"
            client.scheduleIfNotExists(build.schedulableInstance(id, RenditionTask(tenantDsl.tenant().value, rendition.id)))
        }
    }

    /** Up to [limit] memos approved before E04 get version 1. */
    fun backfill(limit: Int = BACKFILL_BATCH): Int = queries.missing(limit).count { first(it, RenditionTrigger.BACKFILL) != null }

    fun of(memoId: UUID): List<MemoRendition> = store.of(memoId)

    fun parts(renditionId: UUID): List<RenditionPart> = queries.parts(renditionId)

    companion object {
        const val BACKFILL_BATCH = 100
    }
}

/** The record tasks (ADR-0018, design E04 §11): one build per version, and a daily backfill per tenant. */
@Configuration(proxyBeanMethods = false)
class MemoRenditionJobs {
    @Bean
    fun memoFinalRenditionTask(
        jobs: Jobs,
        builder: RecordBuilder,
    ): OneTimeTask<RenditionTask> =
        jobs.tenantTask(MemoRenditionPurpose.JOB, RenditionTask::class.java, maxRetries = RecordBuilder.MAX_ATTEMPTS) {
            builder.build(it.renditionId)
        }

    @Bean
    fun memoRenditionBackfillTask(
        jobs: Jobs,
        renditions: MemoRenditions,
        transactions: TenantTransactions,
    ): OneTimeTask<RenditionBackfillTask> =
        jobs.tenantTask(BACKFILL, RenditionBackfillTask::class.java) {
            transactions.inTenant(TenantContext.current()) { renditions.backfill() }
        }

    @Bean
    fun memoRenditionBackfillSweep(
        jobs: Jobs,
        tenants: ActiveTenants,
        scheduler: ObjectProvider<SchedulerClient>,
        @Qualifier("memoRenditionBackfillTask") task: ObjectProvider<OneTimeTask<RenditionBackfillTask>>,
    ): RecurringTask<Void> =
        jobs.platformTask("memo-rendition-backfill-sweep", Schedules.fixedDelay(EVERY)) {
            val client = scheduler.getObject()
            val backfill = task.getObject()
            tenants.ids().forEach { tenant ->
                client.scheduleIfNotExists(backfill.schedulableInstance(tenant.toString(), RenditionBackfillTask(tenant.value)))
            }
        }

    private companion object {
        const val BACKFILL = "memo-rendition-backfill"
        val EVERY: Duration = Duration.ofHours(6)
    }
}
