package com.memo.minutes.internal

import com.github.kagkarlsson.scheduler.SchedulerClient
import com.github.kagkarlsson.scheduler.task.helper.OneTimeTask
import com.memo.audit.AuditLog
import com.memo.common.context.Actor
import com.memo.common.context.ActorContext
import com.memo.common.context.ActorScope
import com.memo.common.jobs.Jobs
import com.memo.common.jobs.TenantScopedPayload
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantTransactions
import com.memo.forum.ForumDirectory
import com.memo.forum.ForumPolicies
import com.memo.forum.MemoResumes
import com.memo.forum.SubmissionPlacements
import com.memo.meeting.MeetingChange
import com.memo.meeting.MeetingChanged
import com.memo.meeting.MeetingItems
import com.memo.meeting.MeetingRef
import com.memo.meeting.MeetingStatus
import com.memo.meeting.Meetings
import com.memo.notification.NotificationKind
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.util.UUID

/** The `minutes-release` job's data (ADR-0018): ids only. */
data class ReleaseTask(
    override val tenantId: UUID,
    val meetingId: UUID,
    val trigger: ReleaseTrigger,
) : TenantScopedPayload

/** What one pass of the job did: the numbers given, the decisions released, and those whose release failed (with why). */
data class ReleasePass(
    val numbered: List<UUID>,
    val released: List<UUID>,
    val failed: Map<UUID, String>,
)

/**
 * The meeting's end and the minutes' approval (ADR-0065, design E06 §1.3, §1.4, §9): resolution numbers given in agenda
 * order and the decisions sent to their memos, one transaction per decision, idempotent (numbered and released decisions
 * are skipped), and what follows a decision recorded or corrected once the meeting is held: its number at once (or its
 * void), its release at once when memos move on at the meeting's end, and the memo's note when a released decision is
 * corrected. When memos move on is the committee type's `minutes.memoResumes`, from the version pinned on the meeting.
 */
@Component
class MinutesRelease(
    private val reads: DecisionReads,
    private val store: DecisionStore,
    private val resolutions: ResolutionStore,
    private val numbers: ResolutionNumbers,
    private val release: DecisionRelease,
    private val items: MeetingItems,
    private val meetings: Meetings,
    private val directory: ForumDirectory,
    private val policies: ForumPolicies,
    private val placements: SubmissionPlacements,
    private val transactions: TenantTransactions,
    private val audit: AuditLog,
    private val minutes: MinutesStore,
    private val notifier: MinutesNotifier,
    private val people: MinutesPeople,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * One pass for [meetingId] (outside a transaction: each decision in its own): first the numbers, in agenda order, until
     * none is left to give (a decision recorded meanwhile is numbered too); then, when the [trigger] releases under the
     * meeting's policy, every decision not yet sent to its memo, as its recorder.
     */
    fun run(
        meetingId: UUID,
        trigger: ReleaseTrigger,
    ): ReleasePass {
        val tenant = TenantContext.current()
        val numbered = mutableListOf<UUID>()
        val failed = linkedMapOf<UUID, String>()
        var rounds = 0
        while (rounds++ < MAX_ROUNDS) {
            val next = transactions.readInTenant(tenant) { toNumber(meetingId) }.filter { it.decisionId !in failed }
            if (next.isEmpty()) break
            next.forEach { candidate ->
                runCatching { transactions.inTenant(tenant) { if (number(candidate.decisionId)) numbered += candidate.decisionId } }
                    .onFailure { failed[candidate.decisionId] = it.javaClass.simpleName }
            }
        }
        val pending =
            transactions.readInTenant(tenant) {
                val meeting = meetings.find(meetingId) ?: return@readInTenant emptyList()
                if (!releases(meeting, trigger)) return@readInTenant emptyList()
                reads.currentOf(meetingId).filter { it.decision.releasedAt == null && it.decision.submissionId != null }
            }
        val released = mutableListOf<UUID>()
        pending.filter { it.decision.id !in failed }.forEach { d ->
            val scope = ActorScope(Actor.User(d.entry.recordedBy), ActorContext.currentOrNull()?.client)
            runCatching { ActorContext.call(scope) { transactions.inTenant(tenant) { release.release(d.decision.id, trigger) } } }
                .onSuccess { if (it == ReleaseResult.RELEASED) released += d.decision.id }
                .onFailure {
                    log.warn("Decision {} of meeting {} not released: {}", d.decision.id, meetingId, it.toString())
                    failed[d.decision.id] = it.javaClass.simpleName
                }
        }
        return ReleasePass(numbered, released, failed)
    }

    /** Numbers [decisionId] (its row locked) if it is still a resolution without one; whether it took a number. */
    private fun number(decisionId: UUID): Boolean {
        val current = store.lockById(decisionId)?.let { reads.currentById(it.id) }?.takeIf { it.entry.resolution }
        return current != null && resolutions.inForce(current.decision.id) == null && numbers.assign(current) != null
    }

    /**
     * After the last retry: each decision still not sent to its memo is audited, for the decision screen and operations, and
     * the committee's secretaries are told in the app (`DECISIONS_RELEASE_FAILED`, with the count; T4's deferred notice).
     */
    fun gaveUp(
        meetingId: UUID,
        pass: ReleasePass,
    ) = transactions.inTenant(TenantContext.current()) {
        pass.failed.forEach { (decisionId, why) ->
            audit.record(
                "minutes.decision.release_failed",
                DecisionEffects.OBJECT_TYPE,
                meetingId.toString(),
                after = mapOf("decisionId" to decisionId.toString(), "error" to why),
            )
        }
        val forumId = meetings.find(meetingId)?.forumId
        if (forumId != null && pass.failed.isNotEmpty()) {
            val count = mapOf("count" to pass.failed.size.toString())
            notifier.notify(people.secretaries(forumId), NotificationKind.DECISIONS_RELEASE_FAILED, meetingId, details = count)
        }
    }

    /**
     * What follows a decision written by [DecisionRecorder] or [ConsentDecisions], in its transaction, the decision's row
     * (and its submission's and the meeting's) locked: a correction to an outcome that is not a resolution voids its number;
     * once the meeting is held ([status]), a resolution gets its number at once and, when memos move on at the meeting's
     * end, the decision is released at once; a released decision corrected tells its memo.
     */
    fun afterChange(
        recorded: CurrentDecision,
        before: EntryRow?,
        status: MeetingStatus,
    ) {
        numbers.voidIfNeeded(recorded)
        if (status != MeetingStatus.HELD) return
        numbers.assign(recorded)
        val meeting = checkNotNull(meetings.find(recorded.decision.meetingId))
        if (recorded.decision.releasedAt == null && resumes(meeting) == MemoResumes.AT_MEETING_END) {
            val row = checkNotNull(store.lockById(recorded.decision.id))
            release.releaseLocked(row, ReleaseTrigger.AT_ONCE)
        }
        if (before != null && recorded.decision.releasedAt != null) release.corrected(recorded, before)
    }

    /**
     * The submissions of [itemIds]' decisions locked before the meeting's row (E03's lock order), so a release at once after
     * the meeting is held takes them in order.
     */
    fun lockSubmissions(submissionIds: Collection<UUID>) {
        if (submissionIds.isNotEmpty()) placements.lock(submissionIds.sorted())
    }

    /**
     * When memos move on for [meeting]: the policy copied onto its minutes when it was held (design E06 §1.8), else (before
     * then) its pinned committee type version's `minutes.memoResumes`.
     */
    fun resumes(meeting: MeetingRef): MemoResumes =
        minutes.find(meeting.id)?.policy?.memoResumes
            ?: directory.forum(meeting.forumId)?.let { policies.version(it.typeId, meeting.typeVersion)?.minutes?.memoResumes }
            ?: MemoResumes.AT_MEETING_END

    /** Whether [trigger] sends [meeting]'s decisions to their memos: HELD under AT_MEETING_END; APPROVED always (catching up). */
    private fun releases(
        meeting: MeetingRef,
        trigger: ReleaseTrigger,
    ): Boolean =
        when (trigger) {
            ReleaseTrigger.HELD -> meeting.status != MeetingStatus.CANCELLED && resumes(meeting) == MemoResumes.AT_MEETING_END
            ReleaseTrigger.APPROVED, ReleaseTrigger.AT_ONCE -> meeting.status != MeetingStatus.CANCELLED
        }

    /** [meetingId]'s resolution decisions without a number, in agenda order. */
    private fun toNumber(meetingId: UUID): List<NumberingCandidate> {
        val agenda = items.agenda(meetingId).items.map { it.id }
        val decisions = reads.currentOf(meetingId)
        val numbered = resolutions.inForce(decisions.map { it.decision.id }).keys
        return ResolutionOrder.toNumber(
            agenda,
            decisions.map {
                NumberingCandidate(it.decision.id, it.decision.itemId, it.entry.decidedAt, it.entry.resolution, it.decision.id in numbered)
            },
        )
    }

    private companion object {
        /** Numbering passes per run: one normally, more only while decisions keep coming in. */
        const val MAX_ROUNDS = 5
    }
}

/**
 * Schedules `minutes-release` (design E06 §9): when a meeting is marked as held, in the ending transaction (db-scheduler
 * joins Spring transactions, ADR-0018: a rolled-back end schedules nothing); and when its minutes are approved (T6 calls
 * [approved]). One task instance per meeting and trigger.
 */
@Component
class MinutesReleaseScheduler(
    private val scheduler: ObjectProvider<SchedulerClient>,
    @param:Qualifier("minutesReleaseTask") private val task: OneTimeTask<ReleaseTask>,
) {
    /** False only while a test runs the job itself (test contexts share the scheduler table). */
    @Volatile var jobs: Boolean = true

    @EventListener
    fun onMeetingChanged(event: MeetingChanged) {
        if (event.change == MeetingChange.ENDED) schedule(event.tenantId, event.meetingId, ReleaseTrigger.HELD)
    }

    /** The meeting's minutes were approved: its decisions go to their memos under ON_MINUTES_APPROVAL. */
    fun approved(meetingId: UUID) = schedule(TenantContext.current().value, meetingId, ReleaseTrigger.APPROVED)

    private fun schedule(
        tenantId: UUID,
        meetingId: UUID,
        trigger: ReleaseTrigger,
    ) {
        if (!jobs) return
        val data = ReleaseTask(tenantId, meetingId, trigger)
        scheduler.ifAvailable?.scheduleIfNotExists(task.schedulableInstance("$meetingId:$trigger", data))
    }
}

/**
 * The release job (ADR-0018): retried with backoff while a decision fails (the others are not held back, and a retry skips
 * what is done); after the last try the failures are audited and the job ends.
 */
@Configuration(proxyBeanMethods = false)
class MinutesReleaseJobs {
    @Bean
    fun minutesReleaseTask(
        jobs: Jobs,
        release: MinutesRelease,
    ): OneTimeTask<ReleaseTask> =
        jobs.tenantTaskCounting(TASK, ReleaseTask::class.java, MAX_RETRIES) { data, failures ->
            val pass = release.run(data.meetingId, data.trigger)
            if (pass.failed.isNotEmpty()) {
                if (failures + 1 >= MAX_RETRIES) release.gaveUp(data.meetingId, pass) else throw ReleaseIncomplete(pass)
            }
        }

    companion object {
        const val TASK = "minutes-release"
        const val MAX_RETRIES = 5
    }
}

/** Some decisions of a meeting were not sent to their memos: the job is retried. */
class ReleaseIncomplete(
    pass: ReleasePass,
) : RuntimeException("decisions not released: ${pass.failed.keys}")
