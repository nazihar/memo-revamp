package com.memo.meeting.internal.quorum

import com.memo.forum.ForumDirectory
import com.memo.forum.ForumPolicies
import com.memo.forum.ForumSeat
import com.memo.forum.Quorum
import com.memo.forum.QuorumResult
import com.memo.forum.QuorumRule
import com.memo.meeting.AttendanceCapacity
import com.memo.meeting.AttendanceMode
import com.memo.meeting.AttendanceState
import com.memo.meeting.Recusals
import com.memo.meeting.internal.MeetingRow
import com.memo.meeting.internal.attendance.AttendanceEventRow
import com.memo.meeting.internal.attendance.AttendanceRow
import com.memo.meeting.internal.attendance.AttendanceRules
import com.memo.meeting.internal.attendance.AttendanceStore
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

/**
 * A meeting's quorum at an instant, for the meeting or one item: the rule and roster it was evaluated with, who filled
 * which seat, the people recused from the item and those out of the room for it, and the result.
 */
data class QuorumAt(
    /** Null: now (every event recorded). */
    val at: Instant?,
    /** Null: the meeting as a whole. */
    val itemId: UUID?,
    val rule: QuorumRule,
    val roster: List<ForumSeat>,
    val seats: List<SeatPresence>,
    /** The people recused from [itemId] (a declarant and the alternate standing in for them); empty for the meeting. */
    val recused: Set<UUID>,
    /** The people present at [at] who stepped out of [itemId]; empty for the meeting. */
    val steppedOut: Set<UUID>,
    val result: QuorumResult,
)

/**
 * The live quorum (ADR-0064, design E05 §1.7): the pinned type version's rule over the roster in office on the meeting's
 * date and the register's presence at an instant, through [PresenceBuilder] and [Quorum.evaluate]; for one item, without
 * the people stepped out of it and with E04's recusals (and the alternates of recused members, recused with them), on
 * which the rule's `recusedCount` decides. Inside a tenant transaction; the caller decides access.
 */
@Component
class LiveQuorum(
    private val directory: ForumDirectory,
    private val policies: ForumPolicies,
    private val store: AttendanceStore,
    private val recusals: Recusals,
) {
    /**
     * [meeting]'s quorum at [at] (null: now, from every event), for [itemId] (null: the meeting), from [events] and [rows]
     * when the caller already holds them (the event's transaction evaluates with the new event before reading it back).
     */
    fun evaluate(
        meeting: MeetingRow,
        at: Instant? = null,
        itemId: UUID? = null,
        events: List<AttendanceEventRow> = store.events(meeting.id),
        rows: List<AttendanceRow> = store.rows(meeting.id),
    ): QuorumAt {
        val rule = rule(meeting)
        val roster = directory.membersOn(meeting.forumId, meeting.date)
        val standIns =
            rows
                .filter { it.capacity == AttendanceCapacity.ALTERNATE }
                .mapNotNull { row -> row.forMemberId?.let { it to row.userId } }
                .toMap()
        val present = presentAt(events, at)
        val recused = itemId?.let { recusals.recusedFrom(it) }.orEmpty()
        val steppedOut = if (itemId == null) emptySet() else present.filterValues { it.steppedOutOf == itemId }.keys
        val seats = PresenceBuilder.build(roster, standIns, present, itemId, recused)
        val result = Quorum.evaluate(rule, PresenceBuilder.seats(roster), PresenceBuilder.attendance(seats))
        return QuorumAt(at, itemId, rule, roster, seats, recused, steppedOut, result)
    }

    /** The pinned type version's quorum rule (ADR-0060); the defaults when the version cannot be read. */
    fun rule(meeting: MeetingRow): QuorumRule =
        directory
            .forum(meeting.forumId)
            ?.let { policies.version(it.typeId, meeting.typeVersion) }
            ?.quorum ?: QuorumRule()

    companion object {
        /** The people present at [at] (all events when null) from a meeting's [events]: in the room or online. */
        fun presentAt(
            events: Collection<AttendanceEventRow>,
            at: Instant?,
        ): Map<UUID, PersonPresence> =
            events
                .groupBy { it.userId }
                .mapValues { (_, own) -> AttendanceRules.replay(own, at) }
                .filterValues { it.state == AttendanceState.PRESENT }
                .mapValues { (_, standing) -> PersonPresence(standing.mode == AttendanceMode.REMOTE, standing.steppedOutOf) }
    }
}
