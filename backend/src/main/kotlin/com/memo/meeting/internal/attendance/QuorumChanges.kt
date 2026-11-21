package com.memo.meeting.internal.attendance

import com.memo.audit.AuditLog
import com.memo.common.id.IdGenerator
import com.memo.forum.ForumDirectory
import com.memo.forum.QuorumResult
import com.memo.meeting.QuorumChangeKind
import com.memo.meeting.internal.MeetingLifecycle
import com.memo.meeting.internal.MeetingNotifier
import com.memo.meeting.internal.MeetingRow
import com.memo.meeting.internal.actorOrNull
import com.memo.meeting.internal.quorum.LiveQuorum
import com.memo.notification.NotificationKind
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * The live quorum's change log (ADR-0064, design E05 §1.7), in the change's transaction, while the meeting is in progress:
 *  - **the meeting** after every attendance change: compared with the last meeting-level change logged (not quorate
 *    before any), a flip writes `meeting_quorum_change` (MEETING) with the numbers, the item in progress and the event that
 *    caused it, and is audited: `meeting.quorum.reached` the first time, then `meeting.quorum.lost` and
 *    `meeting.quorum.restored`, the last two told to the chair and the secretaries in the app (`MEETING_QUORUM_LOST`,
 *    `MEETING_QUORUM_RESTORED`; no email, they are in the room);
 *  - **the item in progress** (T5) after every attendance change and when it becomes the item in progress: compared with
 *    the last change logged for that item, a flip is logged (ITEM) and audited the same way with its level; not told (the
 *    secretary and the chair see it on the live view, and E06 refuses a decision without it).
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
    /** Logs a flip of [meeting]'s quorum, and of [item]'s (the item in progress now), caused by [cause]. */
    fun check(
        meeting: MeetingRow,
        cause: AttendanceEventRow,
        item: UUID?,
    ) {
        val at = if (AttendanceRules.timed(cause.kind)) cause.at else cause.recordedAt
        flip(meeting, QuorumLevel.MEETING, live.evaluate(meeting).result, cause.itemId ?: item, cause.id, at)
        if (item != null) flip(meeting, QuorumLevel.ITEM, live.evaluate(meeting, itemId = item).result, item, cause.id, at)
    }

    /** Logs a flip of [progress]'s item's quorum as it becomes the item in progress. */
    fun progressed(
        meeting: MeetingRow,
        progress: ItemProgressRow,
    ) {
        flip(meeting, QuorumLevel.ITEM, live.evaluate(meeting, itemId = progress.itemId).result, progress.itemId, null, progress.startedAt)
    }

    @Suppress("LongParameterList")
    private fun flip(
        meeting: MeetingRow,
        level: QuorumLevel,
        result: QuorumResult,
        itemId: UUID?,
        causeEventId: UUID?,
        at: Instant,
    ) {
        val logged = log.changes(meeting.id, level).filter { level == QuorumLevel.MEETING || it.itemId == itemId }
        val wasQuorate = logged.lastOrNull()?.let { it.kind != QuorumChangeKind.LOST } ?: false
        if (result.quorate == wasQuorate) return
        val kind =
            when {
                !result.quorate -> QuorumChangeKind.LOST
                logged.any { it.kind == QuorumChangeKind.LOST } -> QuorumChangeKind.RESTORED
                else -> QuorumChangeKind.REACHED
            }
        val change =
            QuorumChangeRow(
                id = ids.next(),
                meetingId = meeting.id,
                level = level,
                itemId = itemId,
                kind = kind,
                needed = result.needed,
                present = result.present,
                unmet = result.unmet,
                at = at,
                causeEventId = causeEventId,
                recordedAt = clock.instant(),
            )
        log.insert(change)
        val after =
            mapOf(
                "changeId" to change.id.toString(),
                "level" to level.name,
                "at" to change.at.toString(),
                "itemId" to change.itemId?.toString(),
                "needed" to change.needed,
                "present" to change.present,
                "unmet" to change.unmet.map { it.kind.name },
                "causeEventId" to causeEventId?.toString(),
            )
        val objectId = meeting.id.toString()
        when (kind) {
            QuorumChangeKind.REACHED -> audit.record("meeting.quorum.reached", MeetingLifecycle.OBJECT_TYPE, objectId, after = after)
            QuorumChangeKind.LOST -> audit.record("meeting.quorum.lost", MeetingLifecycle.OBJECT_TYPE, objectId, after = after)
            QuorumChangeKind.RESTORED -> audit.record("meeting.quorum.restored", MeetingLifecycle.OBJECT_TYPE, objectId, after = after)
        }
        if (level == QuorumLevel.MEETING && kind != QuorumChangeKind.REACHED) tell(meeting, kind, change)
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
