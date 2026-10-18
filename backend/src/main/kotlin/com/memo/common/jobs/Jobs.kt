package com.memo.common.jobs

import com.github.kagkarlsson.scheduler.task.ExecutionComplete
import com.github.kagkarlsson.scheduler.task.ExecutionOperations
import com.github.kagkarlsson.scheduler.task.FailureHandler
import com.github.kagkarlsson.scheduler.task.helper.OneTimeTask
import com.github.kagkarlsson.scheduler.task.helper.RecurringTask
import com.github.kagkarlsson.scheduler.task.helper.Tasks
import com.github.kagkarlsson.scheduler.task.schedule.Schedule
import com.memo.common.context.ActorContext
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantId
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.UUID

/**
 * Data of a task that works on one tenant's data (ADR-0018). The tenant travels in the task data, never in the thread.
 * Payloads carry ids only: the scheduler table is a platform table the app role can read across tenants.
 */
interface TenantScopedPayload {
    val tenantId: UUID
}

/** Answers whether a tenant id names an existing tenant. Implemented by the tenancy module. */
fun interface KnownTenants {
    fun exists(tenant: TenantId): Boolean
}

/** Lists the tenants a platform job fans out to (platform scope, ADR-0017). Implemented by the tenancy module. */
fun interface ActiveTenants {
    fun ids(): List<TenantId>
}

/** A job failure that retrying cannot fix (for example a task for an unknown tenant): the execution is dropped. */
class PermanentJobFailure(
    message: String,
) : RuntimeException(message)

/**
 * Builds db-scheduler tasks with the platform's conventions (ADR-0018): bounded retries with exponential backoff,
 * tenant and actor bound from the task data for tenant tasks, permanent failures dropped and counted.
 * Modules declare their tasks as beans built here; the scheduler picks every `Task` bean up.
 */
@Component
class Jobs(
    private val knownTenants: KnownTenants,
    private val meters: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** A one-off task for one tenant. Schedule instances with `SchedulerClient`; instance ids should be idempotency keys. */
    fun <P : TenantScopedPayload> tenantTask(
        name: String,
        payload: Class<P>,
        maxRetries: Int = DEFAULT_MAX_RETRIES,
        handler: (P) -> Unit,
    ): OneTimeTask<P> =
        Tasks
            .oneTime(name, payload)
            .onFailure(retrying(name, maxRetries))
            .execute { instance, _ ->
                val data =
                    runCatching { instance.data }.getOrElse { throw PermanentJobFailure("task data of $name is unreadable") }
                        ?: throw PermanentJobFailure("task $name has no data (no tenant)")
                val tenant = TenantId(data.tenantId)
                if (!knownTenants.exists(tenant)) throw PermanentJobFailure("task $name names an unknown tenant")
                TenantContext.call(tenant) { ActorContext.asSystem(name) { handler(data) } }
            }

    /**
     * A recurring task with no tenant. It runs as `System(name)` and may only use platform transactions or schedule
     * tenant tasks; it never touches tenant data itself.
     */
    fun platformTask(
        name: String,
        schedule: Schedule,
        handler: () -> Unit,
    ): RecurringTask<Void> =
        Tasks
            .recurring(name, schedule)
            .execute { _, _ -> ActorContext.asSystem(name) { handler() } }

    /**
     * A one-off task with no scope binding, for platform work on one target named in its data (for example one audit
     * chain). The handler opens scoped transactions itself, so it may touch a tenant and the platform chain one after the
     * other, which a tenant-scoped task cannot.
     */
    fun <P : Any> platformOneOff(
        name: String,
        payload: Class<P>,
        maxRetries: Int = DEFAULT_MAX_RETRIES,
        handler: (P) -> Unit,
    ): OneTimeTask<P> =
        Tasks
            .oneTime(name, payload)
            .onFailure(retrying(name, maxRetries))
            .execute { instance, _ ->
                val data = instance.data ?: throw PermanentJobFailure("task $name has no data")
                ActorContext.asSystem(name) { handler(data) }
            }

    private fun <P> retrying(
        name: String,
        maxRetries: Int,
    ): FailureHandler<P> {
        val backoff = FailureHandler.ExponentialBackoffFailureHandler<P>(FIRST_RETRY_DELAY)
        return FailureHandler { complete: ExecutionComplete, operations: ExecutionOperations<P> ->
            val failures = complete.execution.consecutiveFailures + 1
            when {
                complete.cause.map { it is PermanentJobFailure }.orElse(false) -> {
                    log.error("Task {} failed permanently: {}", name, complete.cause.get().message)
                    meters.counter(FAILED_METRIC, "task", name, "reason", "permanent").increment()
                    operations.stop()
                }

                failures >= maxRetries -> {
                    log.error("Task {} failed {} times; giving up", name, failures, complete.cause.orElse(null))
                    meters.counter(FAILED_METRIC, "task", name, "reason", "retries-exhausted").increment()
                    operations.stop()
                }

                else -> {
                    backoff.onFailure(complete, operations)
                }
            }
        }
    }

    companion object {
        const val FAILED_METRIC = "memo.jobs.failed"
        private const val DEFAULT_MAX_RETRIES = 5
        private val FIRST_RETRY_DELAY: Duration = Duration.ofSeconds(10)
    }
}
