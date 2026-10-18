package com.memo.system.jobs

import com.github.kagkarlsson.scheduler.task.helper.RecurringTask
import com.github.kagkarlsson.scheduler.task.schedule.Schedules
import com.memo.common.jobs.Jobs
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.modulith.events.CompletedEventPublications
import org.springframework.modulith.events.FailedEventPublications
import org.springframework.modulith.events.ResubmissionOptions
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * Keeps the transactional outbox moving (ADR-0018, research §R6). Spring Modulith's staleness monitor marks publications
 * stuck in processing as failed; this resubmits failed ones once per cluster (a db-scheduler task, not on restart), and
 * removes archived publications after their retention.
 */
@Component
class OutboxMaintenance(
    private val failed: FailedEventPublications,
    private val completed: CompletedEventPublications,
    @param:Value("\${memo.outbox.resubmit-min-age:PT1M}") private val resubmitMinAge: Duration,
    @param:Value("\${memo.outbox.archive-retention:P30D}") private val archiveRetention: Duration,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun resubmitFailed() {
        failed.resubmit(ResubmissionOptions.defaults().withMinAge(resubmitMinAge))
    }

    fun cleanUpArchive() {
        completed.deletePublicationsOlderThan(archiveRetention)
        log.debug("Archived event publications older than {} removed", archiveRetention)
    }
}

@Configuration(proxyBeanMethods = false)
class OutboxTasks {
    @Bean
    fun outboxResubmitTask(
        jobs: Jobs,
        outbox: OutboxMaintenance,
    ): RecurringTask<Void> = jobs.platformTask("outbox-resubmit", Schedules.fixedDelay(RESUBMIT_EVERY)) { outbox.resubmitFailed() }

    @Bean
    fun outboxArchiveCleanupTask(
        jobs: Jobs,
        outbox: OutboxMaintenance,
    ): RecurringTask<Void> = jobs.platformTask("outbox-archive-cleanup", Schedules.fixedDelay(CLEANUP_EVERY)) { outbox.cleanUpArchive() }

    private companion object {
        val RESUBMIT_EVERY: Duration = Duration.ofMinutes(5)
        val CLEANUP_EVERY: Duration = Duration.ofDays(1)
    }
}
