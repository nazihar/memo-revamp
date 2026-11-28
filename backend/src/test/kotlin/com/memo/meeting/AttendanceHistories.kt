package com.memo.meeting

import java.util.UUID

/**
 * The attendance histories of design E08 §8 (analytics' volume, test plan E08 T5) that [MeetingVolume] writes over E05's
 * rows when [on]: every committee but the first seated ([SEATED] people: a chair, a vice-chair and members, one term
 * ended two years ago and its successor's begun), members sending apologies to [APOLOGIES_PCT] % of the meetings and
 * missing [ABSENT_PCT] % without a word (so they attend about 90 %), [LATE_PCT] % arriving late, [LEAVES_PCT] % leaving
 * early and [RETURNS_PCT] % stepping out and back, by a chance fixed per person and meeting, and the alternates of three
 * seats standing in when their member sent apologies. The held focal meeting keeps E05's pattern, which the E05 and E06
 * volume tests measure. Off, every answer is E05's pattern by seat number, so the volume is what it was.
 */
class AttendanceHistories(
    private val tenant: UUID,
    /** Who records the alternates' arrivals. */
    private val secretary: UUID,
    private val on: Boolean,
) {
    /**
     * The other committees' seats: their first [SEATED] invitees, the first the chair and the second a vice-chair, seated
     * since 2010; the twelfth's term ended two years ago and the thirteenth took the seat the next day; seats 3 to 5 have
     * an alternate ([alternate]). Uses [MeetingVolume]'s `mv_forum`.
     */
    fun seats(): List<String> =
        if (!on) {
            emptyList()
        } else {
            listOf(
                """
                insert into forum_member (id, tenant_id, forum_id, kind, user_id, role, voting, attributes, alternate_user_id, starts_on,
                                          ended_on, end_reason, term_number, created_at, version)
                select md5('mvfm' || f.f || '-' || u)::uuid, '$tenant'::uuid, f.id, 'PERSON', md5('mvu' || f.f || '-' || u)::uuid,
                       case u when 1 then 'CHAIR' when 2 then 'VICE_CHAIR' else 'MEMBER' end, true, '[]'::jsonb,
                       case when u between $ALTERNATE_SEATS then ${alternate("f.f", "u")} end,
                       case when u = $SEATED then current_date - 729 else date '2010-01-01' end,
                       case when u = ${SEATED - 1} then current_date - 730 end,
                       case when u = ${SEATED - 1} then 'TERM_COMPLETED' end, 1, now(), 0
                from mv_forum f, generate_series(1, $SEATED) u
                where f.f > 1
                """,
            )
        }

    /** Invitee [u] sends apologies to meeting [meeting]: one in five (E05's volume), or by chance but at [focal]. */
    fun apologies(
        meeting: String,
        u: String,
        focal: String,
    ) = if (on) "case when $focal then $u % 5 = 3 else ${chance(meeting, u)} < $APOLOGIES_PCT end" else "$u % 5 = 3"

    /** The member of seat 3, 4 or 5 of another committee who sends apologies asks the alternate to attend. */
    fun standsIn() = if (on) "answer = 'APOLOGIES' and f > 1 and u between $ALTERNATE_SEATS" else "false"

    /**
     * The columns `absent`, `returns` (left and came back), `leaves` (left early) and `late` (minutes after the start) of
     * an invitee `u` at `meeting_id`, [held] the condition of the held focal meeting.
     */
    fun columns(held: String): String {
        val chance = chance("meeting_id", "u")
        val absent = if (on) "not ($held) and $chance between $APOLOGIES_PCT and ${APOLOGIES_PCT + ABSENT_PCT - 1}" else "false"
        val returns = if (on) "$chance between $RETURNS_FROM and ${RETURNS_FROM + RETURNS_PCT - 1}" else "u % 2 = 0"
        val leaves = if (on) "$chance between $LEAVES_FROM and ${LEAVES_FROM + LEAVES_PCT - 1}" else "u % 4 = 1"
        val late =
            if (on) {
                "case when $chance between $LATE_FROM and ${LATE_FROM + LATE_PCT - 1} then 15 + $chance % 30 else u % 10 end"
            } else {
                "u % 12"
            }
        return "$absent as absent, $returns as returns, $leaves as leaves, $late as late"
    }

    /**
     * The alternate of seat 3, 4 or 5 of another committee attends when its member sent apologies (the reply asked them
     * to): in the audience for that seat, on the register as ALTERNATE, arrived at the start. Uses `mv_attendee`.
     */
    fun alternates(): List<String> {
        if (!on) return emptyList()
        val standing = "from mv_attendee where apologies and f > 1 and u between $ALTERNATE_SEATS"
        return listOf(
            """
            insert into meeting_audience (tenant_id, meeting_id, user_id, source, members_only, for_member_id, for_user_id)
            select '$tenant'::uuid, meeting_id, ${alternate("f", "u")}, 'ALTERNATE', true, md5('mvfm' || f || '-' || u)::uuid, user_id
            $standing
            """,
            """
            insert into meeting_attendance (tenant_id, meeting_id, user_id, capacity, for_member_id, for_user_id, state, mode,
                                            first_arrived_at, last_left_at, opened_at, updated_at, version)
            select '$tenant'::uuid, meeting_id, ${alternate("f", "u")}, 'ALTERNATE', md5('mvfm' || f || '-' || u)::uuid, user_id,
                   'PRESENT', 'IN_PERSON', starts_at + interval '2 minutes', null, starts_at, starts_at + interval '3 hours', 0
            $standing
            """,
            """
            insert into meeting_attendance_event (id, tenant_id, meeting_id, user_id, kind, mode, at, item_id, source, correction,
                                                  recorded_by, recorded_at)
            select md5('mvaealt' || meeting_id || user_id)::uuid, '$tenant'::uuid, meeting_id, ${alternate("f", "u")}, 'ARRIVED',
                   'IN_PERSON', starts_at + interval '2 minutes', null, 'RECORDED', false, '$secretary',
                   starts_at + interval '2 minutes'
            $standing
            """,
        )
    }

    /** Seat [u]'s alternate on committee [f]. */
    private fun alternate(
        f: String,
        u: String,
    ) = "md5('mvalt' || $f || '-' || $u)::uuid"

    /** A number from 0 to 99 fixed for invitee [u] at meeting [meeting]: what happens to them there. */
    private fun chance(
        meeting: String,
        u: String,
    ) = "abs(hashtext($meeting::text || '-' || $u)) % 100"

    companion object {
        /** The seats of the other committees, the last one a successor's. */
        const val SEATED = 13

        /** The seats whose alternate attends when their member sends apologies. */
        const val ALTERNATE_SEATS = "3 and 5"

        /** The share of invitations (in %) answered with apologies, and missed without a word. */
        const val APOLOGIES_PCT = 7
        const val ABSENT_PCT = 3

        /** The shares (in %) that step out and come back, leave early, or arrive late, by chance. */
        const val RETURNS_FROM = 10
        const val RETURNS_PCT = 20
        const val LEAVES_FROM = 30
        const val LEAVES_PCT = 8
        const val LATE_FROM = 40
        const val LATE_PCT = 10
    }
}
