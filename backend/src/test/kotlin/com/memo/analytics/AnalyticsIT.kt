package com.memo.analytics

import com.memo.analytics.api.AnalyticsNotFoundException
import com.memo.analytics.api.CommitteeAnalytics
import com.memo.common.context.Actor
import com.memo.common.context.ActorContext
import com.memo.common.context.ActorScope
import com.memo.common.tenant.TenantContext
import com.memo.forum.OutcomeKind
import com.memo.meeting.AttendanceStats
import com.memo.minutes.internal.DecisionInput
import com.memo.minutes.internal.DeferTarget
import com.memo.search.DecisionStats
import com.memo.testsupport.Audit
import com.memo.testsupport.aPassword
import com.memo.testsupport.aTestTenant
import com.memo.testsupport.anActiveUser
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import org.springframework.test.web.servlet.assertj.MvcTestResult
import java.io.ByteArrayInputStream
import java.time.LocalDate
import java.time.ZoneId

/**
 * Meeting analytics end to end (ADR-0070; test plan E08 T4 `AnalyticsIT`, `AnalyticsCrossTenantIT`): two held meetings of
 * the Board Risk Committee (the second with a member's apologies), a decision and a deferral on submission items, an
 * overdue action; the overview and the detail for each kind of viewer (an officer, a member's own row and no late counts,
 * `forum.read`, `forums.manage`, nobody's 403), the period's rules, the attendance export (its columns, the formula prefix,
 * its audit entry, members refused, five an hour), a meeting above the viewer's clearance left out, and another tenant.
 */
class AnalyticsIT : AnalyticsFixture() {
    @Autowired lateinit var committeeAnalytics: CommitteeAnalytics

    @Autowired lateinit var attendanceStats: AttendanceStats

    @Autowired lateinit var decisionStats: DecisionStats

    private fun MvcTestResult.committee() = json()["committees"].items().single { it["forumId"].asString() == brc.toString() }

    private fun twoMeetings() {
        val first = meetingWith(listOf("Term loan, Karnaphuli Steel", "Facility renewal, Acme"), listOf(chair, vice, director, colleague))
        decide(first, first.item("Term loan, Karnaphuli Steel").item, APPROVED_WITH_CONDITIONS)
        val target = LocalDate.now(ZoneId.of("Asia/Dhaka")).plusDays(30)
        decide(first, first.item("Facility renewal, Acme").item, DecisionInput(OutcomeKind.DEFERRED, deferTo = DeferTarget(date = target)))
        held(first)
        overdueAction(first.meeting, null, director, daysAgo = 40)
        val second = meetingWith(listOf("Risk appetite review"), listOf(chair, vice, director), apologies = listOf(colleague))
        endMeeting(second.meeting)
    }

    @Test
    fun `the summary and the detail for each kind of viewer`() {
        twoMeetings()

        val officer = analytics(secretary)
        assertThat(officer).hasStatusOk()
        val block = officer.committee()
        assertThat(block["access"].asString()).isEqualTo("FULL")
        assertThat(block["attendance"]["heldMeetings"].asInt()).isEqualTo(2)
        assertThat(block["attendance"]["expected"].asInt() to block["attendance"]["attended"].asInt()).isEqualTo(8 to 7)
        assertThat(block["attendance"]["ratePercent"].asInt()).isEqualTo(88)
        assertThat(block["actions"]["overdue"].asInt() to block["actions"]["overdue31To90"].asInt()).isEqualTo(1 to 1)
        assertThat(block["timeToDecision"]["decisions"].asInt()).isEqualTo(1)
        assertThat(block["timeToDecision"]["medianDays"].asInt()).isZero()
        assertThat(block["timeToDecision"]["enough"].asBoolean()).isFalse()
        assertThat(block["deferrals"]["decisions"].asInt() to block["deferrals"]["deferred"].asInt()).isEqualTo(2 to 1)
        // The items were taken after the papers deadline (the meeting began a minute before): late, and not on time.
        assertThat(block["papers"]["meetings"].asInt()).isEqualTo(2)
        assertThat(block["papers"]["onTime"].asInt()).isZero()
        assertThat(block["papers"]["lateItems"].asInt()).isGreaterThanOrEqualTo(3)

        val full = detail(secretary).json()
        val rows = full["attendance"]["members"].items().associateBy { it["userId"].asString() }
        assertThat(rows.keys).containsExactlyInAnyOrder(*listOf(chair, vice, director, colleague).map { it.id.toString() }.toTypedArray())
        val apologised = rows.getValue(colleague.id.toString())
        assertThat(apologised["expected"].asInt() to apologised["attended"].asInt()).isEqualTo(2 to 1)
        assertThat(apologised["apologies"].asInt() to apologised["absent"].asInt()).isEqualTo(1 to 0)
        assertThat(apologised["meetings"].items().map { it["attendance"].asString() }).containsExactly("IN_PERSON", "APOLOGIES")
        assertThat(full["actions"]["owners"].items().single()["ownerUserId"].asString()).isEqualTo(director.id.toString())
        assertThat(full["papers"]["meetings"].items()).hasSize(2)
        assertThat(full["deferrals"]["byQuarter"].items()).hasSize(1)

        // A member: the committee's totals, their own row, no late counts, no export.
        val member = analytics(director).committee()
        assertThat(member["access"].asString()).isEqualTo("MEMBER")
        assertThat(member["attendance"]["attended"].asInt()).isEqualTo(7)
        assertThat(member["papers"].absent("lateItems")).isTrue()
        val own = detail(director).json()
        assertThat(own["attendance"]["ownRowOnly"].asBoolean()).isTrue()
        assertThat(own["attendance"]["members"].items().map { it["userId"].asString() }).containsExactly(director.id.toString())
        assertThat(own["papers"]["meetings"].items().map { it.absent("lateItems") }).containsOnly(true)
        assertThat(export(director)).hasStatus(HttpStatus.FORBIDDEN)

        // Oversight sees every committee in full; the vice-chair is an officer; someone outside has no analytics.
        assertThat(analytics(reader).committee()["access"].asString()).isEqualTo("FULL")
        assertThat(detail(vice).json()["attendance"]["members"].items()).hasSize(4)
        assertThat(analytics(outsider)).hasStatus(HttpStatus.FORBIDDEN)
        assertThat(detail(outsider)).hasStatus(HttpStatus.FORBIDDEN)
        assertThat(read("/api/v1/analytics/committees/${otherCommittee()}", director.session)).hasStatus(HttpStatus.NOT_FOUND)
        assertThat(analytics(director, "?forumIds=${java.util.UUID.randomUUID()}")).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
    }

    @Test
    fun `periods default to twelve months, refuse more than ten years, and leave member rows out above five`() {
        val default = analytics(secretary).json()
        // Today in the tenant's zone: the default period ends there.
        val today = LocalDate.parse(default["to"].asString())
        assertThat(LocalDate.parse(default["from"].asString())).isEqualTo(today.minusMonths(12).plusDays(1))
        val tooLong = analytics(secretary, "?from=${today.minusYears(10)}&to=$today")
        assertThat(tooLong).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
        assertThat(tooLong.errors()).containsExactly("from:RANGE")
        assertThat(analytics(secretary, "?from=$today&to=${today.minusDays(1)}")).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
        val sixYears = detail(secretary, "?from=${today.minusYears(6)}&to=$today").json()
        assertThat(sixYears["attendance"]["membersOmitted"].asBoolean()).isTrue()
        assertThat(export(secretary, "?from=${today.minusYears(6)}&to=$today")).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
    }

    @Test
    fun `the attendance export holds the table for the annual report, string cells prefixed, audited`() {
        val first = meetingWith(listOf("Term loan, Karnaphuli Steel"), listOf(chair, vice, director))
        endMeeting(first.meeting)
        transactions.inTenant(tenant.id) {
            tenantDsl.current().execute("update identity_user set display_name = ? where id = ?", "=HYPERLINK(\"x\")", colleague.id)
        }

        val file = export(reader)
        assertThat(file).hasStatusOk()
        assertThat(file.response.getHeader("Content-Disposition")).contains("attachment", "attendance.xlsx")
        val rows =
            XSSFWorkbook(ByteArrayInputStream(file.response.contentAsByteArray)).use { workbook ->
                workbook.getSheetAt(0).map { row -> row.map { it.stringCellValue } }
            }
        assertThat(rows.first()).containsExactly("Committee", "Board Risk Committee")
        assertThat(rows.flatten()).contains("Meetings held", "Represented by alternate")
        val colleagueRow = rows.single { it.firstOrNull()?.contains("HYPERLINK") == true }
        assertThat(colleagueRow.first()).startsWith("'=")
        assertThat(colleagueRow.drop(3).take(2)).containsExactly("1", "0")
        val audited = Audit.assertAudited(tenant.id, "analytics.attendance.exported", brc.toString(), reader.id.toString())
        assertThat(audited.canonical).contains("rows")
    }

    @Test
    fun `five exports an hour, counted after the permission check`() {
        repeat(3) { assertThat(export(director)).hasStatus(HttpStatus.FORBIDDEN) }
        repeat(5) { assertThat(export(secretary)).hasStatusOk() }
        assertThat(export(secretary)).hasStatus(HttpStatus.TOO_MANY_REQUESTS)
    }

    @Test
    fun `a held meeting above the viewer's clearance is left out of every measure`() {
        val first = meetingWith(listOf("Term loan, Karnaphuli Steel"), listOf(chair, vice, director, colleague))
        endMeeting(first.meeting)
        val second = meetingWith(listOf("Facility renewal, Acme"), listOf(chair, vice, director, colleague))
        endMeeting(second.meeting)
        transactions.inTenant(tenant.id) {
            tenantDsl.current().execute("update meeting set classification_id = ? where id = ?", levels()[1].id, second.meeting.id)
        }
        clear(secretary.id, 1)
        assertThat(analytics(secretary).committee()["attendance"]["heldMeetings"].asInt()).isEqualTo(2)
        assertThat(analytics(reader).committee()["attendance"]["heldMeetings"].asInt()).isEqualTo(1)
        assertThat(analytics(reader).committee()["papers"]["meetings"].asInt()).isEqualTo(1)
    }

    @Test
    fun `another tenant's people never read this tenant's committees or counts`() {
        twoMeetings()
        val other = registry.aTestTenant(transactions, settings, "analyticsother", mfaRequired = false)
        val otherAdmin = accounts.anActiveUser(transactions, other, aPassword(), tenantAdmin = true)
        val asOther = { block: () -> Any? ->
            ActorContext.call(ActorScope(Actor.User(otherAdmin.id.value), null)) {
                TenantContext.call(other.id) { transactions.readInTenant(other.id, block) }
            }
        }
        assertThatThrownBy { asOther { committeeAnalytics.detail(brc, null, null) } }.isInstanceOf(AnalyticsNotFoundException::class.java)
        val today = LocalDate.now(ZoneId.of("Asia/Dhaka"))
        assertThat(asOther { attendanceStats.held(setOf(brc), today.minusDays(7), today, Int.MAX_VALUE) } as List<*>).isEmpty()
        assertThat(asOther { decisionStats.of(setOf(brc), today.minusDays(7), today) } as List<*>).isEmpty()
        val summary = asOther { committeeAnalytics.summary(null, null, emptySet()) } as com.memo.analytics.api.AnalyticsSummaryResponse
        assertThat(summary.committees.map { it.forumId }).doesNotContain(brc)
    }
}
