package com.memo.actions

import com.memo.actions.internal.ActionReportReads
import com.memo.actions.internal.ActionReportResponse
import com.memo.actions.internal.ActionReportSection
import com.memo.actions.internal.ActionSuggestedDue
import com.memo.actions.internal.ActionTracker
import com.memo.actions.internal.ActionUpdateRequests
import com.memo.actions.internal.CommitteeRagKey
import com.memo.actions.internal.ReportHoldRequest
import com.memo.actions.internal.TrackerFilter
import com.memo.forum.ActionsPolicy
import com.memo.forum.DefaultDue
import com.memo.forum.StandingKind
import com.memo.identity.UserAccount
import com.memo.meeting.internal.AgendaOrigin
import com.memo.meeting.internal.MeetingRow
import com.memo.meeting.internal.MemberAgendas
import com.memo.memo.MemoDraftFields
import com.memo.memo.MemoDrafts
import com.memo.memo.internal.MemoTypes
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.LocalDate
import java.util.UUID

/**
 * What the screens need to say things themselves (E07 follow-up to T7, T10 and T11; design E07 §1.5, §1.9, §1.12, §1.13):
 * the action's `evidenceRequired`, `overdueWorkingDays` and `can.holdBack`; the key's numbers on "My actions" and on the
 * report; the tracker's `can.askForUpdates`; the committee type's suggested due date; the report link's counts; and, on
 * the report, each row's follow-up memos (live, by the memo's own read rule, never in the snapshot) and "Close the
 * actions the committee agreed" by the verifier's rule.
 */
class ActionReadingAidsIT : ActionFixture() {
    override fun label() = "actaids"

    @Autowired lateinit var reportReads: ActionReportReads

    @Autowired lateinit var reportHolds: com.memo.actions.internal.ActionReportHolds

    @Autowired lateinit var memberAgendas: MemberAgendas

    @Autowired lateinit var tracker: ActionTracker

    @Autowired lateinit var updateRequests: ActionUpdateRequests

    @Autowired lateinit var suggestedDue: ActionSuggestedDue

    @Autowired lateinit var memoDrafts: MemoDrafts

    @Autowired lateinit var memoTypes: MemoTypes

    @Autowired lateinit var orgDirectory: com.memo.org.OrgDirectory

    /** The tenant keeps its meetings' zone (UTC+6), so its day is not UTC's from 18:00 UTC (#247). */
    @BeforeEach
    fun tenantZone() {
        transactions.inTenant(tenant.id) { settings.put(com.memo.org.internal.OrgCalendar.TIME_ZONE, "Asia/Dhaka") }
    }

    private fun tenantToday(): LocalDate = transactions.readInTenant(tenant.id) { orgDirectory.today() }

    /** The next BRC meeting, [days] ahead, its agenda from the template ("Matters arising and actions"), scheduled. */
    private fun nextMeeting(days: Long = 14): MeetingRow {
        val created = asUser(secretary) { lifecycle.create(brc, place(), times(today.plusDays(days))) }
        asUser(secretary) { agendaStart.start(created.id, AgendaOrigin.TEMPLATE, null) }
        return asUser(secretary) { meeting(created.id).let { lifecycle.schedule(it.id, it.version) } }
    }

    private fun publish(meeting: MeetingRow) {
        asUser(chair) { chairing.agree(meeting.id, drafts.draft(meeting.id).version, false, null) }
        asUser(secretary) { publisher.publish(meeting.id, drafts.draft(meeting.id).version, null, true) }
    }

    private fun report(
        by: UserAccount,
        meeting: MeetingRow,
    ): ActionReportResponse = asUser(by) { reportReads.report(meeting.id, null) }

    private fun pdfText(
        by: UserAccount,
        meeting: MeetingRow,
    ): String =
        Loader
            .loadPDF(asUser(by) { reportReads.pdf(meeting.id, null) })
            .use { PDFTextStripper().getText(it) }
            // Lines wrap where the page ends them: one space between words, so a phrase is found whole.
            .replace(Regex("\\s+"), " ")

    /** A draft memo of [by] (only they read it), linked to [actionId] by them; its id. */
    private fun followUp(
        by: UserAccount,
        actionId: UUID,
        subject: String,
    ): UUID {
        val type = asUser(by) { memoTypes.active().first { it.name == "General memo" }.id }
        val memo = asUser(by) { memoDrafts.create(MemoDraftFields(subject, type, levels().first().id), null) }
        asUser(by) { followUps.link(actionId, memo) }
        return memo
    }

    @Autowired lateinit var followUps: com.memo.actions.internal.ActionFollowUps

    @Test
    fun `the action says whether evidence is required and by how many working days it is overdue`() {
        val action = issued()
        val page = checkNotNull(seen(action.id, director2))
        assertThat(page.evidenceRequired).isFalse()
        assertThat(page.overdueWorkingDays).isNull()

        actionsPolicy(ActionsPolicy(evidenceRequired = true))
        assertThat(checkNotNull(seen(action.id, director2)).evidenceRequired).isTrue()

        // Due three working days before today on the tenant's calendar: overdue by 3, or 2 when today is a day off.
        val today = tenantToday()
        val due = checkNotNull(transactions.readInTenant(tenant.id) { workingDays.before(today, 3) })
        val todayWorks = transactions.readInTenant(tenant.id) { workingDays.after(today.minusDays(1), 1) } == today
        alter(action.id, "due_date = ?", due)
        assertThat(checkNotNull(seen(action.id, director2)).overdueWorkingDays).isEqualTo(if (todayWorks) 3 else 2)
        // Overdue is not a colour: a colour set by hand leaves it overdue.
        alter(action.id, "rag_override = 'GREEN', rag_override_reason = 'Agreed with the chair'")
        assertThat(checkNotNull(seen(action.id, secretary)).overdueWorkingDays).isEqualTo(if (todayWorks) 3 else 2)
        // Due today: not overdue. Done, to be verified: never overdue.
        alter(action.id, "due_date = ?", today)
        assertThat(checkNotNull(seen(action.id, director2)).overdueWorkingDays).isNull()
        alter(action.id, "due_date = ?, status = 'DONE_PENDING'", due)
        assertThat(checkNotNull(seen(action.id, director2)).overdueWorkingDays).isNull()
    }

    @Test
    fun `my actions and the report give the key's numbers of the committee type`() {
        issued()
        assertThat(mine(director2).keys).containsExactly(CommitteeRagKey(brc, 10, 20))
        actionsPolicy(ActionsPolicy(amberWorkingDays = 5, staleWorkingDays = ActionsPolicy.OFF))
        assertThat(mine(director2).keys).containsExactly(CommitteeRagKey(brc, 5, null))
        assertThat(mine(treasurer).keys).isEmpty()

        val next = nextMeeting()
        publish(next)
        val key = report(director, next).key
        assertThat(key.amberWorkingDays).isEqualTo(5)
        assertThat(key.staleWorkingDays).isNull()
    }

    @Test
    fun `the item link carries the reader's counts per section`() {
        val d = deciding { m, items -> declareOn(m, items.getValue(com.memo.minutes.DecisionFixture.CREDIT), director) }
        issued(deciding = d, item = d.credit)
        issued(deciding = d, item = d.appetite)
        val overdue = issued(deciding = d, item = d.appetite)
        alter(overdue.id, "due_date = ?", tenantToday().minusDays(2))
        val next = nextMeeting()
        publish(next)
        val link = { by: UserAccount ->
            asUser(by) { memberAgendas.agenda(next.id, null) }
                .sections
                .flatMap { it.items }
                .single { it.standingKind == StandingKind.MATTERS_ARISING }
                .links
                .single()
        }
        val zero = ActionReportSection.SHOWN.associate { it.name to 0 }
        assertThat(link(secretary).counts).isEqualTo(zero + mapOf("OVERDUE" to 1, "OPEN" to 2))
        // The recused member counts none of the credit item's actions.
        assertThat(link(director).counts).isEqualTo(zero + mapOf("OVERDUE" to 1, "OPEN" to 1))
        assertThat(link(director).label).isEqualTo("Action taken report: 1 overdue, 1 open")
    }

    @Test
    fun `each reader sees on the report only the follow-up memos they may read, on screen and in the PDF`() {
        val action = issued()
        val secretarys = followUp(secretary, action.id, "Collateral review note for the committee")
        val owners = followUp(director2, action.id, "CANARY-MEMO owner draft")
        // The owner's memo has a number now; nobody else may read it.
        transactions.inTenant(tenant.id) {
            actionsDsl.current().execute("update memo set reference = ? where id = ?", "CANARY-MEMO/2026/0412", owners)
        }
        val next = nextMeeting()
        publish(next)

        val bySecretary = report(secretary, next).sections.flatMap { it.rows }.single { it.actionId == action.id }
        assertThat(bySecretary.memos.map { it.memoId }).containsExactly(secretarys)
        assertThat(bySecretary.memos.single().subject).isEqualTo("Collateral review note for the committee")
        assertThat(bySecretary.memos.single().status).isEqualTo("DRAFT")
        val byOwner = report(director2, next).sections.flatMap { it.rows }.single { it.actionId == action.id }
        assertThat(byOwner.memos.map { it.reference }).containsExactly("CANARY-MEMO/2026/0412")
        val byMember = report(director, next).sections.flatMap { it.rows }.single { it.actionId == action.id }
        assertThat(byMember.memos).isEmpty()

        assertThat(pdfText(director2, next)).contains("CANARY-MEMO/2026/0412").contains("CANARY-MEMO owner draft")
        listOf(secretary, director, reader).forEach { by ->
            val answer = json.writeValueAsString(report(by, next))
            val pdf = pdfText(by, next)
            listOf(answer, pdf).forEach { assertThat(it).doesNotContain("CANARY-MEMO") }
        }
        assertThat(pdfText(secretary, next)).contains("Collateral review note for the committee")
        // The frozen snapshot holds no memo: the memos are joined when the report is read.
        val snapshots =
            transactions.readInTenant(tenant.id) {
                actionsDsl
                    .current()
                    .select(com.memo.jooq.tables.references.ACTIONS_REPORT_ROW.SNAPSHOT)
                    .from(com.memo.jooq.tables.references.ACTIONS_REPORT_ROW)
                    .where(
                        com.memo.jooq.tables.references.ACTIONS_REPORT_ROW.ACTION_ID
                            .eq(action.id),
                    ).fetch { it.value1().toString() }
            }
        assertThat(snapshots).isNotEmpty().allSatisfy { assertThat(it).doesNotContain("Collateral review", "CANARY-MEMO") }
    }

    @Test
    fun `the verifier may close what the committee agreed once the meeting runs, nobody else`() {
        val action = issued()
        asUser(director2) { closure.done(action.id, row(action.id).version, "The valuation was reported", emptyList()) }
        val next = nextMeeting()
        publish(next)
        val toClose = { by: UserAccount ->
            report(by, next).let { r ->
                r.can.closeAgreed to
                    r.sections
                        .single { it.section == ActionReportSection.TO_CLOSE }
                        .rows
                        .single()
                        .canClose
            }
        }
        // Scheduled: not yet.
        assertThat(toClose(secretary)).isEqualTo(false to false)
        asUser(secretary) { meeting(next.id).let { lifecycle.start(it.id, it.version) } }
        assertThat(toClose(secretary)).isEqualTo(true to true)
        // The owner never verifies; a member is no verifier.
        assertThat(toClose(director2)).isEqualTo(false to false)
        assertThat(toClose(director)).isEqualTo(false to false)
        // Once closed, nothing is left to close.
        asUser(secretary) { closure.verify(action.id, null, com.memo.actions.internal.VerifyDecision.CLOSE, null) }
        assertThat(toClose(secretary)).isEqualTo(false to false)
    }

    @Test
    fun `keeping an action out of the next report is offered by the rule the endpoint enforces`() {
        val d = deciding()
        val action = issued(deciding = d)
        val draft = raise(d.meeting.id, null)
        val holdBack = { by: UserAccount, id: UUID -> checkNotNull(seen(id, by)).can.holdBack }
        // No meeting to come yet (409 NO_MEETING).
        assertThat(holdBack(secretary, action.id)).isFalse()
        nextMeeting()
        assertThat(holdBack(secretary, action.id)).isTrue()
        assertThat(holdBack(director, action.id)).isFalse()
        assertThat(holdBack(director2, action.id)).isFalse()
        assertThat(holdBack(secretary, draft)).isFalse()
        asUser(secretary) { reportHolds.hold(action.id, ReportHoldRequest("Discussed in camera")) }
        // Held back already (409 ALREADY_HELD).
        assertThat(holdBack(secretary, action.id)).isFalse()
    }

    @Test
    fun `asking owners for an update is offered to the secretaries once a day`() {
        issued()
        val can = { by: UserAccount -> asUser(by) { tracker.page(brc, TrackerFilter(), null, 50) }.can }
        assertThat(can(secretary).askForUpdates).isTrue()
        assertThat(can(secretary).askedToday).isFalse()
        assertThat(can(director).askForUpdates).isFalse()
        asUser(secretary) { updateRequests.askNow(brc) }
        assertThat(can(secretary).askForUpdates).isFalse()
        assertThat(can(secretary).askedToday).isTrue()
        assertThat(can(director).askedToday).isFalse()
    }

    @Test
    fun `the committee type's default due date is suggested on the business calendar`() {
        val today = tenantToday()
        // NEXT_MEETING with none to come: the type's working days (20) from today.
        val none = asUser(secretary) { suggestedDue.of(brc) }
        assertThat(none.defaultDue).isEqualTo(DefaultDue.NEXT_MEETING)
        assertThat(none.basis).isEqualTo(DefaultDue.WORKING_DAYS)
        assertThat(none.workingDays).isEqualTo(20)
        assertThat(none.date).isEqualTo(transactions.readInTenant(tenant.id) { workingDays.after(today, 20) })

        val next = nextMeeting(days = 21)
        val byMeeting = asUser(secretary) { suggestedDue.of(brc) }
        assertThat(byMeeting.basis).isEqualTo(DefaultDue.NEXT_MEETING)
        assertThat(byMeeting.meetingId).isEqualTo(next.id)
        assertThat(byMeeting.date).isEqualTo(next.startsAt.atZone(next.zone).toLocalDate())

        actionsPolicy(ActionsPolicy(defaultDue = DefaultDue.WORKING_DAYS, defaultDueWorkingDays = 10))
        val byDays = asUser(secretary) { suggestedDue.of(brc) }
        assertThat(byDays.basis).isEqualTo(DefaultDue.WORKING_DAYS)
        assertThat(byDays.date).isEqualTo(transactions.readInTenant(tenant.id) { workingDays.after(today, 10) })
        assertThat(byDays.meetingId).isNull()

        assertThat(deniedAs(director) { suggestedDue.of(brc) }).startsWith("403")
        assertThat(deniedAs(secretary) { suggestedDue.of(UUID.randomUUID()) }).isEqualTo("404")
    }
}
