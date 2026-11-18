package com.memo.meeting.internal

import com.memo.audit.AuditLog
import com.memo.common.authz.Clearances
import com.memo.common.id.IdGenerator
import com.memo.common.web.FieldProblem
import com.memo.common.web.refuseIf
import com.memo.identity.UserStatus
import com.memo.identity.UserSummaries
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Guests (design E03 §1.1): people in attendance for the whole meeting who are not on the roster, for example the CFO at
 * ALCO. Added and removed by the meeting's secretaries until it is held; a removal records its time, never a deletion.
 * Each change recomputes the audience and is audited on the meeting.
 */
@Service
class MeetingGuests(
    private val store: AudienceStore,
    private val guard: MeetingGuard,
    private val audience: MeetingAudience,
    private val users: UserSummaries,
    private val clearances: Clearances,
    private val ids: IdGenerator,
    private val audit: AuditLog,
) {
    fun add(
        meetingId: UUID,
        userId: UUID,
        note: String?,
    ): MeetingRow {
        val meeting = guard.requireManaged(meetingId)
        MeetingAction.GUESTS.next(meeting.status)
        val text = note?.trim()?.ifEmpty { null }
        val user = users.of(userId)?.takeIf { it.status == UserStatus.ACTIVE }
        val rank = clearances.rankOf(listOf(userId))[userId] ?: 0
        val level = clearances.level(meeting.classificationId)?.rank ?: 0
        requireNoProblems(
            listOfNotNull(
                FieldProblem("userId", "unknown", "No such active user.").takeIf { user == null },
                FieldProblem("userId", "CLASSIFICATION", "Not cleared for this meeting's classification.")
                    .takeIf { user != null && rank < level },
                MeetingFields.tooLong("note", text, MeetingFields.MAX_NOTE),
            ),
        )
        val added = store.addGuest(ids.next(), meeting.id, userId, text, actorOrNull())
        refuseIf(!added, "ALREADY_GUEST") { "This person is already a guest of the meeting." }
        audit.record(
            "meeting.guest_added",
            MeetingLifecycle.OBJECT_TYPE,
            meeting.id.toString(),
            after = mapOf("userId" to userId.toString(), "note" to text),
        )
        audience.recompute(meeting)
        return meeting
    }

    fun remove(
        meetingId: UUID,
        userId: UUID,
    ): MeetingRow {
        val meeting = guard.requireManaged(meetingId)
        MeetingAction.GUESTS.next(meeting.status)
        if (!store.removeGuest(meeting.id, userId, actorOrNull())) throw MeetingNotFoundException("guest")
        audit.record(
            "meeting.guest_removed",
            MeetingLifecycle.OBJECT_TYPE,
            meeting.id.toString(),
            before =
                mapOf("userId" to userId.toString()),
        )
        audience.recompute(meeting)
        return meeting
    }
}
