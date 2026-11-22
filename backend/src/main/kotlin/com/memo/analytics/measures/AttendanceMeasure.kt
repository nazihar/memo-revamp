package com.memo.analytics.measures

import com.memo.forum.ForumRole
import com.memo.meeting.AttendanceCapacity
import com.memo.meeting.AttendanceFact
import com.memo.meeting.AttendanceMode
import com.memo.meeting.AttendanceState
import com.memo.meeting.HeldMeetingAttendance
import java.time.LocalDate
import java.util.UUID

/** How a seat holder stood at one held meeting (design E08 §2.1 M1). */
enum class SeatAttendance {
    /** Attended in person (their first arrival). */
    IN_PERSON,

    /** Attended remotely (their first arrival). */
    REMOTE,

    /** Did not attend, with apologies. */
    APOLOGIES,

    /** Did not attend, without apologies (marked absent, or never marked). */
    ABSENT,
}

/** One held meeting of a seat holder's term. */
data class MemberMeeting(
    val meetingId: UUID,
    val date: LocalDate,
    val attendance: SeatAttendance,
    /** Their alternate attended in their place: shown, never counted as their attendance. */
    val byAlternate: Boolean,
    val joinedLate: Boolean,
    val leftEarly: Boolean,
)

/**
 * A seat holder's attendance in a period (design E08 §2.1 M1, flow AV): [expected] is the held meetings during their
 * term, [attended] those where they arrived (in person or remotely) at any time; the rest are [apologies] or [absent].
 * [byAlternate] counts meetings their alternate attended for them, which are never in [attended].
 */
data class MemberAttendance(
    val userId: UUID,
    /** Their role at the last held meeting of the period. */
    val role: ForumRole,
    /** The first and last day of their seat within the period (the term's part the period covers). */
    val seatFrom: LocalDate,
    val seatTo: LocalDate,
    val expected: Int,
    val attended: Int,
    val inPerson: Int,
    val remote: Int,
    val joinedLate: Int,
    val leftEarly: Int,
    val apologies: Int,
    val absent: Int,
    val byAlternate: Int,
    val meetings: List<MemberMeeting>,
) {
    /** attended ÷ expected in whole percent; null without an expected meeting. */
    val ratePercent: Int? get() = percent(attended, expected)
}

/** A committee's attendance in a period: the sums over every seat holder, and the held meetings counted. */
data class CommitteeAttendance(
    val heldMeetings: Int,
    val expected: Int,
    val attended: Int,
) {
    val ratePercent: Int? get() = percent(attended, expected)
}

/**
 * Measure M1, attendance (ADR-0070, design E08 §2.1): one definition for the screen, the export and the tests.
 *  - A seat holder is the chair, a vice-chair or a member in office on the meeting's date; secretaries, observers and
 *    standing invitees are not, and neither are alternates, guests or item attendees.
 *  - Expected: each held meeting of the committee while they held the seat (a term starting or ending in the period
 *    counts only its meetings).
 *  - Attended: their register line reached PRESENT at some time (in person or remote, by their first arrival), after
 *    voids; joining late, leaving early and stepping out of an item (a recusal) still attended.
 *  - Otherwise APOLOGIES when the register says so, else ABSENT; "represented by alternate" when an alternate attended
 *    for them, which is shown and never counted as theirs.
 *  - The committee's rate is the sum of attended over the sum of expected.
 */
object AttendanceMeasure {
    /** Each seat holder's attendance over [meetings] (one committee's held meetings in [period]), by user id. */
    fun members(
        meetings: List<HeldMeetingAttendance>,
        period: Period,
    ): List<MemberAttendance> {
        val ordered = meetings.sortedWith(compareBy({ it.meeting.date }, { it.meeting.startsAt }, { it.meeting.id }))
        val byPerson =
            ordered
                .flatMap { held ->
                    held.seats
                        .distinctBy { it.userId }
                        .map { seat -> seat.userId to Triple(held, seat, meetingOf(held, seat.userId)) }
                }.groupBy({ it.first }, { it.second })
        return byPerson
            .map { (user, terms) ->
                val lines = terms.map { it.third }
                val seats = terms.map { it.second }
                val lastUntil = if (seats.any { it.until == null }) null else seats.mapNotNull { it.until }.maxOrNull()
                MemberAttendance(
                    userId = user,
                    role = terms.last().second.role,
                    seatFrom = maxOf(period.from, seats.minOf { it.startsOn }),
                    seatTo = lastUntil?.let { minOf(period.to, it) } ?: period.to,
                    expected = lines.size,
                    attended = lines.count { it.attendance.attended },
                    inPerson = lines.count { it.attendance == SeatAttendance.IN_PERSON },
                    remote = lines.count { it.attendance == SeatAttendance.REMOTE },
                    joinedLate = lines.count { it.joinedLate },
                    leftEarly = lines.count { it.leftEarly },
                    apologies = lines.count { it.attendance == SeatAttendance.APOLOGIES },
                    absent = lines.count { it.attendance == SeatAttendance.ABSENT },
                    byAlternate = lines.count { it.byAlternate },
                    meetings = lines,
                )
            }.sortedBy { it.userId }
    }

    /** The committee's attendance over [meetings]: every seat holder's expected and attended meetings summed. */
    fun committee(meetings: List<HeldMeetingAttendance>): CommitteeAttendance {
        val lines = meetings.flatMap { held -> held.seats.distinctBy { it.userId }.map { meetingOf(held, it.userId) } }
        return CommitteeAttendance(meetings.size, lines.size, lines.count { it.attendance.attended })
    }

    /** How seat holder [userId] stood at [held]. */
    fun meetingOf(
        held: HeldMeetingAttendance,
        userId: UUID,
    ): MemberMeeting {
        val line = lineOf(held.people, userId)
        val present = line?.takeIf { it.attended }
        val attendance =
            when {
                present?.firstMode == AttendanceMode.REMOTE -> SeatAttendance.REMOTE
                present != null -> SeatAttendance.IN_PERSON
                line?.state == AttendanceState.APOLOGIES -> SeatAttendance.APOLOGIES
                else -> SeatAttendance.ABSENT
            }
        val alternate =
            present == null &&
                held.people.any { it.capacity == AttendanceCapacity.ALTERNATE && it.forUserId == userId && it.attended }
        return MemberMeeting(
            meetingId = held.meeting.id,
            date = held.meeting.date,
            attendance = attendance,
            byAlternate = alternate,
            joinedLate = present?.joinedLate == true,
            leftEarly = present?.leftEarly == true,
        )
    }

    /** The person's own line (as a member of the roster first), never a line where they stood in for someone else. */
    private fun lineOf(
        people: List<AttendanceFact>,
        userId: UUID,
    ): AttendanceFact? {
        val own = people.filter { it.userId == userId && it.capacity != AttendanceCapacity.ALTERNATE }
        return own.firstOrNull { it.capacity == AttendanceCapacity.MEMBER } ?: own.firstOrNull()
    }

    private val SeatAttendance.attended: Boolean get() = this == SeatAttendance.IN_PERSON || this == SeatAttendance.REMOTE
}
