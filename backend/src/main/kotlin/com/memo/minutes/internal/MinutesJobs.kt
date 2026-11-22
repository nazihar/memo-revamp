package com.memo.minutes.internal

import com.github.kagkarlsson.scheduler.SchedulerClient
import com.github.kagkarlsson.scheduler.task.helper.OneTimeTask
import com.memo.common.jobs.Jobs
import com.memo.common.jobs.TenantScopedPayload
import com.memo.common.tenant.TenantContext
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Lazy
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.util.UUID

/** The `minutes-draft` job's data (ADR-0018): ids only; [seq] is the request it answers (a later one supersedes it). */
data class DraftTask(
    override val tenantId: UUID,
    val meetingId: UUID,
    val seq: Int,
) : TenantScopedPayload

/** The `minutes-copies` job's data: the build asked for ([seq], the minutes' `copies_seq`). */
data class CopiesTask(
    override val tenantId: UUID,
    val meetingId: UUID,
    val seq: Int,
) : TenantScopedPayload

/** The `minutes-structure` job's data: the draft's version to check. */
data class StructureTask(
    override val tenantId: UUID,
    val meetingId: UUID,
    val versionId: UUID,
) : TenantScopedPayload

/**
 * Schedules the minutes' jobs (design E06 §9) in the caller's transaction (db-scheduler joins Spring transactions,
 * ADR-0018: a rolled-back request schedules nothing): `minutes-draft` per request, `minutes-structure` per saved version.
 */
@Component
class MinutesJobs(
    private val scheduler: ObjectProvider<SchedulerClient>,
    @param:Lazy @param:Qualifier("minutesDraftTask") private val draftTask: OneTimeTask<DraftTask>,
    @param:Lazy @param:Qualifier("minutesStructureTask") private val structureTask: OneTimeTask<StructureTask>,
    @param:Lazy @param:Qualifier("minutesCopiesTask") private val copiesTask: OneTimeTask<CopiesTask>,
    private val clock: Clock,
) {
    /** False only while a test runs the jobs itself (test contexts share the scheduler table). */
    @Volatile var jobs: Boolean = true

    fun draft(
        meetingId: UUID,
        seq: Int,
    ) {
        if (!jobs) return
        val data = DraftTask(TenantContext.current().value, meetingId, seq)
        scheduler.ifAvailable?.scheduleIfNotExists(draftTask.schedulableInstance("$meetingId:$seq", data))
    }

    fun check(
        meetingId: UUID,
        versionId: UUID,
    ) {
        if (!jobs) return
        val data = StructureTask(TenantContext.current().value, meetingId, versionId)
        scheduler.ifAvailable?.scheduleIfNotExists(structureTask.schedulableInstance("$meetingId:$versionId", data))
    }

    /**
     * The readers' copies, built now or, [delayed], two minutes later (edits made while circulated are debounced: a later
     * save asks again, and each build reads the then current version).
     */
    fun copies(
        meetingId: UUID,
        seq: Int,
        delayed: Boolean = false,
    ) {
        if (!jobs) return
        val data = CopiesTask(TenantContext.current().value, meetingId, seq)
        val at = if (delayed) clock.instant().plus(COPIES_DEBOUNCE) else clock.instant()
        scheduler.ifAvailable?.scheduleIfNotExists(copiesTask.instance("$meetingId:$seq", data), at)
    }

    private companion object {
        val COPIES_DEBOUNCE: Duration = Duration.ofMinutes(2)
    }
}

/**
 * The minutes' jobs (ADR-0018): the draft is filled and stored as its requester, retried with backoff, and given up with a
 * code after the last try (or at once when retrying cannot help: a template that does not fill, a control that cannot be
 * found); the structure check reads one saved version.
 */
@Configuration(proxyBeanMethods = false)
class MinutesJobTasks {
    @Bean
    fun minutesDraftTask(
        jobs: Jobs,
        drafting: MinutesDrafting,
    ): OneTimeTask<DraftTask> =
        jobs.tenantTaskCounting(DRAFT, DraftTask::class.java, MAX_RETRIES) { data, failures ->
            drafting.run(data, last = failures + 1 >= MAX_RETRIES)
        }

    @Bean
    fun minutesCopiesTask(
        jobs: Jobs,
        copies: MinutesCopies,
    ): OneTimeTask<CopiesTask> =
        jobs.tenantTaskCounting(COPIES, CopiesTask::class.java, MAX_RETRIES) { data, failures ->
            copies.build(data.meetingId, last = failures + 1 >= MAX_RETRIES)
        }

    @Bean
    fun minutesStructureTask(
        jobs: Jobs,
        checks: MinutesStructureChecks,
    ): OneTimeTask<StructureTask> = jobs.tenantTask(STRUCTURE, StructureTask::class.java) { data -> checks.check(data.meetingId) }

    companion object {
        const val DRAFT = "minutes-draft"
        const val STRUCTURE = "minutes-structure"
        const val COPIES = "minutes-copies"
        const val MAX_RETRIES = 3
    }
}
