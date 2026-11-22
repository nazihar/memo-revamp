package com.memo.actions

import com.memo.actions.internal.ActionReportReads
import com.memo.actions.internal.ActionReportResponse
import com.memo.actions.internal.ActionReportSection
import com.memo.actions.internal.ActionReports
import com.memo.actions.internal.ReportHoldRequest
import com.memo.forum.StandingKind
import com.memo.identity.UserAccount
import com.memo.meeting.internal.AgendaOrigin
import com.memo.meeting.internal.ItemInput
import com.memo.meeting.internal.ItemKind
import com.memo.meeting.internal.MeetingRow
import com.memo.meeting.internal.MemberAgendas
import com.memo.testsupport.Audit
import com.memo.testsupport.aPassword
import com.memo.testsupport.anActiveUser
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/**
 * The action taken report under "Matters arising" (test plan E07 T7 `ActionReportIT`; ADR-0067 C1, design E07 §1.12):
 * built when a revision of the next meeting's agenda with a MATTERS_ARISING item is published (not without one), with
 * its sections in order; frozen (a later revision gets its own report, the first stays); "since the last meeting" from
 * the previous meeting's start; held-back actions absent and counted for officers; at most 500 rows, the rest counted;
 * each reader's rows and counts by the one rule (a recused member none of the item's, unless it is theirs), on screen,
 * as a PDF and on the item's link; its content hash audited, every open audited.
 */
class ActionReportIT : ActionFixture() {
    override fun label() = "actreport"

    @Autowired lateinit var reportReads: ActionReportReads

    @Autowired lateinit var reportHolds: com.memo.actions.internal.ActionReportHolds

    @Autowired lateinit var memberAgendas: MemberAgendas

    @Autowired lateinit var guestsOf: com.memo.meeting.internal.MeetingGuests

    /** The next BRC meeting, [days] ahead, its agenda from the template ("Matters arising and actions"), scheduled. */
    private fun nextMeeting(days: Long = 14): MeetingRow {
        val created = asUser(secretary) { lifecycle.create(brc, place(), times(today.plusDays(days))) }
        asUser(secretary) { agendaStart.start(created.id, AgendaOrigin.TEMPLATE, null) }
        return asUser(secretary) { meeting(created.id).let { lifecycle.schedule(it.id, it.version) } }
    }

    /** Publishes [meeting]'s draft (the chair agrees first); the revision's number. */
    private fun publish(meeting: MeetingRow): Int {
        if (asUser(secretary) { drafts.draft(meeting.id) }.currentRevision == 0) {
            asUser(chair) { chairing.agree(meeting.id, drafts.draft(meeting.id).version, false, null) }
        }
        val note = "Revision with the latest papers".takeIf { asUser(secretary) { drafts.draft(meeting.id) }.currentRevision > 0 }
        return asUser(secretary) { publisher.publish(meeting.id, drafts.draft(meeting.id).version, note, true) }
    }

    private fun addItem(
        meeting: MeetingRow,
        title: String,
    ) = asUser(secretary) {
        val section =
            drafts
                .draft(meeting.id)
                .sections
                .single { it.title == "For decision" }
                .id
        agendaItems.add(
            meeting.id,
            drafts.draft(meeting.id).version,
            ItemInput(ItemKind.ITEM, sectionId = section, title = title, minutes = 10),
        )
    }

    private fun mattersArising(meeting: MeetingRow): UUID =
        asUser(secretary) { drafts.draft(meeting.id) }
            .sections
            .flatMap { it.items }
            .single { it.standingKind == StandingKind.MATTERS_ARISING }
            .id

    private fun report(
        by: UserAccount,
        meeting: MeetingRow,
        revision: Int? = null,
    ): ActionReportResponse = asUser(by) { reportReads.report(meeting.id, revision) }

    private fun ActionReportResponse.references(section: ActionReportSection): List<String> =
        sections.single { it.section == section }.rows.map { it.reference }

    private fun ActionReportResponse.counts(): List<Int> = sections.map { it.count }

    @Test
    fun `the next meeting's report lists those to close, overdue, open, then closed and cancelled since the last meeting`() {
        val d = deciding()
        val issue = { issued(deciding = d) }
        val toClose = List(2) { issue() }
        val overdue = List(3) { issue() }
        val open = List(9) { issue() }
        val closed = List(4) { issue() }
        val closedBefore = issue()
        val cancelled = issue()
        // One marked done for real (its note is the latest update), one through the row; dates and endings through the row.
        asUser(director2) { closure.done(toClose[0].id, row(toClose[0].id).version, "Plan revised and approved by ALCO", emptyList()) }
        alter(toClose[1].id, "status = 'DONE_PENDING', done_at = now() + interval '1 hour'")
        overdue.forEachIndexed { i, a -> alter(a.id, "due_date = current_date - ${3 - i}") }
        open.forEachIndexed { i, a -> alter(a.id, "due_date = current_date + ${20 - i}") }
        closed.forEach { alter(it.id, "status = 'CLOSED', closed_at = now(), step_name = null") }
        alter(closedBefore.id, "status = 'CLOSED', closed_at = now() - interval '2 days', step_name = null")
        alter(
            cancelled.id,
            "status = 'CANCELLED', cancel_kind = 'RISK_ACCEPTED', cancel_reason = 'Superseded by the new risk appetite', " +
                "closed_at = now(), step_name = null",
        )

        val next = nextMeeting()
        assertThat(publish(next)).isEqualTo(1)
        val report = report(secretary, next)

        assertThat(report.sections.map { it.section }).containsExactlyElementsOf(ActionReportSection.SHOWN)
        assertThat(report.counts()).containsExactly(2, 3, 9, 4, 1, 0)
        assertThat(report.references(ActionReportSection.TO_CLOSE)).containsExactlyElementsOf(toClose.map { it.reference })
        assertThat(report.references(ActionReportSection.OVERDUE)).containsExactlyElementsOf(overdue.map { it.reference })
        // Open: due date first (the last raised is due first).
        assertThat(report.references(ActionReportSection.OPEN)).containsExactlyElementsOf(open.reversed().map { it.reference })
        assertThat(report.references(ActionReportSection.CLOSED)).containsExactlyInAnyOrderElementsOf(closed.map { it.reference })
        assertThat(report.references(ActionReportSection.CLOSED)).doesNotContain(closedBefore.reference)
        val cancelledRow =
            report.sections
                .single { it.section == ActionReportSection.CANCELLED }
                .rows
                .single()
        assertThat(cancelledRow.cancelKind?.name).isEqualTo("RISK_ACCEPTED")
        assertThat(cancelledRow.cancelReason).isEqualTo("Superseded by the new risk appetite")
        val done =
            report.sections
                .first()
                .rows
                .first()
        assertThat(done.latestUpdate).isEqualTo("Plan revised and approved by ALCO")
        assertThat(done.title).isEqualTo(toClose[0].title)
        assertThat(done.sourceLabel).isEqualTo(toClose[0].sourceLabel)
        assertThat(report.previousMeetingLabel).isEqualTo(d.meeting.label)
        assertThat(report.since).isEqualTo(meeting(d.meeting.id).startsAt)
        assertThat(report.more).isZero()
        assertThat(report.heldBack).isZero()
        assertThat(report.summary)
            .isEqualTo(
                "Action taken report: 2 to close, 3 overdue, 9 open, 4 closed since the last meeting, 1 cancelled since the last meeting",
            )

        linkedAndAudited(next, report, toClose[0].title)
    }

    /** The "Matters arising" item links to [report] with the reader's counts; built and opened are audited, without [title]. */
    private fun linkedAndAudited(
        next: MeetingRow,
        report: ActionReportResponse,
        title: String,
    ) {
        // The "Matters arising" item links to it, with the reader's counts.
        val link =
            asUser(director) { memberAgendas.agenda(next.id, null) }
                .sections
                .flatMap { it.items }
                .single { it.standingKind == StandingKind.MATTERS_ARISING }
                .links
                .single()
        assertThat(link.kind).isEqualTo("ACTION_REPORT")
        assertThat(link.label).isEqualTo(report.summary)
        assertThat(link.at).isEqualTo(report.builtAt)

        // Built and opened are audited; the built entry carries the content's hash, never a title.
        val built = Audit.assertAudited(tenant.id, "actions.report.built", report.reportId.toString())
        assertThat(built.canonical).contains("contentSha256").doesNotContain(title)
        Audit.assertAudited(tenant.id, "actions.report.opened", report.reportId.toString(), secretary.id.value.toString())
    }

    @Test
    fun `a later revision gets its own report and the first is unchanged`() {
        val d = deciding()
        val first = issued(deciding = d)
        val second = issued(deciding = d)
        val next = nextMeeting()
        publish(next)
        val r1 = report(secretary, next)
        assertThat(r1.references(ActionReportSection.OPEN)).containsExactlyInAnyOrder(first.reference, second.reference)

        // After the first report: one closed, and one raised.
        alter(first.id, "status = 'CLOSED', closed_at = now(), step_name = null")
        val third = issued(deciding = d)
        addItem(next, "Liquidity report")
        assertThat(publish(next)).isEqualTo(2)

        val r2 = report(secretary, next)
        assertThat(r2.revision).isEqualTo(2)
        assertThat(r2.revisions).containsExactly(1, 2)
        assertThat(r2.references(ActionReportSection.OPEN)).containsExactlyInAnyOrder(second.reference, third.reference)
        assertThat(r2.references(ActionReportSection.CLOSED)).containsExactly(first.reference)
        val again = report(secretary, next, revision = 1)
        assertThat(again.reportId).isEqualTo(r1.reportId)
        assertThat(again.sections).isEqualTo(r1.sections)
        assertThat(again.builtAt).isEqualTo(r1.builtAt)
        assertThat(outcome(secretary) { reportReads.report(next.id, 3) }).isEqualTo("404")
    }

    @Test
    fun `a revision without a matters arising item has no report`() {
        issued(deciding = deciding())
        val next = nextMeeting()
        val item = mattersArising(next)
        asUser(secretary) { agendaItems.remove(next.id, item, drafts.draft(next.id).version) }
        publish(next)
        assertThat(outcome(secretary) { reportReads.report(next.id, null) }).isEqualTo("404")
        assertThat(
            transactions.readInTenant(tenant.id) {
                actionsDsl.current().fetchCount(
                    com.memo.jooq.tables.references.ACTIONS_REPORT,
                    com.memo.jooq.tables.references.ACTIONS_REPORT.MEETING_ID
                        .eq(next.id),
                )
            },
        ).isZero()
    }

    @Test
    fun `an action held back is absent and counted for officers only`() {
        val d = deciding()
        val held = issued(deciding = d)
        val kept = issued(deciding = d)
        // No meeting to come yet: nothing to hold it back from.
        assertThat(outcome(secretary) { reportHolds.hold(held.id, ReportHoldRequest("Discussed in camera")) }).isEqualTo("409 NO_MEETING")
        val next = nextMeeting()
        assertThat(outcome(secretary) { reportHolds.hold(held.id, ReportHoldRequest(" ")) }).isEqualTo("422 REQUIRED")
        assertThat(outcome(director) { reportHolds.hold(held.id, ReportHoldRequest("Discussed in camera")) }).startsWith("403")
        val answer = asUser(secretary) { reportHolds.hold(held.id, ReportHoldRequest("Discussed in camera")) }
        assertThat(answer.meetingId).isEqualTo(next.id)
        assertThat(outcome(secretary) { reportHolds.hold(held.id, ReportHoldRequest("Again")) }).isEqualTo("409 ALREADY_HELD")
        assertThat(timeline(held.id).first()).isEqualTo("HELD_BACK")
        val audited = Audit.assertAudited(tenant.id, "actions.report.held", held.id.toString(), secretary.id.value.toString())
        assertThat(audited.canonical).doesNotContain("Discussed in camera").contains("reasonSha256")

        publish(next)
        val officers = report(secretary, next)
        assertThat(officers.references(ActionReportSection.OPEN)).containsExactly(kept.reference)
        assertThat(officers.heldBack).isEqualTo(1)
        val member = report(director, next)
        assertThat(member.references(ActionReportSection.OPEN)).containsExactly(kept.reference)
        assertThat(member.heldBack).isNull()
        assertThat(member.counts().sum()).isEqualTo(1)
    }

    @Test
    fun `a recused member sees no row of the item, nor counts it, on screen, in the PDF or on the link, but their own`() {
        val d = deciding { m, items -> declareOn(m, items.getValue(com.memo.minutes.DecisionFixture.CREDIT), director) }
        val ofCredit = issued(deciding = d, item = d.credit)
        val theirs = issued(deciding = d, item = d.credit, owner = person(director))
        val ofAppetite = issued(deciding = d, item = d.appetite)
        val next = nextMeeting()
        publish(next)

        assertThat(report(secretary, next).references(ActionReportSection.OPEN))
            .containsExactlyInAnyOrder(ofCredit.reference, theirs.reference, ofAppetite.reference)
        val recused = report(director, next)
        assertThat(recused.references(ActionReportSection.OPEN)).containsExactlyInAnyOrder(theirs.reference, ofAppetite.reference)
        assertThat(recused.counts().sum()).isEqualTo(2)
        assertThat(recused.summary).isEqualTo("Action taken report: 2 open")

        val pdf = asUser(director) { reportReads.pdf(next.id, null) }
        val text = Loader.loadPDF(pdf).use { PDFTextStripper().getText(it) }
        assertThat(text).contains(checkNotNull(ofAppetite.reference)).contains("Action taken report")
        assertThat(text).doesNotContain(checkNotNull(ofCredit.reference))
        Audit.assertAudited(tenant.id, "actions.report.opened", recused.reportId.toString(), director.id.value.toString())
        val link =
            asUser(director) { memberAgendas.agenda(next.id, null) }
                .sections
                .flatMap { it.items }
                .single { it.standingKind == StandingKind.MATTERS_ARISING }
                .links
                .single()
        assertThat(link.label).isEqualTo("Action taken report: 2 open")
    }

    @Test
    fun `only the committee's officers, members today and forum readers read it`() {
        issued(deciding = deciding())
        val next = nextMeeting()
        val guest = accounts.anActiveUser(transactions, tenant, aPassword())
        asUser(secretary) { guestsOf.add(next.id, guest.id.value, "Adviser") }
        publish(next)
        assertThat(report(director, next).counts().sum()).isEqualTo(1)
        assertThat(outcome(reader) { reportReads.report(next.id, null) }).isEqualTo("OK")
        assertThat(outcome(user) { reportReads.report(next.id, null) }).isEqualTo("404")
        assertThat(outcome(guest) { reportReads.report(next.id, null) }).isEqualTo("404")
        assertThat(outcome(user) { reportReads.pdf(next.id, null) }).isEqualTo("404")
    }

    @Test
    fun `at most 500 rows are shown, and the rest are counted`() {
        val level = levels().first().id
        transactions.inTenant(tenant.id) {
            actionsDsl.current().execute(
                """
                insert into actions_item (id, tenant_id, forum_id, number_year, sequence, reference, source, source_label, title,
                    classification_id, owner_kind, owner_user_id, holder_user_id, due_date, original_due_date, status, created_by,
                    created_at, issued_by, issued_at)
                select gen_random_uuid(), ?, ?, 2099, g, 'BRC A' || g || '/2099', 'DIRECT', 'Board Risk Committee', 'Bulk action ' || g,
                    ?, 'PERSON', ?, ?, current_date + 30 + g, current_date + 30 + g, 'OPEN', ?, now(), ?, now()
                from generate_series(1, 505) g
                """.trimIndent(),
                tenant.id.value,
                brc,
                level,
                director2.id.value,
                director2.id.value,
                secretary.id.value,
                secretary.id.value,
            )
        }
        val next = nextMeeting()
        publish(next)
        val report = report(secretary, next)
        assertThat(report.sections.sumOf { it.rows.size }).isEqualTo(ActionReports.ROWS_MAX)
        assertThat(report.more).isEqualTo(5)
        // The latest-due open actions are left out first.
        assertThat(report.references(ActionReportSection.OPEN).last()).isEqualTo("BRC A500/2099")
    }
}
