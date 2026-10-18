package com.memo.testsupport

import com.github.kagkarlsson.scheduler.task.helper.OneTimeTask
import com.github.kagkarlsson.scheduler.task.helper.Tasks
import com.memo.common.context.ActorContext
import com.memo.common.jobs.Jobs
import com.memo.common.jobs.TenantScopedPayload
import com.memo.common.tenant.TenantContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

data class ProbePayload(
    override val tenantId: UUID,
    val runId: UUID,
) : TenantScopedPayload

/** What a probe task saw when it ran: scoped tenant, database tenant, actor. */
data class ProbeObservation(
    val scopedTenant: String?,
    val databaseTenant: String?,
    val actor: String?,
)

/** Test-only tasks (scanned like any bean): a tenant probe and a plain counter for single-execution tests. */
@Configuration(proxyBeanMethods = false)
class TestJobs {
    @Bean
    fun probeTask(
        jobs: Jobs,
        probe: TransactionProbe,
    ): OneTimeTask<ProbePayload> =
        jobs.tenantTask(PROBE, ProbePayload::class.java, maxRetries = 1) { payload ->
            OBSERVED[payload.runId] =
                ProbeObservation(TenantContext.currentOrNull()?.toString(), probe.databaseTenant(), ActorContext.current().actor.id)
        }

    @Bean
    fun countingTask(): OneTimeTask<Void> = Tasks.oneTime(COUNTING).execute { instance, _ -> counter(instance.id).incrementAndGet() }

    companion object {
        const val PROBE = "test-probe"
        const val COUNTING = "test-counting"
        val OBSERVED = ConcurrentHashMap<UUID, ProbeObservation>()
        private val COUNTERS = ConcurrentHashMap<String, AtomicInteger>()

        fun counter(instance: String): AtomicInteger = COUNTERS.computeIfAbsent(instance) { AtomicInteger() }
    }
}
