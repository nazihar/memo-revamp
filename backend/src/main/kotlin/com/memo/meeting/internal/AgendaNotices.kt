package com.memo.meeting.internal

import com.github.kagkarlsson.scheduler.SchedulerClient
import com.github.kagkarlsson.scheduler.task.helper.OneTimeTask
import com.memo.common.authz.Clearances
import com.memo.common.jobs.Jobs
import com.memo.common.jobs.TenantScopedPayload
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantTransactions
import com.memo.forum.ForumDirectory
import com.memo.forum.ForumRef
import com.memo.meeting.AgendaPublished
import com.memo.meeting.MeetingStatus
import com.memo.meeting.PaperAccess
import com.memo.notification.NotificationKind
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import java.time.Clock
import java.util.UUID

/** Task data of one revision's notifications (ids only, ADR-0018). */
data class AgendaNoticeTask(
    override val tenantId: UUID,
    val meetingId: UUID,
    val revision: Int,
) : TenantScopedPayload

/**
 * Tells the audience of a published revision (design E03 §9), after the commit, once per person and revision (the notice
 * table is the dedupe key, so a retried job tells nobody twice): revision 1 to everyone still in the audience; a later
 * revision only to those who see something it changed, judged by the paper rule ([PaperAccess], ADR-0061). Never the
 * publisher, never someone not cleared for the meeting.
 */
@Service
class AgendaNotices(
    private val meetings: MeetingStore,
    private val revisions: RevisionStore,
    private val audience: AudienceStore,
    private val directory: ForumDirectory,
    private val clearances: Clearances,
    private val notifier: MeetingNotifier,
    private val viewers: PaperViewers,
    private val papers: PaperAccess,
    private val clock: Clock,
) {
    /** Sends the notifications of [meetingId]'s revision [revision]; how many people were told. Inside a tenant transaction. */
    fun send(
        meetingId: UUID,
        revision: Int,
    ): Int {
        val target = target(meetingId, revision) ?: return 0
        val (meeting, head, forum) = target
        val rows = audience.audience(meeting.id).groupBy { it.userId }
        val users = (rows.keys - setOfNotNull(head.publishedBy)).sorted()
        val ranks = clearances.rankOf(users)
        val officers = viewers.officers(forum.id)
        val floor = clearances.level(meeting.classificationId)?.rank ?: Int.MAX_VALUE
        val kind = if (revision == 1) NotificationKind.MEETING_AGENDA_PUBLISHED else NotificationKind.MEETING_AGENDA_CHANGED
        return users.count { user ->
            val viewer = viewers.of(meeting, user, rows.getValue(user), ranks[user] ?: 0, officers)
            val current = viewer.officer || viewer.inAudience
            val tell = (ranks[user] ?: 0) >= floor && current && (revision == 1 || papers.changed(viewer, head.id))
            val claimed = tell && revisions.claim(head.id, user, kind.name, clock.instant())
            if (claimed) notifier.agenda(user, kind, meeting, forum)
            claimed
        }
    }

    /** The meeting, revision and forum to tell about: none when the meeting is no longer on, or the revision was quiet. */
    private fun target(
        meetingId: UUID,
        revision: Int,
    ): Triple<MeetingRow, RevisionHead, ForumRef>? =
        meetings.find(meetingId)?.takeIf { it.status in TOLD }?.let { meeting ->
            val head = revisions.heads(meeting.id).firstOrNull { it.number == revision && it.notified }
            val forum = directory.forum(meeting.forumId)
            if (head != null && forum != null) Triple(meeting, head, forum) else null
        }

    private companion object {
        val TOLD = setOf(MeetingStatus.SCHEDULED, MeetingStatus.IN_PROGRESS)
    }
}

/**
 * Schedules a revision's notifications in the publishing transaction (db-scheduler joins Spring transactions, ADR-0018):
 * a rolled-back publication tells nobody, and the job runs after the commit. One task instance per revision.
 */
@Component
class AgendaNoticeScheduler(
    private val scheduler: ObjectProvider<SchedulerClient>,
    @param:Qualifier("meetingAgendaNoticeTask") private val task: OneTimeTask<AgendaNoticeTask>,
) {
    /** False only while a test runs the notifications itself (test contexts share the scheduler table). */
    @Volatile var jobs: Boolean = true

    @EventListener
    fun onPublished(event: AgendaPublished) {
        if (!jobs) return
        val data = AgendaNoticeTask(event.tenantId, event.meetingId, event.revision)
        scheduler.ifAvailable?.scheduleIfNotExists(task.schedulableInstance("${event.meetingId}:${event.revision}", data))
    }
}

/** The notification job of a published revision (ADR-0018): retried with backoff, tenant bound from its data. */
@Configuration(proxyBeanMethods = false)
class AgendaNoticeJobs {
    @Bean
    fun meetingAgendaNoticeTask(
        jobs: Jobs,
        notices: AgendaNotices,
        transactions: TenantTransactions,
    ): OneTimeTask<AgendaNoticeTask> =
        jobs.tenantTask(TASK, AgendaNoticeTask::class.java) {
            transactions.inTenant(TenantContext.current()) { notices.send(it.meetingId, it.revision) }
        }

    companion object {
        const val TASK = "meeting-agenda-notices"
    }
}
