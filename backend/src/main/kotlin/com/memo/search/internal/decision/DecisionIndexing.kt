package com.memo.search.internal.decision

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
import com.memo.meeting.AgendaPublished
import com.memo.meeting.MeetingChange
import com.memo.meeting.MeetingChanged
import com.memo.minutes.DecisionRecorded
import com.memo.minutes.DecisionReleased
import com.memo.minutes.Decisions
import com.memo.minutes.MinutesApproved
import com.memo.minutes.ResolutionChanged
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Task data of a tenant's register backfill (design E08 §1.2, §9): the missing or stale rows by default; with [rebuild],
 * every row after [after] in id order (after a PostgreSQL major upgrade or a change to the body's make-up, the runbook).
 */
data class DecisionBackfillTask(
    override val tenantId: UUID,
    val rebuild: Boolean = false,
    val after: UUID? = null,
    /** Rows a rebuild rewrote in its earlier chunks (audited once, at its end). */
    val rows: Int = 0,
) : TenantScopedPayload

/**
 * Keeps the decision register with its sources (ADR-0069, design E08 §1.2): synchronously in the publisher's tenant
 * transaction, as the memo search does (a row is written, and rolled back, with its change), for every new entry
 * (corrections included), number given or voided, release, minutes' approval, agenda revision and meeting change; and a
 * backfill for what came before, or what a row missed. Recusal is not indexed: it is evaluated per request.
 */
@Service
class DecisionIndexing(
    private val index: DecisionIndex,
    private val decisions: Decisions,
    private val tenantDsl: TenantDsl,
    private val audit: AuditLog,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** A new entry: the row, and the decisions its entry ratifies now or ratified before (their "ratified by" moved). */
    @EventListener
    fun onRecorded(event: DecisionRecorded) {
        index.put(event.decisionId)
        val ratified =
            listOfNotNull(
                decisions.find(event.decisionId)?.current?.ratifiesDecisionId,
                decisions.entry(event.decisionId, event.seq - 1)?.ratifiesDecisionId,
            ).toSet()
        ratified.forEach { index.put(it) }
    }

    /** A number given or voided: the row's number columns. */
    @EventListener
    fun onResolution(event: ResolutionChanged) {
        index.put(event.decisionId)
    }

    /** Sent to its memo: the row's state. */
    @EventListener
    fun onReleased(event: DecisionReleased) {
        index.put(event.decisionId)
    }

    /** The minutes were approved: every decision of the meeting is final (one set-based write). */
    @EventListener
    fun onMinutesApproved(event: MinutesApproved) {
        val at = decisions.of(event.meetingId).mapNotNull { it.finalAt }.minOrNull() ?: clock.instant()
        index.putFinal(event.meetingId, at)
    }

    /** A new revision: the decided items' number, title, level and restriction (one set-based write). */
    @EventListener
    fun onAgenda(event: AgendaPublished) {
        index.putItems(event.meetingId)
    }

    /** The meeting's label, date or level may have changed. */
    @EventListener
    fun onMeeting(event: MeetingChanged) {
        if (event.change in MEETING_COLUMNS) index.putItems(event.meetingId)
    }

    /**
     * Writes up to [limit] rows that are missing or behind their decision, in id order, inside a tenant transaction;
     * returns how many it wrote. A decision whose meeting or item cannot be read is left out (and logged): the next run
     * meets it again, and the metric keeps counting it.
     */
    fun backfill(limit: Int = CHUNK): Int =
        index.unindexed(limit).count { id ->
            index.put(id).also { written -> if (!written) log.warn("search: decision {} has no meeting or item to index", id) }
        }

    /** Rewrites up to [limit] rows after [after] in id order, inside a tenant transaction; returns the ids it rewrote. */
    fun rebuild(
        after: UUID?,
        limit: Int = CHUNK,
    ): List<UUID> = decisions.ids(after, limit).onEach { index.put(it) }

    /** How many decisions still lack an up-to-date row (counted up to the function's 1,000), inside a tenant transaction. */
    fun unindexed(): Int = index.unindexed(MAX_COUNTED).size

    /** Audits a backfill run that wrote [rows] rows (`search.decisions.backfilled`, the system actor), inside a transaction. */
    fun backfilled(
        rows: Int,
        rebuild: Boolean,
    ) {
        val tenant = tenantDsl.tenant().value.toString()
        audit.record("search.decisions.backfilled", "tenant", tenant, after = mapOf("rows" to rows, "rebuild" to rebuild))
    }

    companion object {
        /** Decisions per chunk (design E08 §1.2). */
        const val CHUNK = 500
        private const val MAX_COUNTED = 1000
        private val MEETING_COLUMNS = setOf(MeetingChange.UPDATED, MeetingChange.RESCHEDULED, MeetingChange.NUMBERED)
    }
}

/**
 * The register's jobs (design E08 §1.2, §9): `search-decisions-backfill` per tenant, chunks of 500 each in its own
 * transaction, resumable (the work list is what is still missing; a rebuild schedules its next chunk by id), idempotent
 * (each chunk upserts); and the check, at start-up and then daily, which reports the rows still missing as a metric and
 * schedules the backfill for each tenant.
 */
@Configuration(proxyBeanMethods = false)
class DecisionJobs {
    @Bean
    fun searchDecisionsBackfillTask(
        jobs: Jobs,
        indexing: ObjectProvider<DecisionIndexing>,
        transactions: TenantTransactions,
        scheduler: ObjectProvider<SchedulerClient>,
        meters: MeterRegistry,
        @Qualifier("searchDecisionsBackfillTask") self: ObjectProvider<OneTimeTask<DecisionBackfillTask>>,
    ): OneTimeTask<DecisionBackfillTask> {
        // The rows still missing per tenant, as of each tenant's last run; the metric is their sum.
        val missing = meters.gauge(UNINDEXED_METRIC, ConcurrentHashMap<UUID, Long>()) { it.values.sum().toDouble() }
        return jobs.tenantTask(BACKFILL, DecisionBackfillTask::class.java) { data ->
            val tenant = TenantContext.current()
            val work = indexing.getObject()
            if (data.rebuild) {
                val done = transactions.inTenant(tenant) { work.rebuild(data.after) }
                val last = done.lastOrNull()
                if (done.size == DecisionIndexing.CHUNK && last != null) {
                    // The next chunk is a new execution, so a stopped rebuild resumes where it was.
                    val next = data.copy(after = last, rows = data.rows + done.size)
                    scheduler.getObject().scheduleIfNotExists(self.getObject().schedulableInstance("rebuild:$tenant:$last", next))
                } else {
                    transactions.inTenant(tenant) { work.backfilled(data.rows + done.size, rebuild = true) }
                }
            } else {
                var written = 0
                var chunks = 0
                do {
                    val n = transactions.inTenant(tenant) { work.backfill() }
                    written += n
                    chunks++
                } while (n == DecisionIndexing.CHUNK && chunks < CHUNKS_PER_RUN)
                transactions.inTenant(tenant) {
                    if (written > 0) work.backfilled(written, rebuild = false)
                    missing.put(tenant.value, work.unindexed().toLong())
                }
            }
        }
    }

    /** On unless `memo.search.backfill-sweep` is false (integration tests: they plant decisions without rows by SQL). */
    @Bean
    @ConditionalOnProperty("memo.search.backfill-sweep", havingValue = "true", matchIfMissing = true)
    fun searchDecisionsCheck(
        jobs: Jobs,
        tenants: ActiveTenants,
        scheduler: ObjectProvider<SchedulerClient>,
        @Qualifier("searchDecisionsBackfillTask") task: ObjectProvider<OneTimeTask<DecisionBackfillTask>>,
    ): RecurringTask<Void> =
        jobs.platformTask(CHECK, Schedules.fixedDelay(EVERY)) {
            val client = scheduler.getObject()
            val backfill = task.getObject()
            tenants.ids().forEach { tenant ->
                client.scheduleIfNotExists(backfill.schedulableInstance(tenant.toString(), DecisionBackfillTask(tenant.value)))
            }
        }

    companion object {
        const val BACKFILL = "search-decisions-backfill"
        const val CHECK = "search-decisions-check"
        const val UNINDEXED_METRIC = "memo.search.decisions.unindexed"

        /** 108 chunks: a tenant of 54,000 decisions in one run (design E08 §8: under ten minutes). */
        private const val CHUNKS_PER_RUN = 120
        private val EVERY: Duration = Duration.ofDays(1)
    }
}
