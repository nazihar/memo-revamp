package com.memo.actions

import com.memo.actions.internal.ActionTracker
import com.memo.actions.internal.ActionTrackerController
import com.memo.actions.internal.RagColour
import com.memo.actions.internal.TrackerFilter
import com.memo.common.authz.ForbiddenException
import com.memo.common.ratelimit.RateLimitedException
import com.memo.identity.UserAccount
import com.memo.minutes.DecisionFixture
import com.memo.testsupport.Audit
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.mock.web.MockHttpServletResponse
import java.io.ByteArrayInputStream
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * The tracker's export (test plan E07 T8 `TrackerExportIT`; design E07 §3, §5; flow AN): the workbook opens and holds the
 * rows of the filtered tracker in its order, every value a string cell, a title beginning with `=` written `'=…`; a
 * director recused from an item exports none of its rows; members and owners are 403 and strangers 404 before any count;
 * each export is audited with the filter (the text as its hash) and the count; ten an hour for one person.
 */
class TrackerExportIT : ActionFixture() {
    override fun label() = "actexport"

    @Autowired lateinit var controller: ActionTrackerController

    @Autowired lateinit var tracker: ActionTracker

    private fun export(
        by: UserAccount,
        rag: RagColour? = null,
        text: String? = null,
    ): MockHttpServletResponse {
        val response = MockHttpServletResponse()
        signedIn(by) { controller.export(brc, null, rag, null, null, null, null, null, null, null, text, null, response) }
        return response
    }

    /** The workbook's cells as text, row by row. */
    private fun cells(response: MockHttpServletResponse): List<List<String>> =
        XSSFWorkbook(ByteArrayInputStream(response.contentAsByteArray)).use { workbook ->
            val sheet = workbook.getSheetAt(0)
            (0..sheet.lastRowNum).map { r ->
                val row = sheet.getRow(r)
                (0 until row.lastCellNum).map { c -> row.getCell(c)?.stringCellValue.orEmpty() }
            }
        }

    @Test
    fun `the workbook holds the filtered rows in order, formula-safe, audited, and only for those who may export`() {
        val d = deciding { m, items -> declareOn(m, items.getValue(DecisionFixture.CREDIT), director) }
        val formula = issue(raise(d.meeting.id, d.appetite, fields(title = "=HYPERLINK(\"https://example.test\",\"open\")"))).id
        val credit = issue(raise(d.meeting.id, d.credit, fields(title = "Report the revised collateral valuation"))).id
        val late =
            issue(
                raise(d.meeting.id, d.appetite, fields(title = "Revise the liquidity contingency plan", owner = person(director2))),
            ).id
        alter(late, "due_date = ?", LocalDate.now(ZoneOffset.UTC).minusDays(5))

        val exported = export(secretary)
        assertThat(exported.status).isEqualTo(200)
        assertThat(exported.contentType).isEqualTo("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
        assertThat(exported.getHeader("Content-Disposition")).contains("attachment").contains("actions.xlsx")
        assertThat(exported.getHeader("Cache-Control")).isEqualTo("no-store")
        val sheet = cells(exported)
        assertThat(sheet.first()).startsWith("Committee", "Reference", "Action", "Source", "Owner", "Held by", "Due")
        // The rows of the screen, in its order (by due date): the late one first.
        val screen = asUser(secretary) { tracker.page(brc, TrackerFilter(), null, 50) }.items
        assertThat(sheet.drop(1).map { it[1] }).containsExactlyElementsOf(screen.map { it.reference })
        assertThat(sheet[1].slice(listOf(0, 4, 10, 11))).containsExactly("Board Risk Committee", director2Name(), "RED", "OVERDUE")
        // The title beginning with `=` is a string starting with the injection prefix, never a formula.
        assertThat(sheet.drop(1).map { it[2] }).contains("'=HYPERLINK(\"https://example.test\",\"open\")")
        val audited = Audit.assertAudited(tenant.id, "actions.tracker.exported", brc.toString(), secretary.id.toString())
        assertThat(audited.canonical).contains("\"count\":3", "\"truncated\":false")
        assertThat(row(formula).reference).isNotNull()

        // Filtered by colour and text: the rows of that tracker; the text is audited as its hash only.
        val red = export(chair, rag = RagColour.RED, text = "contingency plans")
        assertThat(cells(red).drop(1).map { it[2] }).containsExactly("Revise the liquidity contingency plan")
        val filtered = Audit.assertAudited(tenant.id, "actions.tracker.exported", brc.toString(), chair.id.toString())
        assertThat(filtered.canonical).contains("\"count\":1", "\"rag\":\"RED\"", "textSha256").doesNotContain("contingency")

        // forum.read exports; the recused director is a member (403), and their rows would not hold the credit item's.
        assertThat(cells(export(reader)).drop(1)).hasSize(3)
        assertThat(runCatching { export(director) }.exceptionOrNull()).isInstanceOf(ForbiddenException::class.java)
        assertThat(runCatching { export(director2) }.exceptionOrNull()).isInstanceOf(ForbiddenException::class.java)
        assertThat(runCatching { export(treasurer) }.exceptionOrNull()).isInstanceOf(ForbiddenException::class.java)
        assertThat(runCatching { export(user) }.exceptionOrNull()?.javaClass?.simpleName).isEqualTo("ActionNotFoundException")
        assertThat(asUser(director) { tracker.page(brc, TrackerFilter(), null, 50) }.items.map { it.id }).doesNotContain(credit)
    }

    @Test
    fun `ten exports an hour for one person, and a refused export counts nothing`() {
        val d = deciding()
        issue(raise(d.meeting.id, d.appetite, fields()))
        // A refused filter (422) and a refused person (403) spend nothing.
        repeat(3) {
            assertThat(runCatching { export(chair, text = "x".repeat(201)) }.exceptionOrNull()?.javaClass?.simpleName)
                .isEqualTo("ActionRejectedException")
            assertThat(runCatching { export(director) }.exceptionOrNull()).isInstanceOf(ForbiddenException::class.java)
        }
        repeat(10) { assertThat(export(chair).status).isEqualTo(200) }
        assertThat(runCatching { export(chair) }.exceptionOrNull()).isInstanceOf(RateLimitedException::class.java)
        // Another person has their own allowance.
        assertThat(export(secretary).status).isEqualTo(200)
    }

    private fun director2Name(): String = asUser(admin) { users.names(setOf(director2.id.value)) }.getValue(director2.id.value)

    @Autowired lateinit var users: com.memo.identity.UserSummaries
}
