package com.memo.analytics.measures

import com.memo.actions.ClosedOnTime
import com.memo.actions.OverdueActions
import com.memo.meeting.LateCounts
import com.memo.meeting.MeetingTimeliness
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Measures M2 and M5 and the period's rules (design E08 §2.1; test plan E08 T4 `ActionMeasureTest`, `TimelinessTest`):
 * overdue summed over owners with their ageing bands, closed by the original against the agreed date; a first revision
 * published before, on and after the deadline, never published, or with no deadline; late counts for officers only;
 * the default period, its limits and the shares' rounding.
 */
class MeasuresTest {
    private val brc = UUID.randomUUID()
    private val mcc = UUID.randomUUID()
    private val treasury = UUID.randomUUID()
    private val karim = UUID.randomUUID()

    @Test
    fun `overdue actions summed per committee, with the owners most overdue first`() {
        val rows =
            listOf(
                OverdueActions(brc, null, treasury, karim, 2, 1, 1, 0, 1),
                OverdueActions(brc, karim, null, karim, 3, 1, 0, 2, 2),
                OverdueActions(mcc, karim, null, karim, 1, 1, 0, 0, 0),
            )
        val closed = listOf(ClosedOnTime(brc, 18, 12, 16))
        val committees = ActionMeasure.committees(setOf(brc, mcc), rows, closed)
        assertThat(committees.getValue(brc).overdue).isEqualTo(Overdue(5, 2, 1, 2, 3))
        assertThat(committees.getValue(brc).closed).isEqualTo(Closed(18, 12, 16))
        assertThat(committees.getValue(brc).closed.byOriginalPercent to committees.getValue(brc).closed.byAgreedPercent)
            .isEqualTo(67 to 89)
        assertThat(committees.getValue(mcc).closed).isEqualTo(Closed.NONE)
        assertThat(committees.getValue(mcc).closed.byOriginalPercent).isNull()
        val owners = ActionMeasure.owners(brc, rows)
        assertThat(owners.map { it.ownerUserId to it.overdue.count }).containsExactly(karim to 3, null to 2)
        assertThat(owners[1].ownerPositionId).isEqualTo(treasury)
    }

    private fun timeliness(
        id: UUID,
        published: LocalDate?,
        deadline: LocalDate?,
    ) = id to MeetingTimeliness(id, published?.let { Instant.parse("${it}T04:00:00Z") }, published, deadline, published?.let { 1 })

    @Test
    fun `papers on time by the first revision against the deadline, late counts for officers only`() {
        val deadline = LocalDate.of(2026, 3, 5)
        val before = UUID.randomUUID()
        val onTheDay = UUID.randomUUID()
        val after = UUID.randomUUID()
        val never = UUID.randomUUID()
        val noDeadline = UUID.randomUUID()
        val ids = listOf(before, onTheDay, after, never, noDeadline)
        val map =
            mapOf(
                timeliness(before, deadline.minusDays(2), deadline),
                timeliness(onTheDay, deadline, deadline),
                timeliness(after, deadline.plusDays(1), deadline),
                timeliness(never, null, deadline),
                timeliness(noDeadline, deadline, null),
            )
        val late = mapOf(after to LateCounts(after, 2, 3))
        val officers = TimelinessMeasure.meetings(ids, map, late)
        assertThat(officers.map { it.onTime }).containsExactly(true, true, false, false, null)
        val committee = TimelinessMeasure.committee(officers, withLate = true)
        assertThat(committee).isEqualTo(CommitteePapers(5, 4, 2, 2, 3))
        assertThat(committee.onTimePercent).isEqualTo(50)
        val members = TimelinessMeasure.meetings(ids, map, null)
        assertThat(members.map { it.lateItems }.toSet()).containsOnly(null)
        assertThat(TimelinessMeasure.committee(members, withLate = false).lateItems).isNull()
    }

    @Test
    fun `the period defaults to the last twelve months and refuses more than its limit or a backward range`() {
        val today = LocalDate.of(2026, 11, 22)
        assertThat(Period.of(null, null, today)).isEqualTo(Period(LocalDate.of(2025, 11, 23), today))
        assertThat(Period.of(LocalDate.of(2016, 11, 23), today, today).within(Period.MAX_YEARS)).isTrue()
        assertThatThrownBy { Period.of(LocalDate.of(2016, 11, 22), today, today) }
            .isInstanceOfSatisfying(AnalyticsRejectedException::class.java) { e ->
                assertThat(e.problems.map { it.field to it.code }).containsExactly("from" to "RANGE")
            }
        assertThatThrownBy { Period.of(LocalDate.of(2020, 1, 1), today, today, Period.MAX_MEMBER_YEARS) }
            .isInstanceOf(AnalyticsRejectedException::class.java)
        assertThatThrownBy { Period.of(today, today.minusDays(1), today) }
            .isInstanceOfSatisfying(AnalyticsRejectedException::class.java) { e ->
                assertThat(e.problems.map { it.field to it.code }).containsExactly("to" to "RANGE")
            }
        assertThat(LocalDate.of(2026, 1, 1) in Period(LocalDate.of(2026, 1, 1), today)).isTrue()
    }

    @Test
    fun `shares are whole percents rounded half up, and none without a basis`() {
        assertThat(percent(10, 12)).isEqualTo(83)
        assertThat(percent(11, 12)).isEqualTo(92)
        assertThat(percent(1, 8)).isEqualTo(13)
        assertThat(percent(0, 0)).isNull()
    }
}
