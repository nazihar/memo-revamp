package com.memo.docedit.internal

import com.github.kagkarlsson.scheduler.SchedulerClient
import com.github.kagkarlsson.scheduler.task.helper.OneTimeTask
import com.github.kagkarlsson.scheduler.task.helper.RecurringTask
import com.github.kagkarlsson.scheduler.task.schedule.Schedules
import com.memo.audit.AuditLog
import com.memo.common.jobs.ActiveTenants
import com.memo.common.jobs.Jobs
import com.memo.common.jobs.TenantScopedPayload
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantTransactions
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.util.UUID

/** Task data of the per-tenant expiry task. */
data class DocEditTenantTask(
    override val tenantId: UUID,
) : TenantScopedPayload

/** Marks edit sessions left open past their lifetime as `EXPIRED` (design E02 §2), per tenant, as `System(<task>)`. */
@Component
class EditSessionExpiry(
    private val store: EditSessionStore,
    private val transactions: TenantTransactions,
    private val audit: AuditLog,
    private val clock: Clock,
) {
    /** Returns how many sessions expired. */
    fun expireDue(): Int =
        transactions.inTenant(TenantContext.current()) {
            val now = clock.instant()
            store.due(now, BATCH).count { id ->
                store.finish(id, SessionState.EXPIRED, null, null, now).also { expired ->
                    if (expired) {
                        audit.record(
                            "docedit.session.closed",
                            EditSessions.OBJECT_TYPE,
                            id.toString(),
                            after = mapOf("outcome" to SessionState.EXPIRED.name),
                        )
                    }
                }
            }
        }

    private companion object {
        const val BATCH = 500
    }
}

/** The editing tasks (ADR-0018): a platform sweep fans out to one expiry task per active tenant. */
@Configuration(proxyBeanMethods = false)
class DocEditJobs {
    @Bean
    fun doceditExpireTask(
        jobs: Jobs,
        expiry: EditSessionExpiry,
    ): OneTimeTask<DocEditTenantTask> = jobs.tenantTask(EXPIRE, DocEditTenantTask::class.java) { expiry.expireDue() }

    @Bean
    fun doceditExpireSweep(
        jobs: Jobs,
        tenants: ActiveTenants,
        scheduler: ObjectProvider<SchedulerClient>,
        task: ObjectProvider<OneTimeTask<DocEditTenantTask>>,
    ): RecurringTask<Void> =
        jobs.platformTask("docedit-expire-sweep", Schedules.fixedDelay(EVERY)) {
            // Instance id = tenant id, so overlapping sweeps collapse.
            val client = scheduler.getObject()
            val expire = task.getObject()
            tenants.ids().forEach { tenant ->
                client.scheduleIfNotExists(expire.schedulableInstance(tenant.toString(), DocEditTenantTask(tenant.value)))
            }
        }

    companion object {
        const val EXPIRE = "docedit-expire"
        private val EVERY: Duration = Duration.ofMinutes(15)
    }
}
