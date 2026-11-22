package com.memo.actions.internal

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
import com.memo.jooq.tables.references.ACTIONS_DIGEST
import com.memo.notification.NotificationKind
import com.memo.notification.NotificationLog
import com.memo.notification.Notifications
import com.memo.notification.RecipientLookup
import com.memo.notification.SentNotification
import com.memo.org.OrgDirectory
import com.memo.tenancy.TenancyProperties
import com.memo.tenancy.Tenant
import com.memo.tenancy.TenantRegistry
import com.memo.tenancy.TenantSettings
import com.memo.workflow.WorkingDays
import org.jooq.impl.DSL
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.support.ResourceBundleMessageSource
import org.springframework.stereotype.Repository
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale
import java.util.UUID

/**
 * The daily digest's keys (`actions_digest`, design E07 §1.15, §9), insert only: a person's digest of a day, and the
 * tenant's whole digest of a day done ([COMPLETE], the nil person), so the hourly runs after it read nothing again,
 * and the day's first run ([STARTED]), which runs the safety net once a day.
 */
@Repository
class ActionDigestStore(
    private val tenantDsl: TenantDsl,
) {
    /** Records [user]'s digest of [day]; false when they already had it (a run repeated sends nothing twice). */
    fun record(
        user: UUID,
        day: LocalDate,
        at: Instant,
    ): Boolean {
        val d = ACTIONS_DIGEST
        return tenantDsl
            .current()
            .insertInto(d)
            .set(d.TENANT_ID, tenantDsl.tenant().value)
            .set(d.USER_ID, user)
            .set(d.DAY, day)
            .set(d.SENT_AT, at.atOffset(ZoneOffset.UTC))
            .onConflictDoNothing()
            .execute() == 1
    }

    /** Whether the tenant's digest of [day] is done. */
    fun complete(day: LocalDate): Boolean =
        tenantDsl.current().fetchExists(ACTIONS_DIGEST, ACTIONS_DIGEST.USER_ID.eq(COMPLETE), ACTIONS_DIGEST.DAY.eq(day))

    /**
     * Each of [users]' latest digest day before [day], from [from] on (the digest's lookback: an earlier one changes nothing),
     * read by day (`actions_digest_day_idx`).
     */
    fun lastDays(
        users: Collection<UUID>,
        from: LocalDate,
        day: LocalDate,
    ): Map<UUID, LocalDate> {
        if (users.isEmpty()) return emptyMap()
        val d = ACTIONS_DIGEST
        return tenantDsl
            .current()
            .select(d.USER_ID, DSL.max(d.DAY))
            .from(d)
            .where(d.USER_ID.`in`(users), d.DAY.ge(from), d.DAY.lt(day))
            .groupBy(d.USER_ID)
            .fetch { (user, last) -> checkNotNull(user) to checkNotNull(last) }
            .toMap()
    }

    /** Those of [users] who had their digest of [day]. */
    fun had(
        users: Collection<UUID>,
        day: LocalDate,
    ): Set<UUID> {
        if (users.isEmpty()) return emptySet()
        val d = ACTIONS_DIGEST
        return tenantDsl
            .current()
            .select(d.USER_ID)
            .from(d)
            .where(d.USER_ID.`in`(users), d.DAY.eq(day))
            .fetchSet { checkNotNull(it.value1()) }
    }

    companion object {
        /** The nil person: the key of the tenant's whole digest of a day. */
        val COMPLETE: UUID = UUID(0, 0)

        /** The key of the day's first run, which runs the safety net. */
        val STARTED: UUID = UUID(0, 1)
    }
}

/** What one run of the daily task did. */
data class DailyRun(
    /** The digest emails sent. */
    val digests: Int = 0,
    /** Whether the digest of the day is done (no one left over the cap). */
    val complete: Boolean = false,
    val safety: SafetyRun = SafetyRun(),
    /** The owners asked for an update before a committee's papers (part (b), on the day's first run). */
    val updatesAsked: Int = 0,
) {
    companion object {
        val NOTHING = DailyRun()
    }
}

/**
 * The `actions-daily` task of a tenant (design E07 §9), from 09:00 in the tenant's zone on its working days, run hourly
 * until the day's digest is done; as the system, in one tenant transaction:
 * - (a) **the digest:** for each person with action notices since their last digest (the in-app items of the kinds whose
 *   emails wait, [DIGEST_KINDS]; at most [LOOKBACK] back), one `ACTIONS_DIGEST` email with their references by kind, and
 *   no more that day (`actions_digest`). Notices written after 09:00 wait for the next working day's. At most
 *   [PEOPLE_MAX] people a run; the rest go on the next hourly run. Escalations, new actions and send-backs are emailed at
 *   once by their own kinds, not here.
 * - (b) **the update requests** ([ActionUpdateRequests.daily]), on the run that starts the day: the holders of open
 *   actions with no update since the previous meeting, from the committee type's `updateRequestWorkingDays` before the
 *   next meeting's agenda deadline, once per meeting.
 * - (c) **the safety net** ([ActionSafetyNet]), on the run that starts the day.
 */
@Service
class ActionsDaily(
    private val digests: ActionDigestStore,
    private val log: NotificationLog,
    private val recipients: RecipientLookup,
    private val notifications: Notifications,
    private val registry: TenantRegistry,
    private val tenancy: TenancyProperties,
    private val settings: TenantSettings,
    private val org: OrgDirectory,
    private val workingDays: WorkingDays,
    private val safetyNet: ActionSafetyNet,
    private val updateRequests: ActionUpdateRequests,
    private val audit: AuditLog,
    private val clock: Clock,
) {
    /** False only while a test runs the task itself (test contexts share the scheduler table). */
    @Volatile var jobs: Boolean = true

    private val texts = DigestTexts()

    /** One run at [now]: nothing before 09:00, on a day off, or once the day's digest is done. */
    fun run(now: Instant = clock.instant()): DailyRun {
        val zone = org.zone()
        val local = now.atZone(zone)
        val today = local.toLocalDate()
        val due = local.toLocalTime() >= DIGEST_TIME && workingDays.after(today.minusDays(1), 1) == today
        if (!due || digests.complete(today)) return DailyRun.NOTHING
        // The day's first run (the one that records its start) checks the actions and asks for updates too.
        val first = digests.record(ActionDigestStore.STARTED, today, now)
        val safety = if (first) safetyNet.check() else SafetyRun()
        val asked = if (first) updateRequests.daily(now) else 0
        val (sent, complete) = digest(today, now, zone)
        if (complete) digests.record(ActionDigestStore.COMPLETE, today, now)
        if (sent > 0) audit.record("actions.digest.sent", DIGEST, today.toString(), after = mapOf("people" to sent, "complete" to complete))
        return DailyRun(sent, complete, safety, asked)
    }

    /** The digests of [today]: how many were sent, and whether everyone was reached. */
    private fun digest(
        today: LocalDate,
        now: Instant,
        zone: ZoneId,
    ): Pair<Int, Boolean> {
        val cutoff = today.atTime(DIGEST_TIME).atZone(zone).toInstant()
        val floor = cutoff.minus(LOOKBACK)
        val written = log.written(DIGEST_KINDS, floor, cutoff, ITEMS_MAX).groupBy { it.userId }.toSortedMap()
        val had = digests.had(written.keys, today)
        // A digest before the lookback's first day leaves the window at [floor], as no digest does.
        val last = digests.lastDays(written.keys, floor.atZone(zone).toLocalDate(), today)
        // The organisation's name and address, read once for every digest of the run.
        val tenant = registry.current()
        var sent = 0
        var complete = true
        for ((user, notes) in written.filterKeys { it !in had }) {
            if (sent >= PEOPLE_MAX) {
                complete = false
                break
            }
            val since = last[user]?.atTime(DIGEST_TIME)?.atZone(zone)?.toInstant() ?: floor
            val news = notes.filter { !it.createdAt.isBefore(since) }
            if (news.isNotEmpty() && digests.record(user, today, now) && send(user, news, tenant)) sent++
        }
        return sent to complete
    }

    /**
     * One digest to [user] with [news] from [tenant]: their references by kind in their language; false for nobody to send
     * to.
     */
    private fun send(
        user: UUID,
        news: List<SentNotification>,
        tenant: Tenant?,
    ): Boolean {
        val recipient = recipients.recipient(user) ?: return false
        val locale = localeOf(recipient.locale)
        val params =
            mapOf(
                "organisation" to tenant?.name.orEmpty(),
                "lines" to texts.lines(news, locale),
                "link" to (tenant?.let { tenancy.publicUrl(it.slug) }.orEmpty() + MY_ACTIONS),
            )
        notifications.notify(recipient, NotificationKind.ACTIONS_DIGEST, params)
        return true
    }

    /** The recipient's language if the digest has texts for it, else the tenant's default, else English (as the mail's). */
    private fun localeOf(tag: String?): Locale =
        listOfNotNull(tag, settings.get(DEFAULT_LOCALE_SETTING)?.value, "en")
            .map { Locale.forLanguageTag(it) }
            .first { it.language in SUPPORTED }

    companion object {
        /** The digest's time in the tenant's zone (design E07 §1.8). */
        val DIGEST_TIME: LocalTime = LocalTime.of(9, 0)

        /** How far back a person's first digest reads. */
        val LOOKBACK: Duration = Duration.ofDays(14)

        /** At most this many people a run (design E07 §9); the rest go on the next run. */
        const val PEOPLE_MAX = 5000

        /** At most this many notices read a run. */
        const val ITEMS_MAX = 200_000

        const val MY_ACTIONS = "/inbox?tab=actions"
        const val DIGEST = "actions_digest"
        private const val DEFAULT_LOCALE_SETTING = "locale.default"
        private val SUPPORTED = setOf("en", "ar")

        /** The notices whose emails wait for the digest (design E07 §1.8, §2.3). */
        val DIGEST_KINDS: Set<NotificationKind> =
            setOf(
                NotificationKind.ACTION_DUE_SOON,
                NotificationKind.ACTION_OVERDUE,
                NotificationKind.ACTION_TO_VERIFY,
                NotificationKind.ACTION_CLOSED,
                NotificationKind.ACTION_CANCELLED,
                NotificationKind.ACTION_REOPENED,
                NotificationKind.ACTION_DUE_REQUESTED,
                NotificationKind.ACTION_DUE_DECIDED,
                NotificationKind.ACTION_DUE_CHANGED,
                NotificationKind.ACTION_SOURCE_CORRECTED,
                NotificationKind.ACTION_NOT_RUNNING,
            )
    }
}

/**
 * The digest's sentences (`actions/digest*.properties`), in the recipient's language: one line per kind of news with its
 * references ("Overdue: BRC A9/2026, MCC A1/2026"), the most pressing first, at most [SHOWN] references a line.
 */
internal class DigestTexts {
    private val messages =
        ResourceBundleMessageSource().apply {
            setBasename("actions/digest")
            setDefaultEncoding(Charsets.UTF_8.name())
            setFallbackToSystemLocale(false)
        }

    fun lines(
        news: List<SentNotification>,
        locale: Locale,
    ): String =
        SECTIONS
            .mapNotNull { (section, kinds) ->
                val references =
                    news
                        .filter { it.kind in kinds }
                        .mapNotNull {
                            it.params["reference"]?.takeIf(
                                String::isNotBlank,
                            )
                        }.distinct()
                if (references.isEmpty()) return@mapNotNull null
                val shown = references.take(SHOWN).joinToString(", ")
                val more = references.size - SHOWN
                val list = if (more > 0) "$shown " + messages.getMessage("digest.more", arrayOf(more.toString()), locale) else shown
                "  " + messages.getMessage("digest.$section", arrayOf(list), locale)
            }.joinToString("\n")

    private companion object {
        const val SHOWN = 10

        val SECTIONS: List<Pair<String, Set<NotificationKind>>> =
            listOf(
                "OVERDUE" to setOf(NotificationKind.ACTION_OVERDUE),
                "DUE_SOON" to setOf(NotificationKind.ACTION_DUE_SOON),
                "TO_VERIFY" to setOf(NotificationKind.ACTION_TO_VERIFY),
                "DATES" to
                    setOf(NotificationKind.ACTION_DUE_REQUESTED, NotificationKind.ACTION_DUE_DECIDED, NotificationKind.ACTION_DUE_CHANGED),
                "CLOSED" to setOf(NotificationKind.ACTION_CLOSED),
                "CANCELLED" to setOf(NotificationKind.ACTION_CANCELLED),
                "REOPENED" to setOf(NotificationKind.ACTION_REOPENED),
                "OTHER" to setOf(NotificationKind.ACTION_SOURCE_CORRECTED, NotificationKind.ACTION_NOT_RUNNING),
            )
    }
}

/** Task data of one tenant's daily task; [slot] (the hour) makes it once an hour. */
data class ActionsDailyTask(
    override val tenantId: UUID,
    val slot: Long = 0,
) : TenantScopedPayload

/** The daily task's jobs (ADR-0018): an hourly platform task fans out to one `actions-daily` per active tenant. */
@Configuration(proxyBeanMethods = false)
class ActionsDailyJobs {
    @Bean
    fun actionsDailyTask(
        jobs: Jobs,
        daily: ActionsDaily,
        transactions: TenantTransactions,
    ): OneTimeTask<ActionsDailyTask> =
        jobs.tenantTask(DAILY, ActionsDailyTask::class.java) {
            if (daily.jobs) transactions.inTenant(TenantContext.current()) { daily.run() }
        }

    @Bean
    fun actionsDailyTenants(
        jobs: Jobs,
        tenants: ActiveTenants,
        scheduler: ObjectProvider<SchedulerClient>,
        task: ObjectProvider<OneTimeTask<ActionsDailyTask>>,
        clock: Clock,
    ): RecurringTask<Void> =
        jobs.platformTask("$DAILY-tenants", Schedules.fixedDelay(EVERY)) {
            val client = scheduler.getObject()
            val work = task.getObject()
            val slot = clock.instant().epochSecond / EVERY.seconds
            tenants.ids().forEach { tenant ->
                client.scheduleIfNotExists(work.schedulableInstance("$tenant:$slot", ActionsDailyTask(tenant.value, slot)))
            }
        }

    private companion object {
        const val DAILY = "actions-daily"
        val EVERY: Duration = Duration.ofHours(1)
    }
}
