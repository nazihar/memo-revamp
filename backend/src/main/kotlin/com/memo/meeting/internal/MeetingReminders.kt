package com.memo.meeting.internal

import com.memo.audit.AuditLog
import com.memo.common.tenant.TenantDsl
import com.memo.forum.ForumDirectory
import com.memo.forum.ForumPolicies
import com.memo.forum.ForumRef
import com.memo.forum.InvitationTitle
import com.memo.forum.MeetingPolicy
import com.memo.jooq.tables.references.MEETING_REMINDER
import com.memo.meeting.internal.invite.InvitableAudience
import com.memo.meeting.internal.invite.InvitationStore
import com.memo.meeting.internal.invite.InviteeState
import com.memo.meeting.internal.invite.InviteeStore
import com.memo.meeting.internal.quorum.QuorumForecast
import com.memo.meeting.internal.rsvp.RsvpAnswer
import com.memo.meeting.internal.rsvp.RsvpRules
import com.memo.meeting.internal.rsvp.RsvpStore
import com.memo.notification.NotificationKind
import com.memo.notification.Notifications
import com.memo.notification.RecipientLookup
import com.memo.tenancy.TenancyProperties
import com.memo.tenancy.TenantRegistry
import com.memo.tenancy.TenantSettings
import com.memo.workflow.WorkingDays
import org.springframework.context.support.ResourceBundleMessageSource
import org.springframework.stereotype.Component
import org.springframework.stereotype.Repository
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.Locale
import java.util.UUID

/** The sweep's reminders (design E05 §9; `meeting_reminder`): each is marked once per person, kind and key. */
enum class ReminderKind { REPLY, MEETING, QUORUM_AT_RISK }

/** Marks the sweep's reminders sent (design E05 §9), inside a tenant transaction; written before the reminder is sent. */
@Repository
class MeetingReminderStore(
    private val tenantDsl: TenantDsl,
) {
    /** Marks [userId]'s [kind] reminder of [meetingId] for [key] sent at [at]; false when it already was. */
    fun mark(
        meetingId: UUID,
        userId: UUID,
        kind: ReminderKind,
        key: String,
        at: Instant,
    ): Boolean =
        tenantDsl
            .current()
            .insertInto(MEETING_REMINDER)
            .set(MEETING_REMINDER.TENANT_ID, tenantDsl.tenant().value)
            .set(MEETING_REMINDER.MEETING_ID, meetingId)
            .set(MEETING_REMINDER.USER_ID, userId)
            .set(MEETING_REMINDER.KIND, kind.name)
            .set(MEETING_REMINDER.KEY, key)
            .set(MEETING_REMINDER.SENT_AT, MeetingStore.utc(at))
            .onConflictDoNothing()
            .execute() == 1

    /** Who was sent [meetingId]'s [kind] reminder for [key]. */
    fun marked(
        meetingId: UUID,
        kind: ReminderKind,
        key: String,
    ): Set<UUID> =
        tenantDsl
            .current()
            .select(MEETING_REMINDER.USER_ID)
            .from(MEETING_REMINDER)
            .where(MEETING_REMINDER.MEETING_ID.eq(meetingId), MEETING_REMINDER.KIND.eq(kind.name), MEETING_REMINDER.KEY.eq(key))
            .fetchSet { checkNotNull(it.value1()) }
}

/**
 * The language a meeting email is written in for a recipient: theirs if memo has texts for it, else the tenant's default,
 * else English (as the mail module chooses).
 */
@Component
class RecipientLocales(
    private val settings: TenantSettings,
) {
    fun of(tag: String?): Locale =
        listOfNotNull(tag, settings.get(DEFAULT_LOCALE_SETTING)?.value, "en")
            .map { Locale.forLanguageTag(it) }
            .first { it.language in SUPPORTED }

    private companion object {
        const val DEFAULT_LOCALE_SETTING = "locale.default"
        val SUPPORTED = setOf("en", "ar")
    }
}

/** The sentences the meeting reminder adds (`meeting/reminder*.properties`), in the recipient's language. */
internal class ReminderTexts {
    private val messages =
        ResourceBundleMessageSource().apply {
            setBasename("meeting/reminder")
            setDefaultEncoding(Charsets.UTF_8.name())
            setFallbackToSystemLocale(false)
            setAlwaysUseMessageFormat(false)
        }

    /** " " and the sentence: the email's body puts it after the date. */
    fun line(
        key: String,
        locale: Locale,
    ): String = " " + messages.getMessage("reminder.$key", null, locale)
}

/** What a scheduled meeting's reminders need, read once per meeting and sweep. */
private data class ReminderScene(
    val meeting: MeetingRow,
    val forum: ForumRef,
    val policy: MeetingPolicy,
    /** People who hold the meeting's invitation now and may be invited (ADR-0063), with when they were first sent it. */
    val holders: Map<UUID, Instant>,
    /** Those without an answer for the meeting's current times (none, or for the old time). */
    val unanswered: Set<UUID>,
    /** Those whose answer for the current times is apologies. */
    val apologies: Set<UUID>,
) {
    /** How the emails to the audience name the committee: as its invitations do (design E05 §1.8). */
    val committee: String get() = if (policy.invitationTitle == InvitationTitle.NEUTRAL) policy.neutralTitle else forum.name
}

/**
 * The sweep's reminders before a scheduled meeting (design E05 §9 (e)–(g)), from 09:00 in the meeting's zone, each marked in
 * `meeting_reminder` before it is sent, so it goes once:
 * - (f) **the meeting reminder** (`MEETING_REMINDER`, the type's `meetingReminder`) on the working day before, until the
 *   meeting starts, to the people who hold its invitation and did not send their apologies, once per person and meeting
 *   date. It carries "you have not answered" for those without an answer for the current times, and E04's declarations
 *   line for those with items to review who were not reminded of the latest revision: their E04 reminder is marked with it,
 *   so the declarations-due reminder (d), which the sweep runs after this one, does not send them a second email; and the
 *   reply reminder of the same day is marked with it too.
 * - (e) **the reply reminder** (`MEETING_REPLY_REMINDER`, `replyReminder`, `replyDeadlineWorkingDays` > 0) on the deadline
 *   day only, to those who held the invitation before 09:00 that day and have no answer for the current times, once per
 *   person and deadline day.
 * - (g) **quorum at risk** (`MEETING_QUORUM_AT_RISK`) from 17:00 on the deadline day (the working day before the meeting
 *   without a deadline) until the start: when the forecast from the replies ([QuorumForecast]) is not quorate for the
 *   meeting or for an item, to the secretaries in office and the chair (the vice-chair when there is none), once per
 *   meeting and invitation sequence (a change of time, place or link asks again).
 * Never an item, a paper or a reason in any of them. Each sent batch is audited with the meeting.
 */
@Component
class MeetingReminders(
    private val sweepStore: MeetingSweepStore,
    private val store: MeetingReminderStore,
    private val directory: ForumDirectory,
    private val policies: ForumPolicies,
    private val invitable: InvitableAudience,
    private val invitees: InviteeStore,
    private val invitations: InvitationStore,
    private val rsvps: RsvpStore,
    private val forecasts: QuorumForecast,
    private val declarations: DeclarationAccess,
    private val reviews: DeclarationReviewStates,
    private val declarationReminders: DeclarationReminderStore,
    private val notifier: MeetingNotifier,
    private val recipients: RecipientLookup,
    private val notifications: Notifications,
    private val locales: RecipientLocales,
    private val registry: TenantRegistry,
    private val tenancy: TenancyProperties,
    private val workingDays: WorkingDays,
    private val audit: AuditLog,
) {
    private val texts = ReminderTexts()

    /** The sweep's parts (f), (e) and (g), in that order, at [now]: how many meetings had a reminder sent. */
    fun due(now: Instant): Int =
        sweepStore.upcoming(now, now.plus(HORIZON), MeetingSweep.BATCH).count { meeting ->
            val forum = directory.forum(meeting.forumId) ?: return@count false
            val policy = policies.version(forum.typeId, meeting.typeVersion)?.meetings ?: MeetingPolicy()
            val dayBefore = workingDays.before(meeting.date, 1) ?: return@count false
            val deadline = policy.replyDeadlineWorkingDays.takeIf { it > 0 }?.let { workingDays.before(meeting.date, it) }
            val today = now.atZone(meeting.zone).toLocalDate()
            val scene = lazy { scene(meeting, forum, policy) }
            val reminded = policy.meetingReminder && from(now, meeting, dayBefore, FROM) && meetingReminder(scene.value, deadline, now)
            val asked =
                policy.replyReminder && deadline == today && from(now, meeting, deadline, FROM) && replyReminder(scene.value, deadline, now)
            val warned = from(now, meeting, deadline ?: dayBefore, AT_RISK_FROM) && quorumAtRisk(meeting, forum, now)
            reminded || asked || warned
        }

    private fun scene(
        meeting: MeetingRow,
        forum: ForumRef,
        policy: MeetingPolicy,
    ): ReminderScene {
        val people = invitable.of(meeting).keys
        val holders =
            invitees
                .invitees(meeting.id)
                .filter { it.state == InviteeState.INVITED && it.userId in people }
                .associate { it.userId to it.firstSentAt }
        val current =
            rsvps
                .ofMeeting(meeting.id)
                .filterValues { !RsvpRules.stale(it.forStartsAt, it.forEndsAt, meeting.startsAt, meeting.endsAt) }
        val apologies = current.filterValues { it.answer == RsvpAnswer.APOLOGIES }.keys
        return ReminderScene(meeting, forum, policy, holders, holders.keys - current.keys, apologies)
    }

    private fun meetingReminder(
        scene: ReminderScene,
        deadline: LocalDate?,
        now: Instant,
    ): Boolean {
        val meeting = scene.meeting
        val key = meeting.date.toString()
        val sent =
            (scene.holders.keys - scene.apologies - store.marked(meeting.id, ReminderKind.MEETING, key))
                .sorted()
                .filter { store.mark(meeting.id, it, ReminderKind.MEETING, key, now) }
        if (sent.isEmpty()) return false
        val toReview = declarationsDue(meeting, sent.toSet(), now)
        sent.forEach { user ->
            val unanswered = user in scene.unanswered
            if (unanswered && deadline != null) store.mark(meeting.id, user, ReminderKind.REPLY, deadline.toString(), now)
            send(user, NotificationKind.MEETING_REMINDER, scene, unanswered, toReview[user] ?: 0)
        }
        audit.record(
            "meeting.reminded.meeting",
            MeetingLifecycle.OBJECT_TYPE,
            meeting.id.toString(),
            after =
                mapOf(
                    "date" to key,
                    "count" to sent.size,
                    "unanswered" to sent.count { it in scene.unanswered },
                    "declarations" to toReview.size,
                ),
        )
        return true
    }

    /**
     * Who among [people] has items of [meeting]'s latest revision to review for declarations and was not reminded of it:
     * their E04 reminder is marked now, as it goes with the meeting reminder (design E05 §9 (f)). The items each has.
     */
    private fun declarationsDue(
        meeting: MeetingRow,
        people: Set<UUID>,
        now: Instant,
    ): Map<UUID, Int> {
        val agenda = declarations.agenda(meeting)
        if (agenda.latest == 0) return emptyMap()
        return reviews
            .of(agenda)
            .filter { it.userId in people && it.toReview.isNotEmpty() && (it.reminded ?: 0) < agenda.latest }
            .filter { declarationReminders.remind(meeting.id, it.userId, agenda.latest, now) }
            .associate { it.userId to it.toReview.size }
    }

    private fun replyReminder(
        scene: ReminderScene,
        deadline: LocalDate,
        now: Instant,
    ): Boolean {
        val meeting = scene.meeting
        val opened = deadline.atTime(FROM).atZone(meeting.zone).toInstant()
        val key = deadline.toString()
        val sent =
            (scene.unanswered - store.marked(meeting.id, ReminderKind.REPLY, key))
                .filter { scene.holders.getValue(it).isBefore(opened) }
                .sorted()
                .filter { store.mark(meeting.id, it, ReminderKind.REPLY, key, now) }
        if (sent.isEmpty()) return false
        sent.forEach { send(it, NotificationKind.MEETING_REPLY_REMINDER, scene) }
        audit.record(
            "meeting.reminded.reply",
            MeetingLifecycle.OBJECT_TYPE,
            meeting.id.toString(),
            after = mapOf("deadline" to key, "count" to sent.size),
        )
        return true
    }

    private fun quorumAtRisk(
        meeting: MeetingRow,
        forum: ForumRef,
        now: Instant,
    ): Boolean {
        val sequence = invitations.find(meeting.id)?.sequence ?: 0
        val key = sequence.toString()
        val told = store.marked(meeting.id, ReminderKind.QUORUM_AT_RISK, key)
        val officers = (notifier.secretaries(forum.id) + setOfNotNull(notifier.chairOf(forum.id)) - told).sorted()
        // The forecast only when someone is still to be told.
        val forecast = officers.takeIf { it.isNotEmpty() }?.let { forecasts.of(meeting) }
        val items = forecast?.items?.count { !it.result.quorate } ?: 0
        val atRisk = forecast != null && (!forecast.result.quorate || items > 0)
        val sent = if (atRisk) officers.filter { store.mark(meeting.id, it, ReminderKind.QUORUM_AT_RISK, key, now) } else emptyList()
        if (forecast == null || sent.isEmpty()) return false
        sent.forEach { notifier.person(it, NotificationKind.MEETING_QUORUM_AT_RISK, meeting, forum, emptyMap()) }
        audit.record(
            "meeting.reminded.quorum_at_risk",
            MeetingLifecycle.OBJECT_TYPE,
            meeting.id.toString(),
            after =
                mapOf(
                    "sequence" to sequence,
                    "quorate" to forecast.result.quorate,
                    "present" to forecast.result.present,
                    "needed" to forecast.result.needed,
                    "items" to items,
                    "count" to sent.size,
                ),
        )
        return true
    }

    /** One reminder to one person of the audience, naming the committee as its invitations do. */
    private fun send(
        user: UUID,
        kind: NotificationKind,
        scene: ReminderScene,
        unanswered: Boolean = false,
        toReview: Int = 0,
    ) {
        val recipient = recipients.recipient(user) ?: return
        val tenant = registry.current()
        val link = "/meetings/${scene.meeting.id}"
        val locale = locales.of(recipient.locale)
        val lines =
            if (kind == NotificationKind.MEETING_REMINDER) {
                mapOf(
                    "reply" to (if (unanswered) texts.line("reply", locale) else ""),
                    "declarations" to (if (toReview > 0) texts.line("declarations", locale) else ""),
                    "unanswered" to unanswered.toString(),
                    "toReview" to toReview.toString(),
                )
            } else {
                emptyMap()
            }
        notifications.notify(
            recipient,
            kind,
            lines +
                mapOf(
                    "committee" to scene.committee,
                    "date" to scene.meeting.date.toString(),
                    "organisation" to tenant?.name.orEmpty(),
                    "link" to (tenant?.let { tenancy.publicUrl(it.slug) }.orEmpty() + link),
                ),
            link,
        )
    }

    /** Whether [day] at [time] in [meeting]'s zone has come at [now], and the meeting has not started. */
    private fun from(
        now: Instant,
        meeting: MeetingRow,
        day: LocalDate,
        time: LocalTime,
    ): Boolean = !now.isBefore(day.atTime(time).atZone(meeting.zone).toInstant()) && now.isBefore(meeting.startsAt)

    private companion object {
        /** The reminders go from 09:00 in the meeting's zone (design E05 §9). */
        val FROM: LocalTime = LocalTime.of(9, 0)

        /** Quorum at risk from 17:00 on the deadline day (design E05 §9 (g)). */
        val AT_RISK_FROM: LocalTime = LocalTime.of(17, 0)

        /** The deadline is at most ten working days before the meeting: 30 days cover it with holidays. */
        val HORIZON: Duration = Duration.ofDays(30)
    }
}
