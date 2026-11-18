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
import com.memo.forum.ForumRole
import com.memo.meeting.AgendaPublished
import com.memo.meeting.MeetingStatus
import com.memo.notification.NotificationKind
import com.memo.org.OrgDirectory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

/** Task data of one revision's notifications (ids only, ADR-0018). */
data class AgendaNoticeTask(
    override val tenantId: UUID,
    val meetingId: UUID,
    val revision: Int,
) : TenantScopedPayload

/**
 * A person of the audience as a revision's notification judges what they see (design E03 §9): their audience rows, their
 * clearance, and whether they are an officer of the forum. The full rule, with exclusions and every read path, is
 * `PaperAccess` (ADR-0061, T4); this is its notification half.
 */
data class Viewer(
    val userId: UUID,
    val rank: Int,
    val officer: Boolean,
    val rows: List<AudienceRow>,
    val today: LocalDate,
) {
    /** A member in office today, for a members-only section when [membersOnly]. */
    fun member(membersOnly: Boolean): Boolean =
        rows.any {
            val until = it.until
            it.source == AudienceSource.MEMBER && (until == null || !until.isBefore(today)) && (!membersOnly || it.membersOnly)
        }

    fun guest(): Boolean = rows.any { it.source == AudienceSource.GUEST }

    /** Still in the audience: a guest, an item attendee, or a member in office today. */
    fun current(): Boolean = officer || guest() || member(false) || rows.any { it.source == AudienceSource.ATTENDEE }
}

/** Whether a viewer sees an item or paper of a revision, and a change in it (design E03 §1.9, §9). */
object AgendaVisibility {
    /** Item flags judged on the item itself; paper flags are judged on each paper the viewer sees. */
    private val ITEM_CHANGES = ItemFlag.entries.toSet() - setOf(ItemFlag.PAPER_ADDED, ItemFlag.PAPER_REPLACED, ItemFlag.PAPER_REMOVED)

    fun seesItem(
        viewer: Viewer,
        item: RevisionItem,
        rank: (UUID) -> Int,
    ): Boolean {
        val restricted = item.restrictedTo
        val inAudience =
            viewer.member(item.membersOnly) || (viewer.guest() && !item.membersOnly) || viewer.userId in item.attendees
        val allowed = viewer.officer || (inAudience && (restricted == null || viewer.userId in restricted))
        return viewer.rank >= rank(item.classificationId) && allowed
    }

    fun seesPaper(
        viewer: Viewer,
        item: RevisionItem,
        paper: RevisionPaper,
        rank: (UUID) -> Int,
    ): Boolean {
        val restricted = paper.restrictedTo
        return seesItem(viewer, item, rank) && viewer.rank >= rank(paper.classificationId) &&
            (restricted == null || viewer.userId in restricted || viewer.officer)
    }

    /** Whether [revision] changed something [viewer] sees (a removed row judged as it was published before). */
    fun changed(
        viewer: Viewer,
        revision: Snapshot,
        rank: (UUID) -> Int,
    ): Boolean {
        val items = revision.items.associateBy { it.itemId }
        return revision.items.any { item -> item.changes.any { it in ITEM_CHANGES } && seesItem(viewer, item, rank) } ||
            revision.papers.any { paper ->
                val item = items[paper.itemId]
                paper.changes.isNotEmpty() && item != null && seesPaper(viewer, item, paper, rank)
            }
    }
}

/**
 * Tells the audience of a published revision (design E03 §9), after the commit, once per person and revision (the notice
 * table is the dedupe key, so a retried job tells nobody twice): revision 1 to everyone still in the audience; a later
 * revision only to those who see something it changed. Never the publisher, never someone not cleared for the meeting.
 */
@Service
class AgendaNotices(
    private val meetings: MeetingStore,
    private val revisions: RevisionStore,
    private val audience: AudienceStore,
    private val directory: ForumDirectory,
    private val clearances: Clearances,
    private val notifier: MeetingNotifier,
    private val org: OrgDirectory,
    private val clock: Clock,
) {
    /** Sends the notifications of [meetingId]'s revision [revision]; how many people were told. Inside a tenant transaction. */
    fun send(
        meetingId: UUID,
        revision: Int,
    ): Int {
        val target = target(meetingId, revision) ?: return 0
        val (meeting, head, forum) = target
        val snapshot = revisions.snapshot(head.id)
        val rows = audience.audience(meeting.id).groupBy { it.userId }
        val users = (rows.keys - setOfNotNull(head.publishedBy)).sorted()
        val ranks = clearances.rankOf(users)
        val officers = officers(forum.id)
        val rank = { level: UUID -> clearances.level(level)?.rank ?: Int.MAX_VALUE }
        val floor = rank(meeting.classificationId)
        val kind = if (revision == 1) NotificationKind.MEETING_AGENDA_PUBLISHED else NotificationKind.MEETING_AGENDA_CHANGED
        val today = org.today()
        return users.count { user ->
            val viewer = Viewer(user, ranks[user] ?: 0, user in officers, rows.getValue(user), today)
            val tell = viewer.rank >= floor && viewer.current() && (revision == 1 || AgendaVisibility.changed(viewer, snapshot, rank))
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

    /** The forum's secretaries and chair in office (the vice-chair when there is no chair): they see every item. */
    private fun officers(forumId: UUID): Set<UUID> {
        val seats = directory.membersOn(forumId)
        val chair = seats.any { it.role == ForumRole.CHAIR }
        return seats
            .filter {
                it.role == ForumRole.SECRETARY || it.role == ForumRole.CHAIR ||
                    (it.role == ForumRole.VICE_CHAIR && !chair)
            }.map { it.userId }
            .toSet()
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
