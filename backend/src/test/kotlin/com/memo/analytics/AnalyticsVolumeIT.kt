package com.memo.analytics

import com.memo.analytics.api.CommitteeAnalytics
import com.memo.analytics.export.AttendanceWorkbook
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.beans.factory.annotation.Autowired
import java.io.OutputStream
import java.util.UUID

/**
 * Meeting analytics at the design's volume (test plan E08 T5 `AnalyticsVolumeIT`, design E08 §8): the committee volume
 * ([CommitteeVolumeSupport]) at `COMMITTEE_VOLUME` meetings (the design's 3,600: in the last 12 months 360 meetings of 30
 * committees with about 14,000 attendance lines, 5,400 decisions and 3,400 open actions; one committee over five years 60
 * meetings, 2,400 lines, 900 decisions, 500 actions; the weekly `load` stage sets it, and the test is skipped without
 * it), measured through the service the endpoints call: the overview for the five reader profiles and the secretary of
 * all 30 committees, a committee's detail over one and five years, and the attendance export over five years. Every
 * statement is `EXPLAIN ANALYZE`d: none may scan a large table sequentially (the decision statistics' own statement is
 * [com.memo.search.RegisterVolumeIT]'s). Budgets have 2x headroom for shared CI runners; the p95 values are printed.
 */
@EnabledIfEnvironmentVariable(named = CommitteeVolumeSupport.VOLUME_VARIABLE, matches = "[1-9][0-9]*")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class AnalyticsVolumeIT : CommitteeVolumeSupport() {
    @Autowired lateinit var analytics: CommitteeAnalytics

    @Autowired lateinit var workbook: AttendanceWorkbook

    @BeforeAll
    fun generate() {
        generateCommittees("analyticsvol")
    }

    @Test
    @Order(1)
    fun `the attendance histories give the design's rates`() {
        val summary = asUser(people.secretary) { analytics.summary(null, null, emptySet()) }
        assertThat(summary.committees).hasSize(FORUMS)
        summary.committees.forEach {
            println(
                "$label ${it.code}: ${it.attendance.heldMeetings} held, attended ${it.attendance.attended} of ${it.attendance.expected} " +
                    "(${it.attendance.ratePercent} %), ${it.actions.overdue} overdue, ${it.timeToDecision.decisions} decisions timed, " +
                    "${it.deferrals} deferrals",
            )
        }
        // About 90 % over the committees (members attend 85–95 %); one committee's year of 12 meetings varies around it.
        val others = summary.committees.drop(1)
        val overall = others.sumOf { it.attendance.attended } * PERCENT / others.sumOf { it.attendance.expected }
        assertThat(overall).isBetween(MIN_RATE, MAX_RATE)
        assertThat(others.map { it.attendance.ratePercent }).allSatisfy { assertThat(it).isBetween(MIN_ONE_RATE, MAX_ONE_RATE) }
        assertThat(summary.committees.sumOf { it.attendance.heldMeetings }).isGreaterThan(FORUMS * MIN_HELD_A_YEAR)
        val detail = asUser(people.secretary) { analytics.detail(committees[1].first, null, null) }
        assertThat(detail.attendance.members.sumOf { it.byAlternate }).describedAs("represented by alternates").isPositive()
        assertThat(detail.attendance.members.sumOf { it.joinedLate }).describedAs("joined late").isPositive()
        assertThat(detail.attendance.members.sumOf { it.leftEarly }).describedAs("left early").isPositive()
        assertThat(detail.attendance.members.sumOf { it.absent }).describedAs("absent without apologies").isPositive()
    }

    @Test
    @Order(2)
    fun `the overview answers within budget for every profile`() {
        viewers().forEach { (name, user) ->
            val committees = asUser(user) { analytics.summary(null, null, emptySet()) }.committees.size
            measure("overview, last 12 months ($name: $committees committees)", SUMMARY_BUDGET_MS, user) {
                analytics.summary(null, null, emptySet())
            }
        }
    }

    @Test
    @Order(3)
    fun `a committee's detail answers within budget over one and five years`() {
        val forum = committees.first().first
        val today = asUser(people.admin) { org.today() }
        val from = today.minusYears(DETAIL_YEARS).plusDays(1)
        val detail = asUser(people.secretary) { analytics.detail(forum, from, today) }
        println(
            "$label detail of ${committees.first().second} over $DETAIL_YEARS years: ${detail.attendance.summary.heldMeetings} held, " +
                "${detail.attendance.members.size} seat holders, ${detail.timeToDecision.overall.decisions} decisions timed, " +
                "${detail.actions.owners.size} owners overdue",
        )
        val readers =
            listOf("secretary of 30" to people.secretary, MANAGER to profiles.managers.first(), MEMBER to profiles.members.first())
        readers.forEach { (name, user) ->
            measure("detail, last 12 months ($name)", DETAIL_BUDGET_MS, user) { analytics.detail(forum, null, null) }
            measure("detail, $DETAIL_YEARS years ($name)", DETAIL_BUDGET_MS, user) { analytics.detail(forum, from, today) }
        }
    }

    @Test
    @Order(4)
    fun `the attendance export answers within budget over five years`() {
        val forum = committees.first().first
        val today = asUser(people.admin) { org.today() }
        val from = today.minusYears(DETAIL_YEARS).plusDays(1)
        val rows = asUser(people.secretary, write = true) { analytics.export(forum, from, today) }.rows.size
        assertThat(rows).isGreaterThan(MIN_SEATS)
        val name = "attendance export ($rows seat holders, $DETAIL_YEARS years, with the workbook)"
        measure(name, EXPORT_BUDGET_MS, people.secretary, write = true) {
            workbook.write(analytics.export(forum, from, today), OutputStream.nullOutputStream())
        }
    }

    /** The five reader profiles' first people, and the secretary of every committee. */
    private fun viewers(): List<Pair<String, UUID>> =
        profiles.byName().map { (name, group) -> name to group.first() } + ("secretary of 30" to people.secretary)

    private companion object {
        const val MIN_RATE = 85
        const val MAX_RATE = 95
        const val MIN_ONE_RATE = 75
        const val MAX_ONE_RATE = 100
        const val PERCENT = 100
        const val MIN_HELD_A_YEAR = 10
        const val MIN_SEATS = 30
        const val DETAIL_YEARS = 5L

        // Design E08 §8.
        const val SUMMARY_BUDGET_MS = 800L
        const val DETAIL_BUDGET_MS = 500L
        const val EXPORT_BUDGET_MS = 3_000L
    }
}
