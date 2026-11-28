package com.memo.forum.internal

import com.github.kagkarlsson.scheduler.SchedulerClient
import com.github.kagkarlsson.scheduler.task.helper.OneTimeTask
import com.github.kagkarlsson.scheduler.task.helper.RecurringTask
import com.github.kagkarlsson.scheduler.task.schedule.Schedules
import com.memo.common.jobs.ActiveTenants
import com.memo.common.jobs.Jobs
import com.memo.common.jobs.TenantScopedPayload
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantDsl
import com.memo.common.tenant.TenantTransactions
import com.memo.forum.ForumChanged
import com.memo.jooq.tables.references.FORUM_READER_SYNC
import com.memo.org.OrgChanged
import com.memo.org.OrgDirectory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import org.springframework.stereotype.Repository
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * The day a tenant's committee readers were last brought in line with the calendar (`forum_reader_sync`, one row per
 * tenant), inside a tenant transaction. The row is locked for the run, so two nodes never run a tenant's day together.
 */
@Repository
class OfficerReaderSyncStore(
    private val tenantDsl: TenantDsl,
) {
    /** Locks the tenant's row, creating it at null first; the day last synced, or null before the first run. */
    fun lock(): LocalDate? {
        val s = FORUM_READER_SYNC
        tenantDsl
            .current()
            .insertInto(s)
            .set(s.TENANT_ID, tenantDsl.tenant().value)
            .onConflictDoNothing()
            .execute()
        return tenantDsl
            .current()
            .select(s.SYNCED_ON)
            .from(s)
            .where(s.TENANT_ID.eq(tenantDsl.tenant().value))
            .forUpdate()
            .fetchOne(s.SYNCED_ON)
    }

    /** Records [day] as synced at [at]; never moves the day back. */
    fun synced(
        day: LocalDate,
        at: OffsetDateTime,
    ) {
        val s = FORUM_READER_SYNC
        tenantDsl
            .current()
            .update(s)
            .set(s.SYNCED_ON, day)
            .set(s.SYNCED_AT, at)
            .where(s.SYNCED_ON.isNull.or(s.SYNCED_ON.lt(day)))
            .execute()
    }
}

/**
 * Committee officers' memo access follows the calendar and the organisation (ADR-0059, fix of the E08 T10 finding):
 *  - **daily**, at the start of the tenant's day (its `default.timezone`): every committee whose chair and secretaries
 *    differ between two days since the last run (a term that ended, an appointment that started, a position seat whose
 *    holder changed by date) has its queue's `FORUM` readers resynced. The first run, or one after more than
 *    [MAX_CATCH_UP_DAYS] days, resyncs every committee;
 *  - **at once**, in the changing transaction, when a position's holder changes ([OrgChanged] on an assignment): the
 *    committees with an officer seat of that position are resynced, and every committee with any seat of it is told
 *    [ForumChanged], so the meeting module recomputes the audience of its open meetings (design E03 §1.6).
 * Resyncing diffs the expected readers against the rows there ([com.memo.memo.MemoReaders.syncForum]): only the rows that
 * change are written, each change audited (`memo.reader.forum_added`, `memo.reader.forum_retired`).
 */
@Component
class OfficerReaders(
    private val readers: SubmissionReaders,
    private val forums: ForumStore,
    private val memberStore: ForumMemberStore,
    private val state: OfficerReaderSyncStore,
    private val org: OrgDirectory,
    private val events: ApplicationEventPublisher,
    private val clock: Clock,
) {
    /** Brings the current tenant's committee readers in line with today; the committees resynced. */
    fun daily(): Set<UUID> {
        val today = LocalDate.now(clock.withZone(org.zone()))
        val last = state.lock()
        if (last != null && !today.isAfter(last)) return emptySet()
        val catchUp = last == null || ChronoUnit.DAYS.between(last, today) > MAX_CATCH_UP_DAYS
        val changed =
            forums
                .all()
                .map { it.id }
                .filter { forum -> catchUp || changedSince(forum, checkNotNull(last), today) }
                .toSet()
        changed.forEach { readers.resync(it, today) }
        state.synced(today, OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC))
        return changed
    }

    /** A position's holder changed: its committees follow at once. */
    @EventListener
    fun onOrgChanged(event: OrgChanged) {
        if (event.objectType != ASSIGNMENT) return
        val position = org.positionOfAssignment(event.objectId) ?: return
        val today = org.today()
        val seats = memberStore.seatsOf(position).filter { it.lastDay?.isBefore(today) != true }
        seats
            .filter { it.role in SubmissionReaders.OFFICERS && it.inOffice(today) }
            .map { it.forumId }
            .toSet()
            .forEach { readers.resync(it, today) }
        seats.map { it.forumId }.toSet().forEach { events.publishEvent(ForumChanged(event.tenantId, it)) }
    }

    /** Whether [forumId]'s officers on some day after [last] up to [today] differ from the day before's. */
    private fun changedSince(
        forumId: UUID,
        last: LocalDate,
        today: LocalDate,
    ): Boolean {
        var before = readers.officersOn(forumId, last)
        var day = last.plusDays(1)
        while (!day.isAfter(today)) {
            val now = readers.officersOn(forumId, day)
            if (now != before) return true
            before = now
            day = day.plusDays(1)
        }
        return false
    }

    companion object {
        /** Longer gaps than this resync every committee rather than walk the days. */
        const val MAX_CATCH_UP_DAYS = 31L
        private const val ASSIGNMENT = "assignment"
    }
}

/** Task data of one tenant's officer-reader run. */
data class OfficerReadersTask(
    override val tenantId: UUID,
) : TenantScopedPayload

/**
 * The officer-reader tasks (ADR-0018): a platform sweep every [EVERY] fans out to one `forum-officer-readers` per active
 * tenant, run as the system. A tenant's run does its work once a day, at the first sweep of the tenant's new day, so
 * access follows the calendar within [EVERY] of midnight in the tenant's zone.
 */
@Configuration(proxyBeanMethods = false)
class OfficerReaderJobs {
    @Bean
    fun forumOfficerReadersTask(
        jobs: Jobs,
        officers: OfficerReaders,
        transactions: TenantTransactions,
    ): OneTimeTask<OfficerReadersTask> =
        jobs.tenantTask(TASK, OfficerReadersTask::class.java) {
            transactions.inTenant(TenantContext.current()) { officers.daily() }
        }

    @Bean
    fun forumOfficerReadersSweep(
        jobs: Jobs,
        tenants: ActiveTenants,
        scheduler: ObjectProvider<SchedulerClient>,
        task: ObjectProvider<OneTimeTask<OfficerReadersTask>>,
    ): RecurringTask<Void> =
        jobs.platformTask("$TASK-sweep", Schedules.fixedDelay(EVERY)) {
            val client = scheduler.getObject()
            val work = task.getObject()
            tenants.ids().forEach { tenant ->
                client.scheduleIfNotExists(work.schedulableInstance(tenant.toString(), OfficerReadersTask(tenant.value)))
            }
        }

    private companion object {
        const val TASK = "forum-officer-readers"
        val EVERY: Duration = Duration.ofMinutes(15)
    }
}
