package com.memo.meeting.internal.invite

import com.github.kagkarlsson.scheduler.SchedulerClient
import com.github.kagkarlsson.scheduler.task.helper.CustomTask
import com.memo.common.jobs.Jobs
import com.memo.common.jobs.TenantScopedPayload
import com.memo.common.tenant.TenantContext
import com.memo.meeting.MeetingStatus
import com.memo.meeting.internal.MeetingRow
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * What a meeting's changes mean for its invitation (ADR-0063, design E05 §1.1), in the transaction of the change, after
 * the meeting's row was written (so its lock is held): scheduling creates the invitation; a new time, place, link or
 * title raises the sequence and names the change; an audience change only makes a send due; a cancellation sends at
 * once. Each schedules the `meeting-invitations` reconciler, which sends the difference about two minutes after the last
 * change ([DEBOUNCE]). Changes to the kind or classification send nothing; nothing is sent once the meeting has started.
 */
@Service
class MeetingInvitations(
    private val store: InvitationStore,
    private val invitees: InviteeStore,
    private val scheduler: InvitationScheduler,
    private val clock: Clock,
) {
    /** DRAFT to SCHEDULED: the invitation at sequence 0, sent to everyone invitable after the debounce. */
    fun scheduled(meeting: MeetingRow) {
        val now = clock.instant()
        store.create(meeting.id, UUID.randomUUID(), now, now.plus(DEBOUNCE))
        scheduler.at(meeting.id, now.plus(DEBOUNCE))
    }

    /** A scheduled meeting's times, place, link or title changed between [before] and [after]. */
    fun changed(
        before: MeetingRow,
        after: MeetingRow,
    ) {
        if (after.status != MeetingStatus.SCHEDULED) return
        val changes = changes(before, after)
        if (changes.isEmpty()) {
            // A new classification can make someone no longer cleared: the reconciler uninvites them, at the same sequence.
            if (before.classificationId != after.classificationId) audienceChanged(after)
            return
        }
        val invitation = ensure(after)
        val due = clock.instant().plus(DEBOUNCE)
        store.changed(after.id, invitation.pendingChanges + changes, due)
        scheduler.at(after.id, due)
    }

    /** The audience of a scheduled meeting changed (roster, guests, its date): newcomers are invited, leavers uninvited. */
    fun audienceChanged(meeting: MeetingRow) {
        if (meeting.status != MeetingStatus.SCHEDULED) return
        ensure(meeting)
        val due = clock.instant().plus(DEBOUNCE)
        store.touched(meeting.id, due)
        scheduler.at(meeting.id, due)
    }

    /**
     * A scheduled meeting was called off: the CANCEL goes at once, and overtakes any update still waiting. Answers the
     * people who hold the event: the reconciler tells them, with the file; the others are told without one.
     */
    fun cancelled(meeting: MeetingRow): Set<UUID> {
        val now = clock.instant()
        if (!store.cancelled(meeting.id, now)) return emptySet()
        scheduler.now(meeting.id, now)
        return invitees.invited(meeting.id)
    }

    /** Meetings scheduled before invitations existed get theirs at their first change (design E05 §7). */
    private fun ensure(meeting: MeetingRow): InvitationRow =
        store.find(meeting.id) ?: run {
            val now = clock.instant()
            store.create(meeting.id, UUID.randomUUID(), now, now.plus(DEBOUNCE))
            checkNotNull(store.find(meeting.id))
        }

    companion object {
        /** Sent about two minutes after the last change (design E05 §1.3): three quick edits make one update. */
        val DEBOUNCE: Duration = Duration.ofMinutes(2)

        /** What an update tells (design E05 §1.1): the kind and the classification are not in the invitation. */
        fun changes(
            before: MeetingRow,
            after: MeetingRow,
        ): Set<MeetingChangeKind> =
            listOfNotNull(
                MeetingChangeKind.TIME.takeIf {
                    (before.startsAt to before.endsAt) != (after.startsAt to after.endsAt) ||
                        before.zone != after.zone
                },
                MeetingChangeKind.PLACE.takeIf { before.mode != after.mode || before.location != after.location },
                MeetingChangeKind.LINK.takeIf { before.joinUrl != after.joinUrl || before.joinDetails != after.joinDetails },
                MeetingChangeKind.TITLE.takeIf { before.title != after.title },
            ).toSortedSet()
    }
}

/** Task data of one meeting's invitations (ids only, ADR-0018). */
data class InvitationTask(
    override val tenantId: UUID,
    val meetingId: UUID,
) : TenantScopedPayload

/**
 * Schedules the reconciler in the changing transaction (db-scheduler joins Spring transactions, ADR-0018): a rolled-back
 * change schedules nothing. One instance per meeting coalesces the changes ([at]: if one is already waiting, it runs at
 * its time and waits again for the latest due time); a cancellation has its own instance that runs at once ([now]).
 */
@Component
class InvitationScheduler(
    private val scheduler: ObjectProvider<SchedulerClient>,
    @param:Qualifier("meetingInvitationTask") private val task: CustomTask<InvitationTask>,
) {
    /** False only while a test runs the reconciler itself (test contexts share the scheduler table). */
    @Volatile var jobs: Boolean = true

    fun at(
        meetingId: UUID,
        at: Instant,
    ) = schedule(meetingId.toString(), meetingId, at)

    fun now(
        meetingId: UUID,
        at: Instant,
    ) = schedule("$meetingId:now", meetingId, at)

    private fun schedule(
        instance: String,
        meetingId: UUID,
        at: Instant,
    ) {
        if (!jobs) return
        val data = InvitationTask(TenantContext.current().value, meetingId)
        scheduler.ifAvailable?.scheduleIfNotExists(task.instance(instance, data), at)
    }
}

/**
 * The `meeting-invitations` job (design E05 §9): one step per execution; while the send is not due yet it waits again
 * until it is (the debounce), then reconciles once. Retried with backoff (ADR-0018); the tenant is bound from its data.
 */
@Configuration(proxyBeanMethods = false)
class InvitationJobs {
    @Bean
    fun meetingInvitationTask(
        jobs: Jobs,
        reconciler: InvitationReconciler,
    ): CustomTask<InvitationTask> = jobs.tenantStepTask(TASK, InvitationTask::class.java) { data, _ -> reconciler.run(data.meetingId) }

    companion object {
        const val TASK = "meeting-invitations"
    }
}
