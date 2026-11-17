package com.memo.forum.internal

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
import com.memo.forum.ForumStatus
import com.memo.identity.UserSummaries
import com.memo.jooq.tables.references.FORUM_TERM_REMINDER
import com.memo.org.OrgDirectory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Term reminders (ADR-0056, design E01 §9): for each row in office whose planned term ends within its type's longest
 * reminder period, the nearest reminder day not yet sent is sent once, to the forum's secretaries (or the holders of
 * `forums.manage` when it has none). A table of sent reminders keeps re-runs and missed days from sending twice.
 */
@Component
class ForumTermReminders(
    private val store: ForumStore,
    private val memberStore: ForumMemberStore,
    private val types: ForumTypeVersionStore,
    private val notifier: ForumNotifier,
    private val org: OrgDirectory,
    private val users: UserSummaries,
    private val access: AccessAdministration,
    private val tenantDsl: TenantDsl,
    private val audit: AuditLog,
    private val clock: Clock,
) {
    /** Sends what is due today in the current tenant; returns how many reminders were sent. */
    fun remindDue(): Int {
        val today = org.today()
        var sent = 0
        store.all().filter { it.status == ForumStatus.ACTIVE }.forEach { forum ->
            val days =
                types
                    .published(forum.typeId)
                    ?.settings
                    ?.termPolicy
                    ?.reminderDays
                    .orEmpty()
                    .sorted()
            val longest = days.maxOrNull() ?: return@forEach
            memberStore
                .members(forum.id)
                .filter { !it.ended && it.inOffice(today) }
                .forEach member@{ row ->
                    val end = row.termEndsOn ?: return@member
                    val left = ChronoUnit.DAYS.between(today, end).toInt()
                    if (left !in 0..longest) return@member
                    val reminder = days.first { it >= left }
                    if (!mark(row.id, reminder)) return@member
                    val person = row.userId ?: row.positionId?.let { org.holders(listOf(it), today)[it] }
                    val name = person?.let { users.of(listOf(it))[it]?.displayName } ?: "A member"
                    notifier.termEnding(forum, name) { access.tenantWideHolders(Permission.FORUMS_MANAGE) }
                    audit.record(
                        "forum.term.reminded",
                        ForumMembers.OBJECT_TYPE,
                        row.id.toString(),
                        after = mapOf("forumId" to forum.id.toString(), "daysBefore" to reminder, "termEndsOn" to end.toString()),
                    )
                    sent++
                }
        }
        return sent
    }

    /** Records that [memberId]'s reminder for [daysBefore] went out; false when it already had. */
    private fun mark(
        memberId: UUID,
        daysBefore: Int,
    ): Boolean =
        tenantDsl
            .current()
            .insertInto(FORUM_TERM_REMINDER)
            .set(FORUM_TERM_REMINDER.TENANT_ID, tenantDsl.tenant().value)
            .set(FORUM_TERM_REMINDER.MEMBER_ID, memberId)
            .set(FORUM_TERM_REMINDER.DAYS_BEFORE, daysBefore)
            .set(FORUM_TERM_REMINDER.SENT_AT, OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC))
            .onConflictDoNothing()
            .execute() == 1
}

/** Task data of the per-tenant reminder task. */
data class ForumRemindTenantTask(
    override val tenantId: UUID,
) : TenantScopedPayload

/** The reminder tasks (ADR-0018): an hourly platform sweep fans out to one task per active tenant, run as the system. */
@Configuration(proxyBeanMethods = false)
class ForumReminderJobs {
    @Bean
    fun forumTermReminderTask(
        jobs: Jobs,
        reminders: ForumTermReminders,
        transactions: TenantTransactions,
    ): OneTimeTask<ForumRemindTenantTask> =
        jobs.tenantTask(REMIND, ForumRemindTenantTask::class.java) {
            transactions.inTenant(TenantContext.current()) { reminders.remindDue() }
        }

    @Bean
    fun forumTermReminderSweep(
        jobs: Jobs,
        tenants: ActiveTenants,
        scheduler: ObjectProvider<SchedulerClient>,
        task: ObjectProvider<OneTimeTask<ForumRemindTenantTask>>,
    ): RecurringTask<Void> =
        jobs.platformTask("forum-term-reminders-sweep", Schedules.fixedDelay(EVERY)) {
            val client = scheduler.getObject()
            val remind = task.getObject()
            tenants.ids().forEach { tenant ->
                client.scheduleIfNotExists(remind.schedulableInstance(tenant.toString(), ForumRemindTenantTask(tenant.value)))
            }
        }

    private companion object {
        const val REMIND = "forum-term-reminders"
        val EVERY: Duration = Duration.ofHours(1)
    }
}
