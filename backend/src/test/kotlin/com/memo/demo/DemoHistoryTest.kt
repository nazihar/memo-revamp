package com.memo.demo

import com.memo.demo.DemoHistory.Committee
import com.memo.demo.DemoHistory.Presence
import com.memo.demo.DemoHistory.Seat
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

/** The plan of the demo committees' year of history (Phase 4 E08 T8): months, days, titles and attendance. */
class DemoHistoryTest {
    private val today = LocalDate.of(2026, 11, 28)

    @Test
    fun `the risk committee meets on the second Tuesday of each of the eleven months before this one`() {
        val plan = DemoHistory.plan(Committee.RISK, today, from = today.minusYears(1))
        assertThat(plan.map { it.month }).containsExactlyElementsOf((1L..11L).map { YearMonth.of(2026, 11).minusMonths(it) })
        assertThat(plan).allSatisfy {
            assertThat(it.day.dayOfWeek).isEqualTo(DayOfWeek.TUESDAY)
            assertThat(it.day.dayOfMonth).isBetween(8, 14)
            assertThat(it.day).isAfter(today.minusYears(1)).isBefore(today)
        }
        assertThat(plan.first().title).isEqualTo("Risk review, October 2026")
        assertThat(plan.last().title).isEqualTo("Risk review, December 2025")
        assertThat(plan.map { it.title }).doesNotHaveDuplicates()
    }

    @Test
    fun `the credit committee meets every other month on the third Wednesday`() {
        val plan = DemoHistory.plan(Committee.CREDIT, today)
        assertThat(plan.map { it.month }).containsExactly(
            YearMonth.of(2026, 9),
            YearMonth.of(2026, 7),
            YearMonth.of(2026, 5),
            YearMonth.of(2026, 3),
            YearMonth.of(2026, 1),
        )
        assertThat(plan).allSatisfy {
            assertThat(it.day.dayOfWeek).isEqualTo(DayOfWeek.WEDNESDAY)
            assertThat(it.day.dayOfMonth).isBetween(15, 21)
        }
    }

    @Test
    fun `nothing is planned before the seats' terms start`() {
        val plan = DemoHistory.plan(Committee.RISK, today)
        assertThat(plan.map { it.title }).doesNotContain("Risk review, December 2025").hasSize(10)
        assertThat(plan).allSatisfy { assertThat(it.day).isAfterOrEqualTo(DemoForumSeeder.START) }
    }

    @Test
    fun `a month in which the committee already met is left out`() {
        val plan = DemoHistory.plan(Committee.RISK, today, listOf(LocalDate.of(2026, 10, 30), LocalDate.of(2026, 11, 4)))
        assertThat(plan.map { it.month }).doesNotContain(YearMonth.of(2026, 10)).hasSize(9)
        assertThat(plan.first().index).isZero()
    }

    @Test
    fun `at most one seat is varied at a meeting, so every decision has its quorum`() {
        Committee.entries.forEach { committee ->
            (0..12).forEach { index ->
                assertThat(DemoHistory.presence(committee, index).values.count { it != Presence.ON_TIME }).isLessThanOrEqualTo(1)
            }
        }
        // The BRC's chairman (the second independent member) is never away, only late.
        (0..12).forEach { assertThat(DemoHistory.presence(Committee.RISK, it).getValue(Seat.SECOND)).isIn(Presence.ON_TIME, Presence.LATE) }
    }

    @Test
    fun `the risk committee's year has every way of attending analytics counts`() {
        val year = DemoHistory.plan(Committee.RISK, today, from = today.minusYears(1)).map { it.presence }
        val director = year.map { it.getValue(Seat.THIRD) }
        assertThat(director.count { it == Presence.APOLOGIES_ALTERNATE }).isEqualTo(2)
        assertThat(director.count { it == Presence.APOLOGIES }).isOne()
        assertThat(director.count { it == Presence.ABSENT }).isOne()
        assertThat(year.count { it.getValue(Seat.SECOND) == Presence.LATE }).isEqualTo(2)
        assertThat(year.count { it.getValue(Seat.FOURTH) == Presence.LEFT_EARLY }).isEqualTo(2)
    }
}
