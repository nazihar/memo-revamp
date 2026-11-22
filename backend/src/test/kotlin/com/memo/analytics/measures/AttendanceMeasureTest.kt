package com.memo.analytics.measures

import com.memo.forum.ForumRole
import com.memo.meeting.AttendanceCapacity
import com.memo.meeting.AttendanceFact
import com.memo.meeting.AttendanceMode
import com.memo.meeting.AttendanceState
import com.memo.meeting.HeldMeeting
import com.memo.meeting.HeldMeetingAttendance
import com.memo.meeting.SeatHolder
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

/**
 * Measure M1's definition (design E08 §2.1; test plan E08 T4 `AttendanceMeasureTest`) on hand-built registers: a term
 * starting and ending in the period, an alternate attending, a voided arrival (the register's derived line), joining late
 * and leaving early and stepping out of an item still attended, apologies against absence, someone never marked, and the
 * committee's rate over every seat.
 */
class AttendanceMeasureTest {
    private val forum = UUID.randomUUID()
    private val chair = UUID.fromString("00000000-0000-0000-0000-000000000001")
    private val director = UUID.fromString("00000000-0000-0000-0000-000000000002")
    private val newcomer = UUID.fromString("00000000-0000-0000-0000-000000000003")
    private val alternate = UUID.randomUUID()
    private val secretary = UUID.randomUUID()
    private val period = Period(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31))

    private fun seat(
        user: UUID,
        role: ForumRole = ForumRole.MEMBER,
        from: LocalDate = LocalDate.of(2020, 1, 1),
        until: LocalDate? = null,
    ) = SeatHolder(user, UUID.nameUUIDFromBytes(user.toString().toByteArray()), role, from, until)

    private fun present(
        user: UUID,
        mode: AttendanceMode = AttendanceMode.IN_PERSON,
        late: Boolean = false,
        left: Boolean = false,
    ) = AttendanceFact(
        user,
        AttendanceCapacity.MEMBER,
        null,
        if (left) AttendanceState.LEFT else AttendanceState.PRESENT,
        true,
        mode,
        late,
        left,
    )

    private fun marked(
        user: UUID,
        state: AttendanceState,
    ) = AttendanceFact(user, AttendanceCapacity.MEMBER, null, state, false, null, joinedLate = false, leftEarly = false)

    private fun meeting(
        month: Int,
        seats: List<SeatHolder>,
        people: List<AttendanceFact>,
    ): HeldMeetingAttendance {
        val date = LocalDate.of(2026, month, 10)
        return HeldMeetingAttendance(
            HeldMeeting(
                UUID.randomUUID(),
                forum,
                "BRC $month/2026",
                date,
                date.atTime(10, 0).toInstant(ZoneOffset.UTC),
                ZoneId.of("Asia/Dhaka"),
            ),
            seats,
            people,
        )
    }

    /** The acceptance case: 12 held meetings, present at 10, apologies at 1 with the alternate attending, absent at 1. */
    @Test
    fun `ten of twelve, one represented by alternate, one absent`() {
        val meetings =
            (1..12).map { month ->
                val people =
                    when (month) {
                        3 -> {
                            listOf(
                                marked(director, AttendanceState.APOLOGIES),
                                AttendanceFact(
                                    alternate,
                                    AttendanceCapacity.ALTERNATE,
                                    director,
                                    AttendanceState.PRESENT,
                                    true,
                                    AttendanceMode.IN_PERSON,
                                    false,
                                    false,
                                ),
                            )
                        }

                        7 -> {
                            listOf(marked(director, AttendanceState.ABSENT))
                        }

                        else -> {
                            listOf(present(director, if (month % 4 == 0) AttendanceMode.REMOTE else AttendanceMode.IN_PERSON))
                        }
                    }
                meeting(month, listOf(seat(director)), people)
            }
        val row = AttendanceMeasure.members(meetings, period).single()
        assertThat(row.expected to row.attended).isEqualTo(12 to 10)
        assertThat(row.ratePercent).isEqualTo(83)
        assertThat(row.apologies to row.absent).isEqualTo(1 to 1)
        assertThat(row.byAlternate).isEqualTo(1)
        assertThat(row.remote to row.inPerson).isEqualTo(3 to 7)
        // The alternate holds no seat: they have no row, and their attendance is never the member's.
        assertThat(AttendanceMeasure.members(meetings, period).map { it.userId }).containsExactly(director)
    }

    @Test
    fun `a term starting in the period counts only the meetings of the term, and the committee sums every seat`() {
        val joined = LocalDate.of(2026, 7, 1)
        val meetings =
            (1..12).map { month ->
                val seats = listOfNotNull(seat(chair, ForumRole.CHAIR), seat(newcomer, from = joined).takeIf { month >= 7 })
                meeting(month, seats, listOf(present(chair), present(newcomer)))
            }
        val rows = AttendanceMeasure.members(meetings, period).associateBy { it.userId }
        assertThat(rows.getValue(newcomer).expected).isEqualTo(6)
        assertThat(rows.getValue(newcomer).seatFrom).isEqualTo(joined)
        assertThat(rows.getValue(chair).seatFrom).isEqualTo(period.from)
        assertThat(rows.getValue(chair).role).isEqualTo(ForumRole.CHAIR)
        val committee = AttendanceMeasure.committee(meetings)
        assertThat(committee).isEqualTo(CommitteeAttendance(12, 18, 18))
        assertThat(committee.ratePercent).isEqualTo(100)
    }

    @Test
    fun `a term ending in the period stops counting, and its last day bounds the seat`() {
        val ended = LocalDate.of(2026, 4, 30)
        val meetings =
            (1..6).map { month ->
                meeting(month, listOfNotNull(seat(director, until = ended).takeIf { month <= 4 }), listOf(present(director)))
            }
        val row = AttendanceMeasure.members(meetings, period).single()
        assertThat(row.expected).isEqualTo(4)
        assertThat(row.seatTo).isEqualTo(ended)
    }

    @Test
    fun `joining late, leaving early and stepping out of an item still attended`() {
        val meetings =
            listOf(
                meeting(1, listOf(seat(director)), listOf(present(director, late = true))),
                meeting(2, listOf(seat(director)), listOf(present(director, left = true))),
                // Stepped out of an item for a declared interest: the register line stays PRESENT.
                meeting(3, listOf(seat(director)), listOf(present(director))),
            )
        val row = AttendanceMeasure.members(meetings, period).single()
        assertThat(row.attended).isEqualTo(3)
        assertThat(row.joinedLate to row.leftEarly).isEqualTo(1 to 1)
    }

    @Test
    fun `a voided arrival is not attendance, and someone never marked is absent without apologies`() {
        // The register's line after an arrival was voided: never arrived, still expected.
        val voided = marked(director, AttendanceState.EXPECTED)
        val meetings =
            listOf(
                meeting(1, listOf(seat(director), seat(chair, ForumRole.CHAIR)), listOf(voided)),
                meeting(2, listOf(seat(director)), listOf(marked(director, AttendanceState.APOLOGIES))),
            )
        val rows = AttendanceMeasure.members(meetings, period).associateBy { it.userId }
        assertThat(rows.getValue(director).attended).isZero()
        assertThat(rows.getValue(director).absent to rows.getValue(director).apologies).isEqualTo(1 to 1)
        assertThat(rows.getValue(chair).absent).isEqualTo(1)
        assertThat(rows.getValue(director).meetings.map { it.attendance })
            .containsExactly(SeatAttendance.ABSENT, SeatAttendance.APOLOGIES)
    }

    @Test
    fun `apologies then arriving after all is attendance, and an alternate never stands for someone who attended`() {
        val late =
            AttendanceFact(director, AttendanceCapacity.MEMBER, null, AttendanceState.PRESENT, true, AttendanceMode.REMOTE, true, false)
        val alt =
            AttendanceFact(
                alternate,
                AttendanceCapacity.ALTERNATE,
                director,
                AttendanceState.PRESENT,
                true,
                AttendanceMode.IN_PERSON,
                false,
                false,
            )
        val row = AttendanceMeasure.members(listOf(meeting(1, listOf(seat(director)), listOf(late, alt))), period).single()
        assertThat(row.attended to row.byAlternate).isEqualTo(1 to 0)
        assertThat(row.remote).isEqualTo(1)
    }

    @Test
    fun `secretaries are not seats, and a committee without held meetings has no rate`() {
        val meetings = listOf(meeting(1, listOf(seat(chair, ForumRole.CHAIR)), listOf(present(chair), present(secretary))))
        assertThat(AttendanceMeasure.members(meetings, period).map { it.userId }).containsExactly(chair)
        assertThat(AttendanceMeasure.committee(emptyList())).isEqualTo(CommitteeAttendance(0, 0, 0))
        assertThat(AttendanceMeasure.committee(emptyList()).ratePercent).isNull()
    }
}
