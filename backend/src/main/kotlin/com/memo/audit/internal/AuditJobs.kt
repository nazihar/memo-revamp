package com.memo.audit.internal

import com.github.kagkarlsson.scheduler.SchedulerClient
import com.github.kagkarlsson.scheduler.task.helper.OneTimeTask
import com.github.kagkarlsson.scheduler.task.helper.RecurringTask
import com.github.kagkarlsson.scheduler.task.schedule.Schedules
import com.memo.audit.PlatformChain
import com.memo.common.jobs.ActiveTenants
import com.memo.common.jobs.Jobs
import io.micrometer.core.instrument.MeterRegistry
import org.jooq.DSLContext
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** Task data of the per-chain audit tasks: the chain (tenant id) only. */
data class AuditChainTask(
    val chainId: UUID,
)

/** Counts future monthly partitions and alerts when they run low (the app role cannot create them). */
@Component
internal class AuditPartitionCheck(
    private val dsl: DSLContext,
    private val clock: Clock,
    meters: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val futureMonths = AtomicInteger(-1).also { meters.gauge("memo.audit.partitions.future", it) }

    fun check(): Int {
        val names =
            dsl
                .fetch(
                    """
                    select c.relname from pg_inherits i join pg_class c on c.oid = i.inhrelid
                    join pg_class p on p.oid = i.inhparent where p.relname = 'audit_event'
                    """,
                ).map { it.get(0, String::class.java) }
                .toSet()
        val thisMonth = LocalDate.now(clock.withZone(ZoneOffset.UTC)).withDayOfMonth(1)
        val ahead = generateSequence(1L) { it + 1 }.takeWhile { names.contains(partitionName(thisMonth.plusMonths(it))) }.count()
        futureMonths.set(ahead)
        if (ahead < MIN_FUTURE_MONTHS) {
            log.error("Only {} future audit partitions exist; run the migration job to create more", ahead)
        }
        return ahead
    }

    private fun partitionName(month: LocalDate) = "audit_event_y%04dm%02d".format(month.year, month.monthValue)

    companion object {
        const val MIN_FUTURE_MONTHS = 3
    }
}

/** The kernel's audit jobs (ADR-0018 §kernel jobs): platform sweeps fan out to one task per tenant. */
@Configuration(proxyBeanMethods = false)
internal class AuditJobs {
    @Bean
    fun auditAnchorTask(
        jobs: Jobs,
        integrity: AuditIntegrity,
    ): OneTimeTask<AuditChainTask> = jobs.platformOneOff(ANCHOR, AuditChainTask::class.java) { integrity.anchor(it.chainId) }

    @Bean
    fun auditVerifyTask(
        jobs: Jobs,
        integrity: AuditIntegrity,
    ): OneTimeTask<AuditChainTask> = jobs.platformOneOff(VERIFY, AuditChainTask::class.java) { integrity.verify(it.chainId, full = false) }

    @Bean
    fun auditVerifyFullTask(
        jobs: Jobs,
        integrity: AuditIntegrity,
    ): OneTimeTask<AuditChainTask> =
        jobs.platformOneOff(VERIFY_FULL, AuditChainTask::class.java) { integrity.verify(it.chainId, full = true) }

    @Bean
    fun auditAnchorSweep(
        jobs: Jobs,
        integrity: AuditIntegrity,
        sweeper: AuditSweeper,
        @Value("\${memo.audit.anchor-interval:PT15M}") interval: Duration,
    ): RecurringTask<Void> =
        jobs.platformTask("audit-anchor-sweep", Schedules.fixedDelay(interval)) {
            integrity.anchor(PlatformChain.ID)
            sweeper.fanOut(ANCHOR)
        }

    @Bean
    fun auditVerifySweep(
        jobs: Jobs,
        integrity: AuditIntegrity,
        sweeper: AuditSweeper,
    ): RecurringTask<Void> =
        jobs.platformTask("audit-verify-sweep", Schedules.fixedDelay(Duration.ofHours(1))) {
            integrity.verify(PlatformChain.ID, full = false)
            sweeper.fanOut(VERIFY)
        }

    @Bean
    fun auditVerifyFullSweep(
        jobs: Jobs,
        integrity: AuditIntegrity,
        sweeper: AuditSweeper,
    ): RecurringTask<Void> =
        jobs.platformTask("audit-verify-full-sweep", Schedules.cron("0 0 2 * * SUN", ZoneOffset.UTC)) {
            integrity.verify(PlatformChain.ID, full = true)
            sweeper.fanOut(VERIFY_FULL)
        }

    @Bean
    fun auditPartitionCheckTask(
        jobs: Jobs,
        check: AuditPartitionCheck,
    ): RecurringTask<Void> = jobs.platformTask("audit-partition-check", Schedules.fixedDelay(Duration.ofDays(1))) { check.check() }

    companion object {
        const val ANCHOR = "audit-anchor"
        const val VERIFY = "audit-verify"
        const val VERIFY_FULL = "audit-verify-full"
    }
}

/** Schedules one per-tenant task for every active tenant; instance id = tenant id, so overlapping sweeps collapse. */
@Component
internal class AuditSweeper(
    private val tenants: ActiveTenants,
    private val scheduler: ObjectProvider<SchedulerClient>,
    private val tasks: ObjectProvider<OneTimeTask<AuditChainTask>>,
) {
    fun fanOut(taskName: String) {
        val task = tasks.orderedStream().toList().single { it.name == taskName }
        val client = scheduler.getObject()
        tenants.ids().forEach { tenant ->
            client.scheduleIfNotExists(task.schedulableInstance(tenant.toString(), AuditChainTask(tenant.value)))
        }
    }
}
