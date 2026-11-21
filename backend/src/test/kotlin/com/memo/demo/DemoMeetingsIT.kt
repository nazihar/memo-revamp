package com.memo.demo

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
import com.memo.meeting.LineAccess
import com.memo.meeting.MeetingSeeding
import com.memo.meeting.MeetingStatus
import com.memo.meeting.internal.AgendaDraftViews
import com.memo.meeting.internal.AgendaNoticeScheduler
import com.memo.meeting.internal.ItemFlag
import com.memo.meeting.internal.ItemKind
import com.memo.meeting.internal.Lateness
import com.memo.meeting.internal.MeetingReads
import com.memo.meeting.internal.MemberAgendas
import com.memo.meeting.internal.PaperFlag
import com.memo.meeting.internal.PublishedLate
import com.memo.meeting.internal.invite.InvitationScheduler
import com.memo.memo.MemoAccessSeeding
import com.memo.memo.MemoSeeding
import com.memo.memo.internal.Memos
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
import java.util.UUID

/**
 * Phase 4 E03 demo data (T9): the Board Risk Committee's review published twice (a replaced paper, a late paper the
 * chair agreed, a consent item lifted by Demo Director, an item whose papers only the chair reads, an in-camera section
 * and a guest), and the credit committee's meeting with the BDT 15 million proposal placed from the queue. Once only.
 */
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

    @Autowired lateinit var uploads: FileUploads

    @Autowired lateinit var lifecycle: com.memo.identity.UserLifecycle

    @Autowired lateinit var clock: Clock

    @Autowired lateinit var meetingSeeding: MeetingSeeding

    @Autowired lateinit var agendaSeeding: AgendaSeeding

    @Autowired lateinit var forumSeeding: ForumSeeding

    @Autowired lateinit var reads: MeetingReads

    @Autowired lateinit var drafts: AgendaDraftViews

    @Autowired lateinit var agendas: MemberAgendas

    @Autowired lateinit var submissions: ForumSubmissions

    @Autowired lateinit var memos: Memos

    @Autowired lateinit var notices: AgendaNoticeScheduler

    @Autowired lateinit var invitations: InvitationScheduler

    @Test
    fun `the risk review is published twice and the credit committee has the proposal from its queue, once`() {
        val password = aPassword()
        // Test contexts share the scheduler table: the notices and invitations the seed causes are not scheduled here.
        notices.jobs = false
        invitations.jobs = false
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
            val seeder =
                DemoMeetingSeeder(
                    meetingSeeding,
                    agendaSeeding,
                    memoSeeding,
                    memoAccess,
                    forumSeeding,
                    forumDirectory,
                    accounts,
                    transactions,
                    clock,
                    password,
                )
            val seeded = checkNotNull(seeder.seed())
            assertRiskReview(seeded.riskReview)
            assertCreditMeeting(seeded.creditMeeting)

            // Fills gaps only: a second run finds both meetings and adds nothing.
            assertThat(seeder.seed()).isEqualTo(seeded)
            AppRoleJdbc.inTransaction(DemoTenant.ID) { c ->
                listOf(DemoMeetingSeeder.RISK_TITLE, DemoMeetingSeeder.CREDIT_TITLE).forEach {
                    assertThat(c.long("select count(*) from meeting where title = ?", it)).describedAs(it).isOne()
                }
                assertThat(c.long("select count(*) from memo where subject = ?", DemoMeetingSeeder.CREDIT_SUBJECT)).isOne()
            }
        } finally {
            notices.jobs = true
            invitations.jobs = true
        }
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

        // The director reads revision 2, has seen revision 1, and so sees what changed.
        val agenda = asUser(director) { agendas.agenda(meeting, null) }
        assertThat(agenda.revision to agenda.latestRevision).isEqualTo(2 to 2)
        assertThat(agenda.note).isEqualTo("Updated risk appetite paper; a late liquidity addendum agreed by the chair")
        assertThat(agenda.lastSeenRevision).isEqualTo(1)
        assertThat(agenda.whatChanged).isNotNull()
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

    private fun id(email: String): UUID = transactions.inTenant(DemoTenant.ID) { accounts.findByEmail(email)!!.id.value }

    private fun <T> asUser(
        user: UUID,
        block: () -> T,
    ): T =
        ActorContext.call(ActorScope(Actor.User(user), null)) {
            TenantContext.call(DemoTenant.ID) { transactions.inTenant(DemoTenant.ID, block) }
        }
}
