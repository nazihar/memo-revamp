package com.memo.demo

import com.memo.actions.ActionSeeding
import com.memo.common.authz.AccessAdministration
import com.memo.common.context.Actor
import com.memo.common.context.ActorContext
import com.memo.common.context.ActorScope
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantTransactions
import com.memo.files.FileUploads
import com.memo.forum.ForumDirectory
import com.memo.forum.ForumSeeding
import com.memo.forum.SubmissionStatus
import com.memo.forum.internal.ForumSubmissions
import com.memo.identity.MfaSeeding
import com.memo.identity.UserAccounts
import com.memo.identity.operator.OperatorMfa
import com.memo.meeting.AgendaSeeding
import com.memo.meeting.AttendanceCapacity
import com.memo.meeting.AttendanceMode
import com.memo.meeting.AttendanceSeeding
import com.memo.meeting.AttendanceState
import com.memo.meeting.DeclarationHow
import com.memo.meeting.DeclarationSeeding
import com.memo.meeting.DeclarationSource
import com.memo.meeting.DeclarationState
import com.memo.meeting.LineAccess
import com.memo.meeting.MeetingAttendance
import com.memo.meeting.MeetingSeeding
import com.memo.meeting.MeetingStatus
import com.memo.meeting.QuorumChangeKind
import com.memo.meeting.QuorumSnapshots
import com.memo.meeting.ReplySeeding
import com.memo.meeting.internal.AgendaDraftViews
import com.memo.meeting.internal.AgendaNoticeScheduler
import com.memo.meeting.internal.DeclarationReviews
import com.memo.meeting.internal.ItemFlag
import com.memo.meeting.internal.ItemKind
import com.memo.meeting.internal.Lateness
import com.memo.meeting.internal.MeetingReads
import com.memo.meeting.internal.MemberAgendas
import com.memo.meeting.internal.PaperFlag
import com.memo.meeting.internal.PublishedLate
import com.memo.meeting.internal.invite.InvitationScheduler
import com.memo.memo.MemoAccessSeeding
import com.memo.memo.MemoInterestLinks
import com.memo.memo.MemoSeeding
import com.memo.memo.internal.Memos
import com.memo.minutes.DecisionSeeding
import com.memo.minutes.MinutesSeeding
import com.memo.minutes.internal.MinutesCopies
import com.memo.minutes.internal.MinutesJobs
import com.memo.minutes.internal.MinutesRecords
import com.memo.minutes.internal.MinutesReleaseScheduler
import com.memo.org.OrgDirectory
import com.memo.org.OrgSeeding
import com.memo.tenancy.TenantRegistry
import com.memo.tenancy.TenantSettings
import com.memo.testsupport.AppRoleJdbc
import com.memo.testsupport.AppRoleJdbc.long
import com.memo.testsupport.FilesIntegrationTest
import com.memo.testsupport.aPassword
import com.memo.workflow.WorkflowSeeding
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.core.env.Environment
import java.time.Clock
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

/**
 * Phase 4 E03 demo data (T9): the Board Risk Committee's review published twice (a replaced paper, a late paper the
 * chair agreed, a consent item lifted by Demo Director, an item whose papers only the chair reads, an in-camera section
 * and a guest), and the credit committee's meeting with the BDT 15 million proposal placed from the queue. E04 T9 adds
 * declarations, E05 T10 the risk review's replies and the credit committee's held review with its register, quorum
 * changes and a decision's snapshot. E06 T12 adds the risk committee's held review with its decisions and circulated
 * minutes, and the credit review's decisions (the memo moved on with its number), approved minutes and an extract. Once
 * only.
 */
@Suppress("LargeClass") // One seed checked in one class: the demo tenant is shared, so its epics cannot be split apart.
class DemoMeetingsIT : FilesIntegrationTest() {
    @Autowired lateinit var committees: DemoForumSeeder

    @Autowired lateinit var forumDirectory: ForumDirectory

    @Autowired lateinit var registry: TenantRegistry

    @Autowired lateinit var settings: TenantSettings

    @Autowired lateinit var transactions: TenantTransactions

    @Autowired lateinit var environment: Environment

    @Autowired lateinit var accounts: UserAccounts

    @Autowired lateinit var mfaSeeding: MfaSeeding

    @Autowired lateinit var grants: AccessAdministration

    @Autowired lateinit var orgSeeding: OrgSeeding

    @Autowired lateinit var operators: OperatorMfa

    @Autowired lateinit var workflowSeeding: WorkflowSeeding

    @Autowired lateinit var memoSeeding: MemoSeeding

    @Autowired lateinit var memoAccess: MemoAccessSeeding

    @Autowired lateinit var memoTemplates: com.memo.memo.MemoTemplateSeeding

    @Autowired lateinit var uploads: FileUploads

    @Autowired lateinit var lifecycle: com.memo.identity.UserLifecycle

    @Autowired lateinit var clock: Clock

    @Autowired lateinit var meetingSeeding: MeetingSeeding

    @Autowired lateinit var agendaSeeding: AgendaSeeding

    @Autowired lateinit var declarationSeeding: DeclarationSeeding

    @Autowired lateinit var replySeeding: ReplySeeding

    @Autowired lateinit var attendanceSeeding: AttendanceSeeding

    @Autowired lateinit var registers: MeetingAttendance

    @Autowired lateinit var snapshots: QuorumSnapshots

    @Autowired lateinit var declarationReviews: DeclarationReviews

    @Autowired lateinit var memoInterests: MemoInterestLinks

    @Autowired lateinit var forumSeeding: ForumSeeding

    @Autowired lateinit var reads: MeetingReads

    @Autowired lateinit var drafts: AgendaDraftViews

    @Autowired lateinit var agendas: MemberAgendas

    @Autowired lateinit var submissions: ForumSubmissions

    @Autowired lateinit var memos: Memos

    @Autowired lateinit var notices: AgendaNoticeScheduler

    @Autowired lateinit var invitations: InvitationScheduler

    @Autowired lateinit var decisionSeeding: DecisionSeeding

    @Autowired lateinit var minutesSeeding: MinutesSeeding

    @Autowired lateinit var minutesJobs: MinutesJobs

    @Autowired lateinit var releases: MinutesReleaseScheduler

    @Autowired lateinit var minutesCopies: MinutesCopies

    @Autowired lateinit var minutesRecords: MinutesRecords

    @Autowired lateinit var actionSeeding: ActionSeeding

    @Autowired lateinit var orgDirectory: OrgDirectory

    @Test
    fun `the risk review is published twice and the credit committee has the proposal from its queue, once`() {
        val password = aPassword()
        // Test contexts share the scheduler table: the notices and invitations the seed causes are not scheduled here.
        notices.jobs = false
        invitations.jobs = false
        // The minutes' jobs too: the copies and the record are built here by hand, as their jobs would.
        minutesJobs.jobs = false
        releases.jobs = false
        try {
            DemoTenantSeeder(
                registry,
                settings,
                transactions,
                environment,
                accounts,
                password,
                mfaSeeding,
                "",
                grants,
                orgSeeding,
                operators,
            ).seed()
            DemoWorkflowSeeder(accounts, workflowSeeding, memoSeeding, transactions, committees, forumDirectory).seed()
            // As at start-up: the demo memos first. They are seeded only while Demo User has no memo, so seeding the credit
            // proposal first would leave them out for DemoMemosIT, whichever test runs first.
            DemoMemoSeeder(accounts, lifecycle, memoSeeding, transactions, uploads, password).seed()
            val seeder = meetingSeeder(password)
            val seeded = checkNotNull(seeder.seed())
            assertRiskReview(seeded.riskReview)
            assertCreditMeeting(seeded.creditMeeting)
            assertDeclarations(seeded)
            assertReplies(seeded.riskReview)
            assertHeldReview(seeded.heldCreditReview)
            val e05Rows = e05Rows(seeded)
            assertRiskMinutes(seeded)
            assertCreditMinutes(seeded.heldCreditReview)
            val e06Rows = e06Rows(seeded)
            assertActions(seeded)
            // E08 T8: the committees' year of history, after the meetings above (which keep their numbers).
            val (history, historyMeetings) = seedHistory(password)
            val historyRows = DemoHistoryRows.rows(historyMeetings)
            // After the history: its meetings have their action taken reports too.
            val e07Rows = e07Rows()

            // Fills gaps only: a second run finds the three meetings and adds nothing.
            assertThat(seeder.seed()).isEqualTo(seeded)
            assertThat(e05Rows(seeded)).isEqualTo(e05Rows)
            assertThat(e06Rows(seeded)).isEqualTo(e06Rows)
            assertThat(e07Rows()).isEqualTo(e07Rows)
            assertThat(history.seed()).isEqualTo(historyMeetings)
            assertThat(DemoHistoryRows.rows(historyMeetings)).isEqualTo(historyRows)
            assertOnce(seeded)
        } finally {
            notices.jobs = true
            invitations.jobs = true
            minutesJobs.jobs = true
            releases.jobs = true
        }
    }

    /** After a second run: each meeting, the two credit memos and the risk review's declarations once. */
    private fun assertOnce(seeded: DemoMeetingSeeder.Seeded) {
        AppRoleJdbc.inTransaction(DemoTenant.ID) { c ->
            listOf(
                DemoMeetingSeeder.RISK_TITLE,
                DemoMeetingSeeder.CREDIT_TITLE,
                DemoMeetingSeeder.HELD_TITLE,
                DemoMinutesSeeder.RISK_TITLE,
            ).forEach {
                assertThat(c.long("select count(*) from meeting where title = ?", it)).describedAs(it).isOne()
            }
            assertThat(c.long("select count(*) from memo where subject = ?", DemoMeetingSeeder.CREDIT_SUBJECT)).isOne()
            assertThat(c.long("select count(*) from memo where subject = ?", DemoMeetingSeeder.MEGHNA)).isOne()
            val both = arrayOf<Any>(seeded.riskReview, seeded.creditMeeting)
            assertThat(c.long("select count(*) from meeting_declaration where meeting_id in (?, ?)", *both)).isEqualTo(4)
            assertThat(c.long("select count(*) from meeting_declaration_review where meeting_id in (?, ?)", *both)).isOne()
        }
    }

    private fun meetingSeeder(password: String): DemoMeetingSeeder {
        val e05 =
            DemoMeetingAttendanceSeeder(
                meetingSeeding,
                agendaSeeding,
                declarationSeeding,
                replySeeding,
                attendanceSeeding,
                forumDirectory,
                transactions,
            )
        return DemoMeetingSeeder(
            meetingSeeding,
            agendaSeeding,
            declarationSeeding,
            e05,
            DemoHeldRiskReviewSeeder(meetingSeeding, agendaSeeding, declarationSeeding, attendanceSeeding, e05),
            DemoMinutesSeeder(declarationSeeding, attendanceSeeding, decisionSeeding, minutesSeeding),
            DemoActionsSeeder(
                actionSeeding,
                declarationSeeding,
                attendanceSeeding,
                agendaSeeding,
                meetingSeeding,
                memoSeeding,
                memoTemplates,
                accounts,
                orgDirectory,
                transactions,
            ),
            memoSeeding,
            memoAccess,
            forumSeeding,
            forumDirectory,
            accounts,
            transactions,
            clock,
            password,
        )
    }

    /** E08 T8: the committees' year of history seeded and checked ([DemoHistoryRows]); the seeder and its meetings. */
    private fun seedHistory(password: String): Pair<DemoHistorySeeder, Map<String, UUID>> {
        val history =
            DemoHistorySeeder(
                meetingSeeding,
                agendaSeeding,
                declarationSeeding,
                replySeeding,
                attendanceSeeding,
                decisionSeeding,
                forumDirectory,
                accounts,
                transactions,
                clock,
                password,
            )
        val historyMeetings = checkNotNull(history.seed())
        val secretary = id(DemoMeetingSeeder.SECRETARY)
        DemoHistoryRows.assertHistory(
            historyMeetings,
            LocalDate.now(clock.withZone(DemoMeetingSeeder.ZONE)),
            held = { asUser(secretary) { reads.get(it) }.let { m -> (m.status == MeetingStatus.HELD) to m.startsAt } },
            people = id(DemoMeetingSeeder.DIRECTOR) to id(DemoMeetingSeeder.HEAD_CREDIT),
            forums =
                listOf(DemoForumSeeder.RISK, DemoForumSeeder.CREDIT).map { key ->
                    asSystem { transactions.inTenant(DemoTenant.ID) { checkNotNull(forumDirectory.forumByKey(key)).id } }
                },
        )
        return history to historyMeetings
    }

    @Test
    fun `meetings are held on a Monday to Thursday`() {
        val monday = LocalDate.of(2026, 11, 23)
        assertThat((0L..6L).map { DemoMeetingSeeder.weekday(monday.plusDays(it)).dayOfWeek }.toSet())
            .containsOnly(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY)
        assertThat(DemoMeetingSeeder.weekday(monday.plusDays(4))).isEqualTo(monday.plusDays(7))
    }

    private fun assertRiskReview(meeting: UUID) {
        val secretary = id(DemoMeetingSeeder.SECRETARY)
        val director = id(DemoMeetingSeeder.DIRECTOR)
        val chair = id(DemoMeetingSeeder.BOARD_MEMBER)
        val read = asUser(secretary) { reads.get(meeting) }
        assertThat(read.status).isEqualTo(MeetingStatus.SCHEDULED)
        assertThat(read.label).matches("BRC \\d+/\\d{4}")
        assertThat(read.guests.map { it.note }).containsExactly("Chief Financial Officer")
        // Brought forward to within the paper deadline, never to a weekend.
        val day = read.startsAt.atZone(DemoMeetingSeeder.ZONE).toLocalDate()
        assertThat(day).isAfter(LocalDate.now(clock.withZone(DemoMeetingSeeder.ZONE)))
        assertThat(day.dayOfWeek).isBetween(DayOfWeek.MONDAY, DayOfWeek.THURSDAY)

        // E07 T12 publishes revision 3 (the cyber figures for matters arising, with the action taken report).
        val latest = asUser(director) { agendas.agenda(meeting, null) }
        assertThat(latest.revision).isEqualTo(3)
        assertThat(latest.note).isEqualTo(DemoActionsSeeder.PUBLISH_NOTE)
        // The director reads revision 2, has seen revision 1, and so sees what changed.
        val agenda = asUser(director) { agendas.agenda(meeting, 2) }
        assertThat(agenda.revision to agenda.latestRevision).isEqualTo(2 to 3)
        assertThat(agenda.note).isEqualTo("Updated risk appetite paper; a late liquidity addendum agreed by the chair")
        assertThat(agenda.lastSeenRevision).isEqualTo(1)
        // What changed is told on the latest revision, since the one the director last saw.
        assertThat(latest.lastSeenRevision).isEqualTo(1)
        assertThat(latest.whatChanged).isNotNull()
        val items = agenda.sections.flatMap { it.items }
        val appetite = items.single { it.title == "Risk appetite statement 2027" }
        assertThat(appetite.changes).contains(ItemFlag.PAPER_REPLACED)
        assertThat(appetite.papers.single().changes).containsExactly(PaperFlag.REPLACED)
        assertThat(appetite.presenters.map { it.name }).containsExactly("Demo Head of Credit")
        val liquidity = items.single { it.title == "Liquidity and funding update" }
        assertThat(liquidity.papers.map { it.title to it.late }).contains("liquidity-stress-addendum.pdf" to PublishedLate.LATE)
        // Only the chair reads the large exposures paper: the director sees its line.
        val exposures = items.single { it.title == "Large exposures and concentration limits" }
        assertThat(exposures.access to exposures.papers).isEqualTo(LineAccess.TITLE to emptyList<Any>())
        assertThat(
            asUser(chair) { agendas.agenda(meeting, null) }
                .sections
                .flatMap { it.items }
                .single { it.title == exposures.title }
                .papers,
        ).hasSize(1)
        // The consent section, one item lifted at the director's request.
        val consent = agenda.sections.single { it.title == DemoMeetingSeeder.CONSENT }
        assertThat(consent.items).hasSize(3)
        val lifted = consent.items.single { it.title == DemoMeetingSeeder.CONTINUITY }.discussion
        assertThat(lifted?.requestedBy?.map { it.name }).containsExactly("Demo Director")
        assertThat(
            consent.items.filter {
                it.discussion
                    ?.requestedBy
                    .orEmpty()
                    .isNotEmpty()
            },
        ).hasSize(1)
        assertThat(agenda.sections.single { it.title == DemoMeetingSeeder.IN_CAMERA }.membersOnly).isTrue()

        // The draft: the late paper was agreed by the chair in the app.
        val draft = asUser(secretary) { drafts.draft(meeting) }
        val addendum =
            draft.sections
                .flatMap { it.items }
                .flatMap { it.papers }
                .single { it.title == "liquidity-stress-addendum.pdf" }
        assertThat(addendum.late).isEqualTo(Lateness.CHAIR_AGREED)
        assertThat(draft.chairAgreement?.onBehalfBy?.id).isEqualTo(secretary)
        assertThat(draft.chairAgreement?.by?.id).isEqualTo(chair)
        assertThat(draft.dirty).isFalse()
    }

    private fun assertCreditMeeting(meeting: UUID) {
        val secretary = id(DemoMeetingSeeder.SECRETARY)
        val author = id(DemoMeetingSeeder.AUTHOR)
        assertThat(asUser(secretary) { reads.get(meeting) }.status).isEqualTo(MeetingStatus.SCHEDULED)
        val draft = asUser(secretary) { drafts.draft(meeting) }
        assertThat(draft.currentRevision).isZero()
        val placed = draft.sections.flatMap { it.items }.single { it.kind == ItemKind.SUBMISSION }
        assertThat(placed.title).isEqualTo(DemoMeetingSeeder.CREDIT_SUBJECT)
        assertThat(placed.late).isEqualTo(Lateness.NONE)
        assertThat(placed.papers).isNotEmpty()
        val memo = checkNotNull(asUser(author) { memoAccess.find(author, DemoMeetingSeeder.CREDIT_SUBJECT) })
        val credit = checkNotNull(asUser(secretary) { forumDirectory.forumByKey(DemoForumSeeder.CREDIT) }).id
        val submission = asUser(secretary) { submissions.get(credit, checkNotNull(placed.submissionId)) }
        assertThat(submission.subjectId).isEqualTo(memo)
        assertThat(submission.status to submission.meetingId).isEqualTo(SubmissionStatus.ACCEPTED to meeting)
        assertThat(asUser(author) { memos.get(memo).metadata["customer"] }).isEqualTo("Padma Spinning Mills Ltd")
    }

    /**
     * E04 T9: at the risk review Demo Director is recused from the Delta Textiles item, waits for the chair's ruling on a
     * consent item and confirmed revision 2; the secretary recorded the Chairman's declaration by email. At the credit
     * committee the head of credit's interest on the memo (they stood aside at the desk) was carried to the placed item.
     */
    private fun assertDeclarations(seeded: DemoMeetingSeeder.Seeded) {
        val director = id(DemoMeetingSeeder.DIRECTOR)
        val secretary = id(DemoMeetingSeeder.SECRETARY)
        val headCredit = id(DemoMeetingSeeder.HEAD_CREDIT)
        val author = id(DemoMeetingSeeder.AUTHOR)
        val mine = asUser(director) { declarationReviews.mine(seeded.riskReview) }
        assertThat(mine.reviewedRevision).isEqualTo(2)
        assertThat(mine.toReview).isEmpty()
        assertThat(mine.declarations.map { Triple(it.itemTitle, it.state, it.source) })
            .containsExactlyInAnyOrder(
                Triple(DemoMeetingSeeder.DELTA, DeclarationState.RECUSED, DeclarationSource.SELF),
                Triple(DemoMeetingSeeder.INCIDENTS, DeclarationState.AWAITING_RULING, DeclarationSource.SELF),
            )
        val items = asUser(director) { agendas.agenda(seeded.riskReview, null) }.sections.flatMap { it.items }
        val delta = items.single { it.title == DemoMeetingSeeder.DELTA }
        assertThat(delta.recused to delta.papers).isEqualTo(true to emptyList<Any>())
        assertThat(items.single { it.title == DemoMeetingSeeder.INCIDENTS }.recused).isTrue()
        assertThat(items.single { it.title == "Risk appetite statement 2027" }.recused).isFalse()
        val byEmail =
            AppRoleJdbc.inTransaction(DemoTenant.ID) { c ->
                c.long(
                    "select count(*) from meeting_declaration d join meeting_item i on i.id = d.item_id " +
                        "where d.meeting_id = ? and i.title = ? and d.how = ? and d.source = ? and d.state = ? " +
                        "and d.recorded_by = ? and d.how_note like 'Email of %'",
                    seeded.riskReview,
                    DemoMeetingSeeder.EXPOSURES,
                    DeclarationHow.EMAIL.name,
                    DeclarationSource.ON_BEHALF.name,
                    DeclarationState.RECUSED.name,
                    secretary,
                )
            }
        assertThat(byEmail).isOne()

        val placed =
            asUser(secretary) { drafts.draft(seeded.creditMeeting) }.sections.flatMap { it.items }.single {
                it.kind == ItemKind.SUBMISSION
            }
        val carried = asUser(headCredit) { declarationReviews.mine(seeded.creditMeeting) }.declarations.single()
        assertThat(carried.itemId).isEqualTo(placed.id)
        assertThat(carried.source to carried.state).isEqualTo(DeclarationSource.MEMO to DeclarationState.RECUSED)
        assertThat(carried.nature).startsWith("Interest declared on memo")
        val memo = checkNotNull(asUser(author) { memoAccess.find(author, DemoMeetingSeeder.CREDIT_SUBJECT) })
        assertThat(asUser(secretary) { memoInterests.of(memo) }).containsExactly(headCredit)
    }

    /**
     * E05 T10: at the risk review Demo Director sent apologies with their alternate standing in, the CFO sent apologies,
     * the secretary recorded the Chairman's yes by phone, and the chair's yes is for the old time.
     */
    private fun assertReplies(meeting: UUID) {
        val secretary = id(DemoMeetingSeeder.SECRETARY)
        val director = id(DemoMeetingSeeder.DIRECTOR)
        val headCredit = id(DemoMeetingSeeder.HEAD_CREDIT)
        AppRoleJdbc.inTransaction(DemoTenant.ID) { c ->
            fun count(
                where: String,
                vararg params: Any,
            ) = c.long(
                "select count(*) from meeting_rsvp r join meeting m on m.id = r.meeting_id where r.meeting_id = ? and $where",
                meeting,
                *params,
            )
            assertThat(count("r.answer = 'APOLOGIES'")).isEqualTo(2)
            assertThat(count("r.user_id = ? and r.answer = 'APOLOGIES' and r.alternate_attends and r.how = 'IN_APP'", director))
                .isOne()
            assertThat(count("r.user_id = ? and r.answer = 'APOLOGIES'", id(DemoMeetingSeeder.HEAD_FINANCE))).isOne()
            assertThat(
                count("r.how = 'PHONE' and r.recorded_by = ? and r.answer = 'IN_PERSON' and r.user_id <> ?", secretary, secretary),
            ).isOne()
            // The chair's answer is for the planned date: stale since the meeting was brought forward.
            assertThat(count("r.user_id = ? and r.for_starts_at <> m.starts_at", id(DemoMeetingSeeder.BOARD_MEMBER))).isOne()
            assertThat(count("r.for_starts_at <> m.starts_at")).isOne()
            assertThat(
                c.long(
                    "select count(*) from meeting_audience where meeting_id = ? and user_id = ? and source = 'ALTERNATE' " +
                        "and for_user_id = ? and removed_at is null",
                    meeting,
                    headCredit,
                    director,
                ),
            ).isOne()
        }
        // The alternate inherits Demo Director's recusals (ADR-0064): the Delta Textiles line without its papers.
        val items = asUser(headCredit) { agendas.agenda(meeting, null) }.sections.flatMap { it.items }
        val delta = items.single { it.title == DemoMeetingSeeder.DELTA }
        assertThat(delta.recused to delta.papers).isEqualTo(true to emptyList<Any>())
    }

    /**
     * E05 T10: the credit committee's review held about ten days ago, hybrid: the CRO arrived late, the vice-chair stepped
     * out for the Meghna Foods item they declared an interest in, the head of finance left early, and the meeting's quorum
     * was reached, lost once and restored; the Meghna Foods decision has a quorate snapshot that re-evaluates to itself.
     */
    private fun assertHeldReview(meeting: UUID) {
        val secretary = id(DemoMeetingSeeder.SECRETARY)
        val director = id(DemoMeetingSeeder.DIRECTOR)
        val headCredit = id(DemoMeetingSeeder.HEAD_CREDIT)
        val finance = id(DemoMeetingSeeder.HEAD_FINANCE)
        val read = asUser(secretary) { reads.get(meeting) }
        // Held, and since E06 T12 its minutes are approved too.
        assertThat(read.status).isEqualTo(MeetingStatus.MINUTES_APPROVED)
        assertThat(read.startsAt).isBefore(clock.instant())
        assertThat(read.label).matches("MCC \\d+/\\d{4}")
        val meghna = checkNotNull(asUser(secretary) { declarationSeeding.item(meeting, DemoMeetingSeeder.MEGHNA) })
        val register = checkNotNull(asUser(secretary) { registers.of(meeting) })
        val people = register.people.associateBy { it.userId }
        assertThat(people.getValue(secretary).state).isEqualTo(AttendanceState.PRESENT)
        assertThat(people.getValue(director).let { it.state to it.joinedLate }).isEqualTo(AttendanceState.PRESENT to false)
        assertThat(people.getValue(finance).let { it.state to it.leftEarly }).isEqualTo(AttendanceState.LEFT to true)
        val vice = people.getValue(headCredit)
        assertThat(Triple(vice.state, vice.mode, vice.steppedOutOf))
            .isEqualTo(Triple(AttendanceState.PRESENT, AttendanceMode.REMOTE, listOf(meghna)))
        val late = register.people.filter { it.joinedLate }
        assertThat(late).hasSize(1)
        assertThat(late.single().let { it.capacity to it.state }).isEqualTo(AttendanceCapacity.MEMBER to AttendanceState.PRESENT)
        // The four opening items, the three of the meeting and any other business.
        assertThat(register.itemsInProgress).hasSize(8)
        assertThat(register.quorumChanges.map { it.kind })
            .containsExactly(QuorumChangeKind.REACHED, QuorumChangeKind.LOST, QuorumChangeKind.RESTORED)
        val lost = register.quorumChanges[1]
        assertThat(lost.needed to lost.present).isEqualTo(3 to 2)
        val day = read.startsAt.atZone(DemoMeetingSeeder.ZONE).toLocalDate()
        assertThat(lost.at).isEqualTo(DemoMeetingSeeder.at(day, LocalTime.of(15, 30)))

        // The first snapshot is E05's; E06's decisions took their own after it.
        val snapshot = asUser(secretary) { snapshots.ofMeeting(meeting) }.first { it.itemId == meghna }
        assertThat(snapshot.itemId).isEqualTo(meghna)
        assertThat(snapshot.result.quorate).isTrue()
        assertThat(snapshot.override).isNull()
        assertThat(snapshot.inputs.recused).contains(headCredit)
        assertThat(snapshot.inputs.evaluate()).isEqualTo(snapshot.result)
        assertThat(snapshot.result.present to snapshot.result.needed).isEqualTo(3 to 3)
    }

    /**
     * E06 T12: the risk committee's held review, numbered before the quarterly review: Demo Director recused from the Rupsha
     * Steel item and out of the room for it; two items approved and the consent section decided together, numbered in that
     * order; the minutes circulated (approved at the next meeting), linked to the quarterly review's previous-minutes item,
     * the director's comment on the cyber item, and two copies: the full one and the director's without the Rupsha item.
     */
    private fun assertRiskMinutes(seeded: DemoMeetingSeeder.Seeded) {
        val meeting = seeded.heldRiskReview
        val secretary = id(DemoMeetingSeeder.SECRETARY)
        val director = id(DemoMeetingSeeder.DIRECTOR)
        val held = asUser(secretary) { reads.get(meeting) }
        assertThat(held.status).isEqualTo(MeetingStatus.HELD)
        assertThat(held.startsAt).isBefore(clock.instant())
        val number = { m: UUID -> checkNotNull(asUser(secretary) { reads.get(m) }.label).substringBefore('/').substringAfter(' ').toInt() }
        assertThat(number(meeting)).isLessThan(number(seeded.riskReview))
        val item = { title: String -> checkNotNull(asUser(secretary) { declarationSeeding.item(meeting, title) }) }
        val resolution = { title: String -> asUser(secretary) { decisionSeeding.resolution(item(title)) }?.first }
        val year = held.startsAt.atZone(DemoMeetingSeeder.ZONE).year
        assertThat(listOf(DemoMinutesSeeder.RUPSHA, DemoMinutesSeeder.CYBER, DemoMinutesSeeder.POLICY).map(resolution))
            .containsExactly("BRC/$year/001", "BRC/$year/002", "BRC/$year/003")
        assertThat(resolution(DemoMinutesSeeder.INCIDENTS)).isNull()
        AppRoleJdbc.inTransaction(DemoTenant.ID) { c ->
            assertThat(
                c.long(
                    "select count(*) from minutes_decision_entry e join minutes_decision d on d.id = e.decision_id " +
                        "where d.meeting_id = ? and e.consent_batch_id is not null",
                    meeting,
                ),
            ).isEqualTo(2)
            assertThat(
                c.long(
                    "select count(*) from minutes_decision_entry e join minutes_decision d on d.id = e.decision_id " +
                        "where d.item_id = ? and ? = any(e.took_no_part)",
                    item(DemoMinutesSeeder.RUPSHA),
                    director,
                ),
            ).isOne()
            assertThat(
                c.long(
                    "select count(*) from minutes where meeting_id = ? and state = 'CIRCULATED' and linked_meeting_id = ? " +
                        "and policy ->> 'approval' = 'AT_NEXT_MEETING'",
                    meeting,
                    seeded.riskReview,
                ),
            ).isOne()
            assertThat(
                c.long(
                    "select count(*) from minutes_comment where meeting_id = ? and user_id = ? and kind = 'SUGGESTION' and item_id = ?",
                    meeting,
                    director,
                    item(DemoMinutesSeeder.CYBER),
                ),
            ).isOne()
        }
        // The copies, as the job builds them: everyone's, and the director's without the item they declared an interest in.
        asSystem { minutesCopies.build(meeting, last = true) }
        AppRoleJdbc.inTransaction(DemoTenant.ID) { c ->
            assertThat(c.long("select count(*) from minutes_copy where meeting_id = ? and state = 'DONE'", meeting)).isEqualTo(2)
            assertThat(
                c.long(
                    "select count(*) from minutes_copy where meeting_id = ? and state = 'DONE' and redacted::text like ?",
                    meeting,
                    "%${item(DemoMinutesSeeder.RUPSHA)}%",
                ),
            ).isOne()
        }
    }

    /**
     * E06 T12: the credit review's decisions: the Meghna Foods memo approved with conditions by 2–1 with the head of
     * finance's dissent and the head of credit taking no part, numbered MCC/…/001 and the memo moved on with it; the
     * watch-list deferred; the portfolio report noted without a quorum, to be ratified. The minutes approved by circulation
     * (the meeting's minutes approved), the record made and an extract of the resolution issued.
     */
    private fun assertCreditMinutes(meeting: UUID) {
        val secretary = id(DemoMeetingSeeder.SECRETARY)
        val finance = id(DemoMeetingSeeder.HEAD_FINANCE)
        val headCredit = id(DemoMeetingSeeder.HEAD_CREDIT)
        val meghna = checkNotNull(asUser(secretary) { declarationSeeding.item(meeting, DemoMeetingSeeder.MEGHNA) })
        val year = asUser(secretary) { reads.get(meeting) }.startsAt.atZone(DemoMeetingSeeder.ZONE).year
        val (number, resolutionId) = checkNotNull(asUser(secretary) { decisionSeeding.resolution(meghna) })
        assertThat(number).isEqualTo("MCC/$year/001")
        assertThat(asUser(secretary) { reads.get(meeting) }.status).isEqualTo(MeetingStatus.MINUTES_APPROVED)
        AppRoleJdbc.inTransaction(DemoTenant.ID) { c ->
            assertThat(
                c.long(
                    "select count(*) from minutes_decision_entry e join minutes_decision d on d.id = e.decision_id " +
                        "where d.item_id = ? and e.outcome_kind = 'APPROVED_WITH_CONDITIONS' and jsonb_array_length(e.conditions) = 2 " +
                        "and e.votes_for = 2 and e.votes_against = 1 and e.dissent::text like ? and ? = any(e.took_no_part) " +
                        "and d.released_at is not null and d.memo_id is not null",
                    meghna,
                    "%$finance%",
                    headCredit,
                ),
            ).isOne()

            fun outcome(title: String) =
                c.long(
                    "select count(*) from minutes_decision_entry e join minutes_decision d on d.id = e.decision_id " +
                        "join meeting_item i on i.id = d.item_id where d.meeting_id = ? and i.title = ? and e.outcome_kind = ? " +
                        "and e.ratification_required = ?",
                    meeting,
                    title,
                    if (title == DemoMeetingSeeder.WATCH_LIST) "DEFERRED" else "NOTED",
                    title == DemoMeetingSeeder.PORTFOLIO,
                )
            assertThat(outcome(DemoMeetingSeeder.WATCH_LIST)).isOne()
            assertThat(outcome(DemoMeetingSeeder.PORTFOLIO)).isOne()
            // The memo's note sheet carries the committee's decision with its number.
            assertThat(
                c.long(
                    "select count(*) from minutes_decision d join workflow_instance w on w.subject_id = d.memo_id " +
                        "join workflow_action a on a.instance_id = w.id " +
                        "where d.item_id = ? and a.kind = 'FORUM_DECIDED' and a.remark like ?",
                    meghna,
                    "%Resolution $number:%",
                ),
            ).isOne()
            assertThat(
                c.long(
                    "select count(*) from minutes where meeting_id = ? and state = 'APPROVED' and policy ->> 'approval' = 'BY_CIRCULATION'",
                    meeting,
                ),
            ).isOne()
            assertThat(c.long("select count(*) from minutes_comment where meeting_id = ? and kind = 'NO_COMMENTS'", meeting)).isEqualTo(3)
            assertThat(c.long("select count(*) from minutes_extract where resolution_id = ?", resolutionId)).isOne()
        }
        // The record, as its job makes it.
        asSystem { minutesRecords.build(meeting, 1, last = true) }
        AppRoleJdbc.inTransaction(DemoTenant.ID) { c ->
            assertThat(c.long("select count(*) from minutes_record where meeting_id = ? and state = 'READY'", meeting)).isOne()
        }
    }

    /**
     * E07 T12: the five actions as [DemoActionsSeeder] says, the follow-up memo approved by someone other than its
     * initiator, the overdue action escalated by the engine, and the quarterly review's report listing the risk committee's
     * four in their sections.
     */
    private fun assertActions(seeded: DemoMeetingSeeder.Seeded) {
        val holder = id(DemoOrg.staffEmail(DemoActionsSeeder.HEAD_CREDIT_RISK_NAME))
        AppRoleJdbc.inTransaction(DemoTenant.ID) { c ->
            val charge = rowOf(c, DemoActionsSeeder.CHARGE)
            assertThat(charge.getValue("reference")).matches("MCC A1/\\d{4}")
            assertThat(charge).containsEntry("status", "CLOSED").containsEntry("source", "DECISION").containsEntry("condition_index", "0")
            assertThat(charge).containsEntry("owner_kind", "POSITION").containsEntry("holder_user_id", holder.toString())
            assertThat(charge.getValue("meeting_id")).isEqualTo(seeded.heldCreditReview.toString())
            val chargeId = UUID.fromString(charge.getValue("id"))
            val evidence = "select count(*) from actions_entry where action_id = ? and kind = 'DONE' and cardinality(file_ids) = 1"
            assertThat(c.long(evidence, chargeId)).isOne()
            val memo =
                "select count(*) from actions_memo m join memo on memo.id = m.memo_id where m.action_id = ? and m.kind = 'RAISED' " +
                    "and memo.status = 'APPROVED' and memo.initiator_id = ?"
            assertThat(c.long(memo, chargeId, holder)).isOne()

            val cyber = rowOf(c, DemoActionsSeeder.INSURANCE)
            assertThat(cyber).containsEntry("status", "DONE_PENDING").containsEntry("source", "ITEM")
            assertThat(cyber).containsEntry("owner_user_id", id(DemoMeetingSeeder.DIRECTOR).toString())

            val rupsha = rowOf(c, DemoActionsSeeder.RUPSHA_BORROWINGS)
            assertThat(rupsha).containsEntry("status", "OPEN").containsEntry("source", "DECISION").containsEntry("owner_kind", "POSITION")
            assertThat(rupsha.getValue("item_id")).isNotNull()
            assertThat(LocalDate.parse(rupsha.getValue("due_date"))).isBefore(LocalDate.now(clock.withZone(DemoMeetingSeeder.ZONE)))
            val instance = UUID.fromString(rupsha.getValue("workflow_instance_id"))
            val fired = "select count(*) from workflow_timer where instance_id = ? and kind = ? and state = 'FIRED'"
            assertThat(c.long(fired, instance, "DUE")).isOne()
            assertThat(c.long(fired, instance, "ESCALATE")).isOne()

            val plan = rowOf(c, DemoActionsSeeder.FUNDING_PLAN)
            assertThat(plan).containsEntry("status", "OPEN").containsEntry("source", "DIRECT").containsEntry("due_revisions", "1")
            assertThat(plan.getValue("due_date")).isNotEqualTo(plan.getValue("original_due_date"))

            val stress = rowOf(c, DemoActionsSeeder.STRESS_MODEL)
            assertThat(stress).containsEntry("status", "CANCELLED").containsEntry("cancel_kind", "RISK_ACCEPTED")
            assertThat(stress.getValue("closed_by")).isEqualTo(id(DemoMeetingSeeder.BOARD_MEMBER).toString())

            // The quarterly review's latest report: to close, overdue, open, cancelled since the last meeting.
            val sections =
                strings(
                    c,
                    "select r.section || ':' || a.title from actions_report_row r join actions_item a on a.id = r.action_id " +
                        "where r.report_id = (select id from actions_report where meeting_id = ? order by revision desc limit 1) " +
                        "order by r.position",
                    seeded.riskReview,
                )
            assertThat(sections).containsExactly(
                "TO_CLOSE:${DemoActionsSeeder.INSURANCE}",
                "OVERDUE:${DemoActionsSeeder.RUPSHA_BORROWINGS}",
                "OPEN:${DemoActionsSeeder.FUNDING_PLAN}",
                "CANCELLED:${DemoActionsSeeder.STRESS_MODEL}",
            )
        }
    }

    /** The action called [title], its columns as text. */
    private fun rowOf(
        c: java.sql.Connection,
        title: String,
    ): Map<String, String?> =
        c.prepareStatement("select * from actions_item where title = ?").use { s ->
            s.setString(1, title)
            s.executeQuery().use { rs ->
                assertThat(rs.next()).describedAs(title).isTrue()
                val meta = rs.metaData
                val row = (1..meta.columnCount).associate { meta.getColumnName(it) to rs.getString(it) }
                assertThat(rs.next()).describedAs("one $title").isFalse()
                row
            }
        }

    private fun strings(
        c: java.sql.Connection,
        sql: String,
        vararg args: Any,
    ): List<String> =
        c.prepareStatement(sql).use { s ->
            args.forEachIndexed { i, a -> s.setObject(i + 1, a) }
            s.executeQuery().use { rs -> generateSequence { if (rs.next()) rs.getString(1) else null }.toList() }
        }

    /** The E07 rows, to compare before and after a second run. */
    private fun e07Rows(): List<Long> =
        AppRoleJdbc.inTransaction(DemoTenant.ID) { c ->
            listOf("actions_item", "actions_entry", "actions_memo", "actions_due_request", "actions_report", "actions_report_row")
                .map { c.long("select count(*) from $it") } +
                c.long("select count(*) from workflow_timer where kind = 'ESCALATE'")
        }

    /** The E06 rows of the held meetings, to compare before and after a second run. */
    private fun e06Rows(seeded: DemoMeetingSeeder.Seeded): List<Long> =
        AppRoleJdbc.inTransaction(DemoTenant.ID) { c ->
            val both = arrayOf<Any>(seeded.heldRiskReview, seeded.heldCreditReview)
            listOf(
                "minutes_decision",
                "minutes_item_note",
                "minutes_resolution",
                "minutes_event",
                "minutes_comment",
                "minutes_approval",
            ).map { c.long("select count(*) from $it where meeting_id in (?, ?)", *both) } +
                c.long("select count(*) from minutes_extract")
        }

    private fun <T> asSystem(block: () -> T): T = TenantContext.call(DemoTenant.ID) { ActorContext.asSystem("demo-test") { block() } }

    /** The E05 rows of the three meetings, to compare before and after a second run. */
    private fun e05Rows(seeded: DemoMeetingSeeder.Seeded): List<Long> =
        AppRoleJdbc.inTransaction(DemoTenant.ID) { c ->
            val all = arrayOf<Any>(seeded.riskReview, seeded.creditMeeting, seeded.heldCreditReview)
            listOf(
                "meeting_rsvp",
                "meeting_rsvp_event",
                "meeting_attendance_event",
                "meeting_item_progress",
                "meeting_quorum_change",
                "meeting_quorum_snapshot",
                "meeting_declaration",
            ).map { c.long("select count(*) from $it where meeting_id in (?, ?, ?)", *all) }
        }

    private fun id(email: String): UUID = transactions.inTenant(DemoTenant.ID) { accounts.findByEmail(email)!!.id.value }

    private fun <T> asUser(
        user: UUID,
        block: () -> T,
    ): T =
        ActorContext.call(ActorScope(Actor.User(user), null)) {
            TenantContext.call(DemoTenant.ID) { transactions.inTenant(DemoTenant.ID, block) }
        }
}
