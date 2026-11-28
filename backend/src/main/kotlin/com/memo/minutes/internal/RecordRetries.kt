package com.memo.minutes.internal

import com.memo.audit.AuditLog
import com.memo.common.web.refuseIf
import com.memo.notification.NotificationKind
import com.memo.workflow.WorkingDays
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * Tells the committee's secretaries, once per record version, that what the approved minutes' readers read of it could
 * not be made (the record, or a reader's approved copy) after its job's last try (`MINUTES_RECORD_FAILED`, in the app):
 * those not recused from any item, who can make it again, else all of them. In the failing transaction; marked in
 * `minutes_reminder` before it is sent, and audited `minutes.reminded.record_failed`.
 */
@Component
class RecordFailures(
    private val people: MinutesPeople,
    private val reminders: MinutesReminderStore,
    private val notifier: MinutesNotifier,
    private val audit: AuditLog,
    private val clock: Clock,
) {
    fun failed(
        meetingId: UUID,
        forumId: UUID,
        recordVersion: Int,
    ) {
        val secretaries = people.secretaries(forumId)
        val free = secretaries - people.recusedFromAny(meetingId)
        val key = "record:$recordVersion"
        val now = clock.instant()
        val due = free.ifEmpty { secretaries }.filter { reminders.mark(meetingId, MinutesReminderKind.RECORD_FAILED, it, key, now) }
        if (due.isEmpty()) return
        notifier.notify(due, NotificationKind.MINUTES_RECORD_FAILED, meetingId, except = null)
        audit.record(
            "minutes.reminded.record_failed",
            MinutesDrafts.OBJECT_TYPE,
            meetingId.toString(),
            after = mapOf("recipients" to due.map { it.toString() }.sorted(), "key" to key),
        )
    }
}

/** Who asked for a failed record again. */
enum class RecordRetryTrigger { SWEEP, SECRETARY }

/**
 * A failed record of approved minutes, or a reader's failed approved copy, asked for again (found by E08 T10: after the
 * job's last try nothing ever asked again, so a longer document server outage left approved minutes without their
 * record). As the memo's final record is tried again by its keeper ([com.memo.memo.internal.rendition.RecordLifecycle]):
 * the newest version FAILED → PENDING (its copies once it is READY), the job scheduled as a new execution with a fresh
 * count of tries, audited `minutes.record.retried`. Two ways in, inside a tenant transaction:
 *  - (m) of the minutes' sweep ([sweep]): once a day at most, in the tenant's zone, with backoff (the first day after the
 *    failure, then 2, 4, 8, 16 and 32 days after each new failure), at most [MAX_SWEEPS] times; only approved minutes,
 *    never one already pending (a guarded update FAILED → PENDING), so a second run in the hour does nothing;
 *  - "Make the record again" ([retry]): a secretary or `forums.manage` not recused from any item, at once, without the
 *    backoff or the cap; 409 `RECORD_NOT_FAILED` when nothing has failed.
 */
@Service
class RecordRetries(
    private val store: MinutesStore,
    private val records: MinutesRecordStore,
    private val copyStore: MinutesCopyStore,
    private val copies: ApprovedCopies,
    private val drafts: MinutesDrafts,
    private val jobs: MinutesJobs,
    private val workingDays: WorkingDays,
    private val audit: AuditLog,
) {
    /** (m): what is due today, at most [BATCH] of each; how many minutes had something asked for again. */
    fun sweep(now: Instant): Int {
        val today = workingDays.dayOf(now)
        val due = { failedAt: Instant, retries: Int ->
            retries < MAX_SWEEPS && !today.isBefore(workingDays.dayOf(failedAt).plusDays(backoffDays(retries)))
        }
        val meetings =
            records.failed(MAX_SWEEPS, BATCH).filter { due(it.failedAt ?: it.createdAt, it.retries) }.map { it.meetingId } +
                copyStore.failedApproved(MAX_SWEEPS, BATCH).filter { due(it.failedAt ?: it.createdAt, it.retries) }.map { it.meetingId }
        return meetings.distinct().count { reopen(it, RecordRetryTrigger.SWEEP, due) }
    }

    /**
     * "Make the record again" on [meetingId]'s approved minutes, as the actor: 404 for a meeting they do not see, 403 for
     * anyone but a secretary or `forums.manage`, 403 `RECUSED` for one recused from any item (the record holds every
     * item), then 409 `RECORD_NOT_FAILED` when neither the record nor a reader's copy of it has failed.
     */
    fun retry(meetingId: UUID) {
        drafts.keeper(meetingId)
        val reopened = reopen(meetingId, RecordRetryTrigger.SECRETARY) { _, _ -> true }
        refuseIf(!reopened, "RECORD_NOT_FAILED") { "Nothing of the record has failed: there is nothing to make again." }
    }

    /** Whether [meetingId]'s approved minutes have a failed newest record version, or a failed copy of it. */
    fun retryable(meetingId: UUID): Boolean {
        val newest = records.of(meetingId).firstOrNull() ?: return false
        return when (newest.state) {
            RecordState.FAILED -> true
            RecordState.READY -> copyStore.ofRecord(meetingId, newest.recordVersion).any { it.state == CopyState.FAILED }
            RecordState.PENDING -> false
        }
    }

    /**
     * The newest version of [meetingId]'s record FAILED → PENDING, or (once it is READY) its failed approved copies, those
     * [eligible] (by when they last failed and how many times they were asked for again); false when nothing was.
     */
    private fun reopen(
        meetingId: UUID,
        trigger: RecordRetryTrigger,
        eligible: (Instant, Int) -> Boolean,
    ): Boolean {
        val row = store.find(meetingId)?.takeIf { it.state == MinutesStage.APPROVED }
        val newest = row?.let { records.of(meetingId).firstOrNull() }
        if (row == null || newest == null) return false
        val failed = newest.state == RecordState.FAILED && eligible(newest.failedAt ?: newest.createdAt, newest.retries)
        val record = if (failed) reopenRecord(newest) else null
        val reopened =
            if (newest.state != RecordState.READY) {
                emptyList()
            } else {
                copyStore
                    .ofRecord(meetingId, newest.recordVersion)
                    .filter { it.state == CopyState.FAILED && eligible(it.failedAt ?: it.createdAt, it.retries) }
                    .mapNotNull(::reopenCopy)
            }
        val any = record != null || reopened.isNotEmpty()
        if (any) {
            if (record != null) jobs.record(meetingId, newest.recordVersion)
            if (reopened.isNotEmpty()) copies.request(row)
            retried(meetingId, newest, trigger, record, reopened)
        }
        return any
    }

    private fun retried(
        meetingId: UUID,
        newest: RecordRow,
        trigger: RecordRetryTrigger,
        record: Pair<UUID, String?>?,
        reopened: List<Pair<UUID, String?>>,
    ) = audit.record(
        "minutes.record.retried",
        MinutesDrafts.OBJECT_TYPE,
        meetingId.toString(),
        before = mapOf("error" to (record ?: reopened.first()).second),
        after =
            mapOf(
                "recordVersion" to newest.recordVersion,
                "record" to (record != null),
                "copies" to reopened.map { it.first.toString() },
                "retries" to (record?.let { newest.retries + 1 }),
                "trigger" to trigger.name,
            ),
    )

    /** [failed] FAILED → PENDING with one retry more; its id and the error it had, or null when it moved on. */
    private fun reopenRecord(failed: RecordRow): Pair<UUID, String?>? {
        val locked = records.lock(failed.id)?.takeIf { it.state == RecordState.FAILED } ?: return null
        val again = locked.copy(state = RecordState.PENDING, error = null, retries = locked.retries + 1)
        return locked.takeIf { records.update(it, again) }?.let { it.id to it.error }
    }

    private fun reopenCopy(failed: CopyRow): Pair<UUID, String?>? {
        val locked = copyStore.lock(failed.id)?.takeIf { it.state == CopyState.FAILED } ?: return null
        val again = locked.copy(state = CopyState.PENDING, error = null, retries = locked.retries + 1)
        return locked.takeIf { copyStore.update(it, again) }?.let { it.id to it.error }
    }

    companion object {
        /** How many times the sweep asks for one failed record or copy again (about two months); a secretary can always. */
        const val MAX_SWEEPS = 6
        private const val BATCH = 500

        /** Days after the failure before the sweep asks again: 1, 2, 4, 8, 16, 32. */
        fun backoffDays(retries: Int): Long = 1L shl retries.coerceIn(0, MAX_SWEEPS)
    }
}
