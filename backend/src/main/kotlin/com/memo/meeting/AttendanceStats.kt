package com.memo.meeting

import com.memo.common.authz.Clearances
import com.memo.forum.ForumDirectory
import com.memo.forum.ForumRole
import com.memo.meeting.internal.MeetingStore
import com.memo.meeting.internal.attendance.AttendanceRules
import com.memo.meeting.internal.attendance.AttendanceStore
import com.memo.meeting.internal.attendance.MeetingProgressStore
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** A held meeting (HELD, MINUTES_APPROVED or CLOSED) as analytics count it (design E08 §2.1). */
data class HeldMeeting(
    val id: UUID,
    val forumId: UUID,
    /** "BRC 3/2026"; null for a meeting never numbered. */
    val label: String?,
    /** The meeting's date in its zone: the roster and the paper deadline are on this day. */
    val date: LocalDate,
    val startsAt: Instant,
    val zone: ZoneId,
)

/** A seat in office on a held meeting's date (design E08 §2.1 M1): the chair, a vice-chair or a member, not others. */
data class SeatHolder(
    /** The person: the member, or the holder of the position seat on the date. */
    val userId: UUID,
    /** The seat (forum membership). */
    val memberId: UUID,
    val role: ForumRole,
    val startsOn: LocalDate,
    /** The seat's last day; null when open-ended. */
    val until: LocalDate?,
)

/** One person's line on a held meeting's register (design E05 §1.6), as the analytics read it; derived after voids. */
data class AttendanceFact(
    val userId: UUID,
    val capacity: AttendanceCapacity,
    /** For an alternate: the member they stood in for. */
    val forUserId: UUID?,
    /** Where the register left them: PRESENT, LEFT, APOLOGIES, ABSENT or EXPECTED (never marked). */
    val state: AttendanceState,
    /** They arrived at least once (in person or remote), an arrival that was not voided. */
    val attended: Boolean,
    /** How they first arrived; null when they never did. */
    val firstMode: AttendanceMode?,
    /** Arrived after the first item started, or ten minutes after the start when no item was started. */
    val joinedLate: Boolean,
    /** Left and did not come back. */
    val leftEarly: Boolean,
)

/** A held meeting with its seat holders on its date and its register (design E08 §2.1 M1). */
data class HeldMeetingAttendance(
    val meeting: HeldMeeting,
    val seats: List<SeatHolder>,
    val people: List<AttendanceFact>,
)

/**
 * The attendance facts behind analytics' attendance (ADR-0070, design E08 §2.1 M1, §2.2) for other modules: the held
 * meetings of some committees in a period, each with the seats in office on its date (`ForumDirectory.membersOn`: the
 * chair, vice-chairs and members; not secretaries, observers or standing invitees) and its register rows. Meetings above
 * [clearanceRank] are left out, so their existence is not counted. Set-based: four queries whatever the number of
 * meetings, plus the forum rosters (cached). Read only, inside a tenant transaction; no access decision is made here
 * beyond the clearance (the caller decides whose committees these are).
 */
@Component
class AttendanceStats(
    private val meetings: MeetingStore,
    private val store: AttendanceStore,
    private val progress: MeetingProgressStore,
    private val directory: ForumDirectory,
    private val clearances: Clearances,
) {
    /**
     * The meetings of [forumIds] held with a date (in their zone) from [from] to [to], at a level within [clearanceRank],
     * by date, with their seats and registers.
     */
    fun held(
        forumIds: Set<UUID>,
        from: LocalDate,
        to: LocalDate,
        clearanceRank: Int,
    ): List<HeldMeetingAttendance> {
        val ranks = clearances.levels().associate { it.id to it.rank }
        // Starts a day either side of the period in UTC: a meeting's own zone decides its date.
        val rows =
            meetings
                .held(
                    forumIds,
                    from.minusDays(1).atStartOfDay(ZoneId.of("UTC")).toInstant(),
                    to.plusDays(2).atStartOfDay(ZoneId.of("UTC")).toInstant(),
                ).filter { !it.date.isBefore(from) && !it.date.isAfter(to) }
                .filter { (ranks[it.classificationId] ?: Int.MAX_VALUE) <= clearanceRank }
        if (rows.isEmpty()) return emptyList()
        val ids = rows.map { it.id }
        val registers = store.rowsOf(ids)
        val modes = store.firstArrivalModes(ids)
        val firstItems = progress.firstStarts(ids)
        return rows.map { row ->
            val late = firstItems[row.id] ?: row.startsAt.plus(LATE_AFTER_START)
            val seats =
                directory
                    .membersOn(row.forumId, row.date)
                    .filter { it.role in SEATS }
                    .map { SeatHolder(it.userId, it.memberId, it.role, it.startsOn, it.until) }
            val people =
                registers[row.id].orEmpty().map { line ->
                    val arrived = line.standing.firstArrivedAt
                    AttendanceFact(
                        userId = line.userId,
                        capacity = line.capacity,
                        forUserId = line.forUserId,
                        state = line.standing.state,
                        attended = arrived != null,
                        firstMode = modes[row.id to line.userId] ?: line.standing.mode,
                        joinedLate = arrived?.isAfter(late) == true,
                        leftEarly = line.standing.state == AttendanceState.LEFT,
                    )
                }
            HeldMeetingAttendance(HeldMeeting(row.id, row.forumId, row.label, row.date, row.startsAt, row.zone), seats, people)
        }
    }

    private companion object {
        val SEATS = setOf(ForumRole.CHAIR, ForumRole.VICE_CHAIR, ForumRole.MEMBER)
        val LATE_AFTER_START: Duration = AttendanceRules.LATE_AFTER_START
    }
}
