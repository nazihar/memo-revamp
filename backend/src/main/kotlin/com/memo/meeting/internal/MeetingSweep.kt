package com.memo.meeting.internal

import com.github.kagkarlsson.scheduler.SchedulerClient
import com.github.kagkarlsson.scheduler.task.helper.OneTimeTask
import com.github.kagkarlsson.scheduler.task.helper.RecurringTask
import com.github.kagkarlsson.scheduler.task.schedule.Schedules
import com.memo.audit.AuditLog
import com.memo.common.authz.AccessAdministration
import com.memo.common.authz.Permission
import com.memo.common.jobs.ActiveTenants
import com.memo.common.jobs.Jobs
import com.memo.common.jobs.TenantScopedPayload
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantDsl
import com.memo.common.tenant.TenantTransactions
import com.memo.jooq.tables.references.MEETING
import com.memo.jooq.tables.references.MEETING_AGENDA
import com.memo.meeting.MeetingStatus
import com.memo.meeting.internal.invite.InvitationSafetyNet
import com.memo.notification.NotificationKind
import org.jooq.Condition
import org.jooq.impl.DSL
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Component
import org.springframework.stereotype.Repository
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * The meetings the hourly sweep looks at (design E03 §9), inside a tenant transaction, through `meeting_status_idx`
 * (tenant, status, end). Each reminder is marked on the meeting row in a guarded update, so it is sent once per date.
 */
@Repository
class MeetingSweepStore(
    private val tenantDsl: TenantDsl,
) {
    /** Draft and scheduled meetings not over at [now], starting before [horizon], not yet reminded and not yet published. */
    fun agendaDue(
        now: Instant,
        horizon: Instant,
        limit: Int,
    ): List<MeetingRow> =
        tenantDsl
            .current()
            .selectFrom(MEETING)
            .where(
                MEETING.STATUS.`in`(DUE_STATUSES),
                MEETING.ENDS_AT.gt(MeetingStore.utc(now)),
                MEETING.STARTS_AT.lt(MeetingStore.utc(horizon)),
                MEETING.AGENDA_DUE_REMINDED_AT.isNull,
                unpublished(),
            ).orderBy(MEETING.STARTS_AT, MEETING.ID)
            .limit(limit)
            .fetch { it.row() }

    /** Scheduled meetings that ended before [before] and were not reminded: nobody marked them as held. */
    fun notHeld(
        before: Instant,
        limit: Int,
    ): List<MeetingRow> =
        tenantDsl
            .current()
            .selectFrom(MEETING)
            .where(
                MEETING.STATUS.eq(MeetingStatus.SCHEDULED.name),
                MEETING.ENDS_AT.lt(MeetingStore.utc(before)),
                MEETING.NOT_HELD_REMINDED_AT.isNull,
            ).orderBy(MEETING.ENDS_AT, MEETING.ID)
            .limit(limit)
            .fetch { it.row() }

    /**
     * Scheduled meetings with a published agenda starting after [now] and before [horizon]: those the declarations-due
     * reminder may be for (design E04 §9), through `meeting_status_start_idx` (tenant, status, start).
     */
    fun declarationsDue(
        now: Instant,
        horizon: Instant,
        limit: Int,
    ): List<MeetingRow> =
        tenantDsl
            .current()
            .selectFrom(MEETING)
            .where(
                MEETING.STATUS.eq(MeetingStatus.SCHEDULED.name),
                MEETING.STARTS_AT.gt(MeetingStore.utc(now)),
                MEETING.STARTS_AT.lt(MeetingStore.utc(horizon)),
                unpublished().not(),
            ).orderBy(MEETING.STARTS_AT, MEETING.ID)
            .limit(limit)
            .fetch { it.row() }

    /** Marks [id]'s agenda reminder sent at [at]; false when it already was, or the meeting moved on meanwhile. */
    fun markAgendaDue(
        id: UUID,
        at: Instant,
    ): Boolean =
        tenantDsl
            .current()
            .update(MEETING)
            .set(MEETING.AGENDA_DUE_REMINDED_AT, MeetingStore.utc(at))
            .where(MEETING.ID.eq(id), MEETING.STATUS.`in`(DUE_STATUSES), MEETING.AGENDA_DUE_REMINDED_AT.isNull, unpublished())
            .execute() == 1

    /** Marks [id]'s "not marked as held" reminder sent at [at]; false when it already was, or the meeting moved on. */
    fun markNotHeld(
        id: UUID,
        at: Instant,
    ): Boolean =
        tenantDsl
            .current()
            .update(MEETING)
            .set(MEETING.NOT_HELD_REMINDED_AT, MeetingStore.utc(at))
            .where(MEETING.ID.eq(id), MEETING.STATUS.eq(MeetingStatus.SCHEDULED.name), MEETING.NOT_HELD_REMINDED_AT.isNull)
            .execute() == 1

    /** Forgets [id]'s reminders: the meeting was rescheduled, and its new date reminds again. */
    fun clear(id: UUID) {
        tenantDsl
            .current()
            .update(MEETING)
            .setNull(MEETING.AGENDA_DUE_REMINDED_AT)
            .setNull(MEETING.NOT_HELD_REMINDED_AT)
            .where(MEETING.ID.eq(id))
            .execute()
    }

    /** The meeting has no published revision: no agenda yet, or a draft never published. */
    private fun unpublished(): Condition =
        DSL.notExists(
            DSL
                .selectOne()
                .from(MEETING_AGENDA)
                .where(
                    MEETING_AGENDA.TENANT_ID.eq(MEETING.TENANT_ID),
                    MEETING_AGENDA.MEETING_ID.eq(MEETING.ID),
                    MEETING_AGENDA.CURRENT_REVISION.gt(0),
                ),
        )

    private companion object {
        val DUE_STATUSES = listOf(MeetingStatus.DRAFT.name, MeetingStatus.SCHEDULED.name)
    }
}

/**
 * The hourly meeting sweep (design E03 §1.1, §9), in one tenant: (a) a draft or scheduled meeting whose paper deadline has
 * passed (the end of the deadline's working day, in the meeting's zone) with its agenda not published tells the forum's
 * secretaries (`MEETING_AGENDA_DUE`); (b) a meeting still SCHEDULED a day after it ended tells them it is not marked as
 * held (`MEETING_NOT_HELD`). Without secretaries in office, the holders of `forums.manage` are told. Each reminder goes
 * once per meeting date (the mark on the meeting row; a reschedule clears it) and is audited. (d, design E04 §9) From
 * 09:00 on the working day before a scheduled meeting with a published agenda, its members and officers with items to
 * review are reminded to declare their interests (`MEETING_DECLARATIONS_DUE`, [DeclarationReminders]), once per person
 * and revision.
 */
@Component
class MeetingSweep(
    private val store: MeetingSweepStore,
    private val lateness: AgendaLateness,
    private val notifier: MeetingNotifier,
    private val access: AccessAdministration,
    private val invitations: InvitationSafetyNet,
    private val reminders: DeclarationReminders,
    private val audit: AuditLog,
    private val clock: Clock,
) {
    /**
     * Sends what is due in the current tenant; how many reminders went out. Also schedules again any meeting invitation
     * whose send is overdue by 15 minutes (design E05 §9 (h)).
     */
    fun sweep(): Int {
        invitations.rescheduleOverdue()
        return agendaDue() + notHeld() + reminders.due(clock.instant())
    }

    private fun agendaDue(): Int {
        val now = clock.instant()
        var sent = 0
        store.agendaDue(now, now.plus(HORIZON), BATCH).forEach { meeting ->
            val deadline = lateness.deadline(meeting) ?: return@forEach
            val passed = now.atZone(meeting.zone).toLocalDate().isAfter(deadline)
            if (!passed || !store.markAgendaDue(meeting.id, now)) return@forEach
            notifier.reminder(NotificationKind.MEETING_AGENDA_DUE, meeting, lateness.forum(meeting), ::administrators)
            audit.record(
                "meeting.reminded.agenda_due",
                MeetingLifecycle.OBJECT_TYPE,
                meeting.id.toString(),
                after = mapOf("deadline" to deadline.toString(), "status" to meeting.status.name),
            )
            sent++
        }
        return sent
    }

    private fun notHeld(): Int {
        val now = clock.instant()
        var sent = 0
        store.notHeld(now.minus(NOT_HELD_AFTER), BATCH).forEach { meeting ->
            if (!store.markNotHeld(meeting.id, now)) return@forEach
            notifier.reminder(NotificationKind.MEETING_NOT_HELD, meeting, lateness.forum(meeting), ::administrators)
            audit.record(
                "meeting.reminded.not_held",
                MeetingLifecycle.OBJECT_TYPE,
                meeting.id.toString(),
                after = mapOf("endsAt" to meeting.endsAt.toString()),
            )
            sent++
        }
        return sent
    }

    private fun administrators(): Set<UUID> = access.tenantWideHolders(Permission.FORUMS_MANAGE)

    companion object {
        /** Meetings looked at per run and kind; the rest wait for the next hour. */
        const val BATCH = 500

        /** The latest deadline is 30 working days before a meeting: looking 11 weeks ahead covers it with holidays. */
        val HORIZON: Duration = Duration.ofDays(77)

        /** A meeting is "not marked as held" a day after its end (design E03 §1.1). */
        val NOT_HELD_AFTER: Duration = Duration.ofDays(1)
    }
}

/** Task data of the per-tenant meeting sweep. */
data class MeetingSweepTenantTask(
    override val tenantId: UUID,
) : TenantScopedPayload

/** The meeting sweep's tasks (ADR-0018): an hourly platform task fans out to one `meeting-sweep` per active tenant. */
@Configuration(proxyBeanMethods = false)
class MeetingSweepJobs {
    @Bean
    fun meetingSweepTask(
        jobs: Jobs,
        sweep: MeetingSweep,
        transactions: TenantTransactions,
    ): OneTimeTask<MeetingSweepTenantTask> =
        jobs.tenantTask(SWEEP, MeetingSweepTenantTask::class.java) {
            transactions.inTenant(TenantContext.current()) { sweep.sweep() }
        }

    @Bean
    fun meetingSweepTenants(
        jobs: Jobs,
        tenants: ActiveTenants,
        scheduler: ObjectProvider<SchedulerClient>,
        task: ObjectProvider<OneTimeTask<MeetingSweepTenantTask>>,
    ): RecurringTask<Void> =
        jobs.platformTask("$SWEEP-tenants", Schedules.fixedDelay(EVERY)) {
            val client = scheduler.getObject()
            val sweep = task.getObject()
            tenants.ids().forEach { tenant ->
                client.scheduleIfNotExists(sweep.schedulableInstance(tenant.toString(), MeetingSweepTenantTask(tenant.value)))
            }
        }

    private companion object {
        const val SWEEP = "meeting-sweep"
        val EVERY: Duration = Duration.ofHours(1)
    }
}
