package com.memo.demo

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.format.TextStyle
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/**
 * The plan of the demo committees' year of history (Phase 4 E08 T8, for [DemoHistorySeeder]): which past months get a
 * held meeting, on which day, with which items, and how each seat holder attended. Pure, so the pattern is tested on its
 * own (`DemoHistoryTest`). The Board Risk Committee meets monthly on the second Tuesday at 10:00, the Management Credit
 * Committee every other month on the third Wednesday at 14:00; a month in which the committee already has a meeting
 * (the E05 and E06 held reviews, a journey's meeting) is left out.
 */
@Suppress("MagicNumber") // The demo's calendar and attendance pattern are data: days, times, months and places in the year.
object DemoHistory {
    /** The demo committees with a history. */
    enum class Committee(
        val titlePrefix: String,
        /** How many months back: the BRC's eleven before its held review, the MCC's five before its own. */
        val monthsBack: IntProgression,
        val weekday: DayOfWeek,
        /** Which [weekday] of the month (2: the second). */
        val week: Int,
        val start: LocalTime,
        val end: LocalTime,
    ) {
        RISK("Risk review", 1..11, DayOfWeek.TUESDAY, 2, LocalTime.of(10, 0), LocalTime.of(12, 0)),
        CREDIT("Credit review", 2..10 step 2, DayOfWeek.WEDNESDAY, 3, LocalTime.of(14, 0), LocalTime.of(16, 0)),
    }

    /** The seats whose attendance the history varies (the secretary always attends, and the chair is always there first). */
    enum class Seat {
        /** BRC: the Chairman's seat (independent); MCC: Demo Head of Credit (vice-chair). */
        SECOND,

        /** BRC: Demo Director (their seat's alternate is Demo Head of Credit); MCC: Demo Head of Finance. */
        THIRD,

        /** BRC: the Chief Risk Officer (standing invitee); MCC: the Chief Risk Officer's seat (member). */
        FOURTH,
    }

    /** How a seat holder attended one meeting. */
    enum class Presence {
        ON_TIME,

        /** Arrived [LATE_BY] after the start, before the first decision. */
        LATE,

        /** Left [EARLY_BY] before the end, after the last decision. */
        LEFT_EARLY,

        /** Attended online (MCC). */
        ONLINE,

        /** Sent apologies in reply to the invitation. */
        APOLOGIES,

        /** Sent apologies; their seat's alternate attended in their place (BRC's Demo Director). */
        APOLOGIES_ALTERNATE,

        /** Neither replied nor came: marked absent without apologies. */
        ABSENT,
    }

    /** One past meeting of the plan. */
    data class Planned(
        val committee: Committee,
        val month: YearMonth,
        val day: LocalDate,
        /** Its place in the committee's history, 0 for the most recent. */
        val index: Int,
        val presence: Map<Seat, Presence>,
        /** The two decision items' titles, then the noting item's. */
        val decisions: List<String>,
        val noting: String,
    ) {
        val title: String get() = "${committee.titlePrefix}, ${month.month.getDisplayName(TextStyle.FULL, Locale.ENGLISH)} ${month.year}"
    }

    val LATE_BY: java.time.Duration = java.time.Duration.ofMinutes(25)
    val EARLY_BY: java.time.Duration = java.time.Duration.ofMinutes(20)

    /**
     * The meetings of [committee]'s history as of [today], most recent first, leaving out the months in which the committee
     * has a meeting on one of [taken] (other than a history meeting of its own title, which the seeder finds by title), and
     * the days before [from] (the demo seats' terms start on [DemoForumSeeder.START]: nobody held a seat before).
     */
    fun plan(
        committee: Committee,
        today: LocalDate,
        taken: Collection<LocalDate> = emptyList(),
        from: LocalDate = DemoForumSeeder.START,
    ): List<Planned> {
        val busy = taken.map(YearMonth::from).toSet()
        return committee.monthsBack
            .map { YearMonth.from(today).minusMonths(it.toLong()) }
            .filter { it !in busy }
            .mapIndexed { index, month ->
                val day = month.atDay(1).with(TemporalAdjusters.dayOfWeekInMonth(committee.week, committee.weekday))
                Planned(
                    committee = committee,
                    month = month,
                    day = day,
                    index = index,
                    presence = presence(committee, index),
                    decisions = decisions(committee, index),
                    noting = noting(committee, month),
                )
            }.filter { it.day.isBefore(today) && !it.day.isBefore(from) }
    }

    /**
     * Who came and how (design E08 §2.1 M1's cases): at most one voting member missing at a meeting, so every decision has
     * its quorum. BRC: the director sends apologies twice with their alternate standing in, once without one, and is absent
     * without apologies once; the Chairman arrives late twice; the CRO leaves early twice. MCC: the head of finance sends
     * apologies once, the vice-chair attends online once, the CRO arrives late once and leaves early once.
     */
    fun presence(
        committee: Committee,
        index: Int,
    ): Map<Seat, Presence> {
        val varied = (if (committee == Committee.RISK) RISK_YEAR else CREDIT_YEAR)[index]
        return Seat.entries.associateWith { seat -> varied?.takeIf { it.first == seat }?.second ?: Presence.ON_TIME }
    }

    /** The BRC's varied seat by the meeting's place in its history (0: the most recent). */
    private val RISK_YEAR =
        mapOf(
            1 to (Seat.SECOND to Presence.LATE),
            2 to (Seat.THIRD to Presence.APOLOGIES_ALTERNATE),
            3 to (Seat.FOURTH to Presence.LEFT_EARLY),
            5 to (Seat.THIRD to Presence.APOLOGIES),
            6 to (Seat.SECOND to Presence.LATE),
            7 to (Seat.THIRD to Presence.ABSENT),
            8 to (Seat.FOURTH to Presence.LEFT_EARLY),
            9 to (Seat.THIRD to Presence.APOLOGIES_ALTERNATE),
        )

    /** The MCC's varied seat by the meeting's place in its history. */
    private val CREDIT_YEAR =
        mapOf(
            0 to (Seat.FOURTH to Presence.LATE),
            1 to (Seat.THIRD to Presence.APOLOGIES),
            2 to (Seat.SECOND to Presence.ONLINE),
            3 to (Seat.FOURTH to Presence.LEFT_EARLY),
        )

    private fun decisions(
        committee: Committee,
        index: Int,
    ): List<String> {
        val list = if (committee == Committee.RISK) RISK_DECISIONS else CREDIT_DECISIONS
        return listOf(list[(2 * index) % list.size], list[(2 * index + 1) % list.size])
    }

    private fun noting(
        committee: Committee,
        month: YearMonth,
    ): String {
        val name = month.month.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
        return if (committee == Committee.RISK) "Risk dashboard, $name" else "Credit portfolio report, $name"
    }

    private val RISK_DECISIONS =
        listOf(
            "Liquidity stress test results",
            "Interest rate risk limits in the banking book",
            "Operational risk appetite metrics",
            "Large exposure to Karnaphuli Traders",
            "Country risk limits",
            "Recovery plan, annual refresh",
            "Model risk policy",
            "Credit concentration limits by sector",
            "Third-party risk policy",
            "Internal capital adequacy assessment",
            "Climate risk scenario results",
        )

    private val CREDIT_DECISIONS =
        listOf(
            "Term loan, Jamuna Rice Mills",
            "Working capital limit, Teesta Garments",
            "Trade finance line, Surma Agro",
            "Renewal of the overdraft, Buriganga Plastics",
            "Project loan, Dhaleshwari Cement",
            "Restructuring, Kirtonkhola Shipyard",
        )
}
