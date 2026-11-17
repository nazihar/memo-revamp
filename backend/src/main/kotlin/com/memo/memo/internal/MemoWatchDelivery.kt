package com.memo.memo.internal

import com.github.kagkarlsson.scheduler.SchedulerClient
import com.github.kagkarlsson.scheduler.task.helper.OneTimeTask
import com.github.kagkarlsson.scheduler.task.helper.RecurringTask
import com.github.kagkarlsson.scheduler.task.schedule.Schedules
import com.memo.common.context.Actor
import com.memo.common.context.ActorContext
import com.memo.common.context.ActorScope
import com.memo.common.jobs.ActiveTenants
import com.memo.common.jobs.Jobs
import com.memo.common.jobs.TenantScopedPayload
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantTransactions
import com.memo.notification.Mail
import com.memo.notification.MailTemplate
import com.memo.notification.NotificationKind
import com.memo.notification.Notifications
import com.memo.notification.RecipientLookup
import com.memo.org.OrgDirectory
import com.memo.tenancy.TenancyProperties
import com.memo.tenancy.TenantRegistry
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Duration
import java.time.ZonedDateTime
import java.util.UUID

/** Task data of one tenant's watch work (flush, digest or sweep); [slot] makes the digest once an hour. */
data class WatchWorkTask(
    override val tenantId: UUID,
    val slot: Long = 0,
) : TenantScopedPayload

/**
 * Tells followers (design E05 §4): in the app once a memo has been quiet for [QUIET] (all its news in one item, with why
 * the person follows it), by email at once for those who chose it, and in a daily digest at [DIGEST_HOUR] in the
 * tenant's time zone for the rest (the default). Watches of memos a person may no longer read are dropped nightly.
 * Inside a tenant transaction unless said otherwise.
 */
@Service
class MemoWatchDelivery(
    private val watches: MemoWatchStore,
    private val news: MemoWatchEventStore,
    private val memos: MemoStore,
    private val readable: MemoReadable,
    private val recipients: RecipientLookup,
    private val notifications: Notifications,
    private val mail: Mail,
    private val registry: TenantRegistry,
    private val tenancy: TenancyProperties,
    private val org: OrgDirectory,
    private val clock: Clock,
) {
    /** In-app items (and emails at once) for news that has been quiet for [QUIET]; how many items were made. */
    fun flush(): Int {
        val pending = news.pending(clock.instant().minus(QUIET))
        pending.forEach { p ->
            val recipient = recipients.recipient(p.userId)
            val memo = memos.find(p.memoId)
            if (recipient != null && memo != null) {
                val reference = memo.reference ?: "–"
                val link = "/memos/${p.memoId}"
                notifications.notify(
                    recipient,
                    NotificationKind.MEMO_WATCHED,
                    mapOf("reference" to reference, "count" to p.events.toString(), "reason" to p.reason.name),
                    link,
                )
                // At once, or never: these are done with email; the rest wait for the digest.
                val email = watches.settings(p.userId).email
                if (email == WatchEmail.IMMEDIATE) {
                    val values = params(recipient.displayName, link) + ("reference" to reference)
                    mail.enqueue(MailTemplate.WATCH_UPDATE, recipient.email, values, recipient.locale)
                }
                if (email != WatchEmail.DIGEST) news.markEmailed(p.userId, p.memoId)
            }
            news.markNotified(p.userId, p.memoId)
        }
        return pending.size
    }

    /** The daily digest, when it is [DIGEST_HOUR] in the tenant's time zone (or [force]); how many emails were sent. */
    fun digest(force: Boolean = false): Int {
        if (!force && ZonedDateTime.now(clock.withZone(org.zone())).hour != DIGEST_HOUR) return 0
        var sent = 0
        news.unemailed().forEach { (user, memos) ->
            val recipient = recipients.recipient(user)
            if (recipient != null && watches.settings(user).email == WatchEmail.DIGEST) {
                mail.enqueue(
                    MailTemplate.WATCH_DIGEST,
                    recipient.email,
                    params(recipient.displayName, "/inbox?tab=watching") + ("count" to memos.toString()),
                    recipient.locale,
                )
                sent++
            }
            news.markEmailed(user)
        }
        return sent
    }

    /** Drops watches of memos each follower may no longer read, judged as that follower; how many were dropped. */
    fun sweep(): Int =
        watches.allWatchers().sumOf { user ->
            ActorContext.call(ActorScope(Actor.User(user), null)) {
                watches.unreadable(user, readable.condition(user)).count { watches.remove(it, user) }
            }
        }

    private fun params(
        name: String,
        link: String,
    ): Map<String, String> {
        val tenant = registry.current()
        return mapOf(
            "name" to name,
            "organisation" to tenant?.name.orEmpty(),
            "link" to (tenant?.let { tenancy.publicUrl(it.slug) }.orEmpty() + link),
        )
    }

    companion object {
        val QUIET: Duration = Duration.ofMinutes(3)
        const val DIGEST_HOUR = 8
    }
}

/** The watch jobs (ADR-0018, design E05 §4): a flush every minute, a digest check every hour, a sweep every night. */
@Configuration(proxyBeanMethods = false)
class MemoWatchJobs(
    private val tenants: ActiveTenants,
    private val scheduler: ObjectProvider<SchedulerClient>,
) {
    @Bean
    fun memoWatchFlushTask(
        jobs: Jobs,
        delivery: MemoWatchDelivery,
        transactions: TenantTransactions,
    ): OneTimeTask<WatchWorkTask> =
        jobs.tenantTask(FLUSH, WatchWorkTask::class.java) { transactions.inTenant(TenantContext.current()) { delivery.flush() } }

    @Bean
    fun memoWatchDigestTask(
        jobs: Jobs,
        delivery: MemoWatchDelivery,
        transactions: TenantTransactions,
    ): OneTimeTask<WatchWorkTask> =
        jobs.tenantTask(DIGEST, WatchWorkTask::class.java) { transactions.inTenant(TenantContext.current()) { delivery.digest() } }

    @Bean
    fun memoWatchSweepTask(
        jobs: Jobs,
        delivery: MemoWatchDelivery,
        transactions: TenantTransactions,
    ): OneTimeTask<WatchWorkTask> =
        jobs.tenantTask(SWEEP, WatchWorkTask::class.java) { transactions.inTenant(TenantContext.current()) { delivery.sweep() } }

    @Bean
    fun memoWatchFlushSweep(
        jobs: Jobs,
        @Qualifier("memoWatchFlushTask") task: ObjectProvider<OneTimeTask<WatchWorkTask>>,
    ): RecurringTask<Void> = fanOut(jobs, "memo-watch-flush-sweep", Duration.ofMinutes(1), task) { 0 }

    @Bean
    fun memoWatchDigestSweep(
        jobs: Jobs,
        @Qualifier("memoWatchDigestTask") task: ObjectProvider<OneTimeTask<WatchWorkTask>>,
        clock: Clock,
    ): RecurringTask<Void> =
        fanOut(jobs, "memo-watch-digest-sweep", Duration.ofHours(1), task) {
            clock.instant().epochSecond / Duration.ofHours(1).seconds
        }

    @Bean
    fun memoWatchNightlySweep(
        jobs: Jobs,
        @Qualifier("memoWatchSweepTask") task: ObjectProvider<OneTimeTask<WatchWorkTask>>,
        clock: Clock,
    ): RecurringTask<Void> =
        fanOut(jobs, "memo-watch-access-sweep", Duration.ofDays(1), task) {
            clock.instant().epochSecond / Duration.ofDays(1).seconds
        }

    /** A platform task that schedules [task] for every active tenant, once per [slot]. */
    private fun fanOut(
        jobs: Jobs,
        name: String,
        every: Duration,
        task: ObjectProvider<OneTimeTask<WatchWorkTask>>,
        slot: () -> Long,
    ): RecurringTask<Void> =
        jobs.platformTask(name, Schedules.fixedDelay(every)) {
            val client = scheduler.getObject()
            val work = task.getObject()
            val now = slot()
            tenants.ids().forEach { tenant ->
                client.scheduleIfNotExists(work.schedulableInstance("$tenant:$now", WatchWorkTask(tenant.value, now)))
            }
        }

    private companion object {
        const val FLUSH = "memo-watch-flush"
        const val DIGEST = "memo-watch-digest"
        const val SWEEP = "memo-watch-sweep"
    }
}
