package com.memo.demo

import com.memo.testsupport.AppRoleJdbc
import com.memo.testsupport.AppRoleJdbc.long
import org.assertj.core.api.Assertions.assertThat
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** The history's rows as the app role reads them (Phase 4 E08 T8), for [DemoMeetingsIT]. */
internal object DemoHistoryRows {
    /**
     * E08 T8: the history. Each meeting held ([held]: whether, and when it started), in the last year before [today]; the
     * BRC's nine to eleven and the MCC's four or five (none before the seats' terms start); then [assertRegisters] with
     * [people] (the director and their alternate).
     */
    fun assertHistory(
        meetings: Map<String, UUID>,
        today: LocalDate,
        held: (UUID) -> Pair<Boolean, Instant>,
        people: Pair<UUID, UUID>,
        forums: List<UUID>,
    ) {
        val risk = meetings.filterKeys { it.startsWith("Risk review, ") }
        assertThat(risk.size).isBetween(9, 11)
        assertThat(meetings.keys.count { it.startsWith("Credit review, ") }).isBetween(4, 5)
        meetings.forEach { (title, meeting) ->
            val (isHeld, startsAt) = held(meeting)
            assertThat(isHeld).describedAs(title).isTrue()
            assertThat(startsAt.atZone(DemoMeetingSeeder.ZONE).toLocalDate()).isAfter(today.minusYears(1)).isBefore(today)
        }
        assertRegisters(meetings, risk.values, people.first, people.second, forums)
    }

    /**
     * Each history meeting's three decisions and two resolutions; over the BRC's year ([risk]) the director's apologies
     * (two with the [alternate] standing in), one absence, two late arrivals and two early departures; the held meetings of
     * the last year of [forums] (the BRC, then the MCC), with the held reviews.
     */
    fun assertRegisters(
        meetings: Map<String, UUID>,
        risk: Collection<UUID>,
        director: UUID,
        alternate: UUID,
        forums: List<UUID>,
    ) {
        AppRoleJdbc.inTransaction(DemoTenant.ID) { c ->
            meetings.forEach { (title, meeting) ->
                assertThat(c.long("select count(*) from minutes_decision where meeting_id = ?", meeting)).describedAs(title).isEqualTo(3)
                assertThat(c.long("select count(*) from minutes_resolution where meeting_id = ?", meeting)).describedAs(title).isEqualTo(2)
            }
            val ids = risk.joinToString(",") { "'$it'" }
            val rsvps = "select count(*) from meeting_rsvp where meeting_id in ($ids) and user_id = ?"
            assertThat(c.long("$rsvps and alternate_attends", director)).isEqualTo(2)
            assertThat(c.long("$rsvps and answer = 'APOLOGIES'", director)).isEqualTo(3)
            assertThat(
                c.long(
                    "select count(*) from meeting_attendance where meeting_id in ($ids) and capacity = 'ALTERNATE' " +
                        "and user_id = ? and for_user_id = ? and state in ('PRESENT', 'LEFT')",
                    alternate,
                    director,
                ),
            ).isEqualTo(2)
            assertThat(c.long("select count(*) from meeting_attendance where meeting_id in ($ids) and state = 'ABSENT'")).isOne()
            assertThat(
                c.long(
                    "select count(*) from meeting_attendance a join meeting m on m.id = a.meeting_id " +
                        "where a.meeting_id in ($ids) and a.first_arrived_at > m.starts_at",
                ),
            ).isEqualTo(2)
            assertThat(c.long("select count(*) from meeting_attendance_event where meeting_id in ($ids) and kind = 'LEFT'"))
                .isEqualTo(2)
            val heldInYear = { forum: UUID ->
                c.long(
                    "select count(*) from meeting where forum_id = ? and status = 'HELD' and starts_at > now() - interval '365 days'",
                    forum,
                )
            }
            assertThat(heldInYear(forums[0])).isBetween(10L, 12L)
            assertThat(heldInYear(forums[1])).isBetween(5L, 6L)
        }
    }

    /** The history meetings' rows, to compare before and after a second run. */
    fun rows(meetings: Map<String, UUID>): List<Long> =
        AppRoleJdbc.inTransaction(DemoTenant.ID) { c ->
            val ids = meetings.values.joinToString(",") { "'$it'" }
            listOf("meeting_rsvp", "meeting_attendance_event", "meeting_item_progress", "minutes_decision", "minutes_resolution")
                .map { c.long("select count(*) from $it where meeting_id in ($ids)") }
        }
}
