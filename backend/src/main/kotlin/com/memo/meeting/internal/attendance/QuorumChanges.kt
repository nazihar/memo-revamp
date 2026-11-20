package com.memo.meeting.internal.attendance

import com.memo.audit.AuditLog
import com.memo.common.id.IdGenerator
import com.memo.forum.ForumDirectory
import com.memo.meeting.QuorumChangeKind
import com.memo.meeting.internal.MeetingLifecycle
import com.memo.meeting.internal.MeetingNotifier
import com.memo.meeting.internal.MeetingRow
import com.memo.meeting.internal.actorOrNull
import com.memo.meeting.internal.quorum.LiveQuorum
import com.memo.notification.NotificationKind
import org.springframework.stereotype.Component
import java.time.Clock
import java.util.UUID

/**
 * The live meeting-level quorum's change log (ADR-0064, design E05 §1.7): after an attendance change of a meeting in
 * progress, the quorum now is compared with the last one logged (not quorate before any). A flip writes
 * `meeting_quorum_change` with the numbers, the item in progress and the event that caused it, and is audited:
 * `meeting.quorum.reached` the first time, then `meeting.quorum.lost` and `meeting.quorum.restored`, the last two told to
 * the chair and the secretaries in the app (`MEETING_QUORUM_LOST`, `MEETING_QUORUM_RESTORED`; no email, they are in the
 * room). In the change's transaction.
 */
@Component
class QuorumChanges(
    private val log: MeetingProgressStore,
    private val live: LiveQuorum,
    private val notifier: MeetingNotifier,
    private val directory: ForumDirectory,
    private val ids: IdGenerator,
    private val audit: AuditLog,
    private val clock: Clock,
) {
    /** Logs a flip of [meeting]'s quorum caused by [cause]; [item] is the item in progress now. */
    fun check(
        meeting: MeetingRow,
        cause: AttendanceEventRow,
        item: UUID?,
    ) {
        val result = live.evaluate(meeting).result
        val logged = log.changes(meeting.id)
        val wasQuorate = logged.lastOrNull()?.let { it.kind != QuorumChangeKind.LOST } ?: false
        if (result.quorate == wasQuorate) return
        val kind =
            when {
                !result.quorate -> QuorumChangeKind.LOST
                logged.any { it.kind == QuorumChangeKind.LOST } -> QuorumChangeKind.RESTORED
                else -> QuorumChangeKind.REACHED
            }
        val at = if (AttendanceRules.timed(cause.kind)) cause.at else cause.recordedAt
        val change =
            QuorumChangeRow(
                id = ids.next(),
                meetingId = meeting.id,
                level = QuorumLevel.MEETING,
                itemId = cause.itemId ?: item,
                kind = kind,
                needed = result.needed,
                present = result.present,
                unmet = result.unmet,
                at = at,
                causeEventId = cause.id,
                recordedAt = clock.instant(),
            )
        log.insert(change)
        val after =
            mapOf(
                "changeId" to change.id.toString(),
                "at" to change.at.toString(),
                "itemId" to change.itemId?.toString(),
                "needed" to change.needed,
                "present" to change.present,
                "unmet" to change.unmet.map { it.kind.name },
                "causeEventId" to cause.id.toString(),
            )
        val objectId = meeting.id.toString()
        when (kind) {
            QuorumChangeKind.REACHED -> audit.record("meeting.quorum.reached", MeetingLifecycle.OBJECT_TYPE, objectId, after = after)
            QuorumChangeKind.LOST -> audit.record("meeting.quorum.lost", MeetingLifecycle.OBJECT_TYPE, objectId, after = after)
            QuorumChangeKind.RESTORED -> audit.record("meeting.quorum.restored", MeetingLifecycle.OBJECT_TYPE, objectId, after = after)
        }
        if (kind != QuorumChangeKind.REACHED) tell(meeting, kind, change)
    }

    /** The chair (the vice-chair when there is none) and the secretaries, but the actor, in the app. */
    private fun tell(
        meeting: MeetingRow,
        kind: QuorumChangeKind,
        change: QuorumChangeRow,
    ) {
        val forum = directory.forum(meeting.forumId) ?: return
        val notice = if (kind == QuorumChangeKind.LOST) NotificationKind.MEETING_QUORUM_LOST else NotificationKind.MEETING_QUORUM_RESTORED
        val details = mapOf("present" to change.present.toString(), "needed" to change.needed.toString())
        val told = notifier.secretaries(forum.id) + setOfNotNull(notifier.chairOf(forum.id))
        (told - setOfNotNull(actorOrNull())).forEach {
            notifier.person(it, notice, meeting, forum, details)
        }
    }
}
