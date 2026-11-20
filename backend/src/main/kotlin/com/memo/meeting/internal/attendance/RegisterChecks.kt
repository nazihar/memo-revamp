package com.memo.meeting.internal.attendance

import com.memo.common.concurrency.ConcurrentModificationException
import com.memo.common.web.FieldProblem
import com.memo.common.web.PreconditionRequiredException
import com.memo.common.web.refuseIf
import com.memo.forum.ForumDirectory
import com.memo.meeting.AttendanceCapacity
import com.memo.meeting.internal.AudienceSource
import com.memo.meeting.internal.AudienceStore
import com.memo.meeting.internal.ItemStore
import com.memo.meeting.internal.Lateness
import com.memo.meeting.internal.MeetingRow
import com.memo.meeting.internal.RevisionStore
import com.memo.meeting.internal.requireNoProblems
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * What the register's writes check against the database (design E05 §1.6): the item in progress at an instant, whether an
 * item is on the agenda, the person's row (opened on their first event), the row's If-Match, and that an alternate is
 * counted once. Inside the writing transaction.
 */
@Component
class RegisterChecks(
    private val store: AttendanceStore,
    private val log: MeetingProgressStore,
    private val audience: AudienceStore,
    private val items: ItemStore,
    private val revisions: RevisionStore,
    private val directory: ForumDirectory,
    private val clock: Clock,
) {
    /** The item in progress at [at]: the latest started by then. */
    fun itemAt(
        meetingId: UUID,
        at: Instant,
    ): UUID? = log.progress(meetingId).lastOrNull { !it.startedAt.isAfter(at) }?.itemId

    /** [itemId] is on [meeting]'s latest published revision, or tabled at it (422 `ITEM_NOT_ON_AGENDA`); [required]: one is needed. */
    fun itemProblem(
        meeting: MeetingRow,
        itemId: UUID?,
        required: Boolean,
    ): FieldProblem? {
        val missing = required && itemId == null
        val unknown =
            itemId != null &&
                !revisions.inLatest(meeting.id, itemId) &&
                items.find(itemId)?.takeIf { it.meetingId == meeting.id && it.late == Lateness.TABLED } == null
        return FieldProblem("itemId", "ITEM_NOT_ON_AGENDA", "Choose an item of the published agenda.").takeIf { missing || unknown }
    }

    /**
     * The person's register row and whether it existed, opened now when they are in the audience without one (422
     * `NOT_INVITABLE` when they are not).
     */
    fun rowFor(
        meeting: MeetingRow,
        userId: UUID,
    ): Pair<AttendanceRow, Boolean> {
        store.row(meeting.id, userId)?.let { return it to true }
        val rows = audience.rowsOf(meeting.id, userId)
        val capacity = AttendanceRules.capacity(rows)
        requireNoProblems(
            listOfNotNull(
                FieldProblem("userId", "NOT_INVITABLE", "This person is not in the meeting's audience.").takeIf { capacity == null },
            ),
        )
        val stand = rows.firstOrNull { it.source == AudienceSource.ALTERNATE }.takeIf { capacity == AttendanceCapacity.ALTERNATE }
        store.open(
            AttendanceRow(meeting.id, userId, checkNotNull(capacity), stand?.forMemberId, stand?.forUserId, Standing(), clock.instant(), 0),
        )
        return checkNotNull(store.row(meeting.id, userId)) to false
    }

    /** If-Match on the person's row: required once it existed (428), equal to its version (412); absent for a new row. */
    fun precondition(
        row: AttendanceRow,
        existed: Boolean,
        version: Long?,
    ) {
        if (existed && version == null) throw PreconditionRequiredException()
        if (version != null && version != row.version) throw ConcurrentModificationException("attendance ${row.userId}", version)
    }

    /** An alternate who holds a seat on the roster is counted as a member, never for another seat (409 `ALREADY_COUNTED`). */
    fun countedOnce(
        meeting: MeetingRow,
        row: AttendanceRow,
    ) {
        if (row.capacity != AttendanceCapacity.ALTERNATE) return
        val holder = directory.membersOn(meeting.forumId, meeting.date).any { it.userId == row.userId }
        refuseIf(holder, "ALREADY_COUNTED") { "This person holds a seat and is counted as a member, not as an alternate." }
    }

    companion object {
        fun reasonRequired(missing: Boolean): FieldProblem? =
            FieldProblem("reason", "REASON_REQUIRED", "Say why the register is corrected.").takeIf { missing }

        fun modeRequired(missing: Boolean): FieldProblem? =
            FieldProblem("mode", "MODE_REQUIRED", "Say whether they attend in person or online.").takeIf { missing }
    }
}
