package com.memo.meeting

import com.memo.common.concurrency.ConcurrentModificationException
import com.memo.common.context.Actor
import com.memo.common.context.ActorContext
import com.memo.common.context.ActorScope
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantTransactions
import com.memo.common.web.RefusedException
import com.memo.forum.ForumRole
import com.memo.forum.SubmissionStatus
import com.memo.forum.internal.ForumInput
import com.memo.forum.internal.ForumMembers
import com.memo.forum.internal.ForumSubmissions
import com.memo.forum.internal.ForumTypePreset
import com.memo.forum.internal.ForumTypes
import com.memo.forum.internal.Forums
import com.memo.forum.internal.MemberInput
import com.memo.forum.internal.SubmissionTriage
import com.memo.identity.UserAccount
import com.memo.identity.UserAccounts
import com.memo.meeting.internal.AgendaDraftViews
import com.memo.meeting.internal.AgendaItemRequest
import com.memo.meeting.internal.AgendaItems
import com.memo.meeting.internal.AgendaItemsController
import com.memo.meeting.internal.AgendaMoves
import com.memo.meeting.internal.AgendaOrigin
import com.memo.meeting.internal.AgendaStart
import com.memo.meeting.internal.ItemInput
import com.memo.meeting.internal.ItemKind
import com.memo.meeting.internal.MeetingInput
import com.memo.meeting.internal.MeetingLifecycle
import com.memo.meeting.internal.MeetingNotFoundException
import com.memo.meeting.internal.MeetingPlace
import com.memo.meeting.internal.MeetingReads
import com.memo.meeting.internal.MoveInput
import com.memo.meeting.internal.TimesInput
import com.memo.tenancy.TenantRegistry
import com.memo.tenancy.TenantSettings
import com.memo.testsupport.AppRoleJdbc
import com.memo.testsupport.AppRoleJdbc.long
import com.memo.testsupport.IntegrationTest
import com.memo.testsupport.TestTenant
import com.memo.testsupport.TestWorkflowSubject
import com.memo.testsupport.aPassword
import com.memo.testsupport.aTestTenant
import com.memo.testsupport.anActiveUser
import com.memo.workflow.WorkflowSeeding
import com.memo.workflow.Workflows
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Two secretaries at once (test plan E03, T2; threat model T2): one submission placed on two agendas in parallel lands
 * on exactly one; two changes made from the same version keep only the first (412 for the other); the same
 * `Idempotency-Key` twice adds one item.
 */
class AgendaConcurrencyIT : IntegrationTest() {
    @Autowired lateinit var registry: TenantRegistry

    @Autowired lateinit var settings: TenantSettings

    @Autowired lateinit var transactions: TenantTransactions

    @Autowired lateinit var accounts: UserAccounts

    @Autowired lateinit var types: ForumTypes

    @Autowired lateinit var forums: Forums

    @Autowired lateinit var members: ForumMembers

    @Autowired lateinit var submissions: ForumSubmissions

    @Autowired lateinit var seeding: WorkflowSeeding

    @Autowired lateinit var workflows: Workflows

    @Autowired lateinit var lifecycle: MeetingLifecycle

    @Autowired lateinit var starts: AgendaStart

    @Autowired lateinit var items: AgendaItems

    @Autowired lateinit var moves: AgendaMoves

    @Autowired lateinit var views: AgendaDraftViews

    @Autowired lateinit var controller: AgendaItemsController

    @Autowired lateinit var triage: SubmissionTriage

    @Autowired lateinit var reads: MeetingReads

    private lateinit var tenant: TestTenant
    private lateinit var admin: UserAccount
    private lateinit var secretary: UserAccount
    private lateinit var other: UserAccount
    private lateinit var initiator: UserAccount
    private lateinit var credit: UUID

    @BeforeEach
    fun setUp() {
        tenant = registry.aTestTenant(transactions, settings, "agendaraces", mfaRequired = false)
        admin = accounts.anActiveUser(transactions, tenant, aPassword(), tenantAdmin = true)
        val checker = accounts.anActiveUser(transactions, tenant, aPassword(), tenantAdmin = true)
        secretary = accounts.anActiveUser(transactions, tenant, aPassword())
        other = accounts.anActiveUser(transactions, tenant, aPassword())
        initiator = accounts.anActiveUser(transactions, tenant, aPassword())
        val type =
            asUser(
                admin,
            ) { types.create("MC", "Management committee", ForumTypePreset.MANAGEMENT_COMMITTEE, publish = true) }.type.id
        credit = asUser(admin) { forums.create(ForumInput("credit-committee", "MCC", "Management Credit Committee", "Credit", type)) }.id
        for (account in listOf(secretary, other)) {
            asUser(admin) { members.add(credit, MemberInput(userId = account.id.value, role = ForumRole.SECRETARY)) }
        }
        val body =
            """
            {"schemaVersion":2,"start":"committee","steps":[
              {"id":"committee","type":"escalate-to-forum","forum":"credit-committee","wait":true,"next":"end"}
            ]}
            """
        asUser(admin) { seeding.submit("straight-to-committee", "Straight to the committee", body) }
        asUser(checker) { seeding.approve("straight-to-committee") }
    }

    @Test
    fun `one submission placed on two agendas at once lands on exactly one`() {
        val item = UUID.randomUUID()
        asUser(initiator) { workflows.start(TestWorkflowSubject.TYPE, item, emptyList(), definitionKey = "straight-to-committee") }
        val submission = asUser(secretary) { submissions.queue(credit, emptySet()) }.single { it.subjectId == item }.id
        val first = meeting(30)
        val second = meeting(31)

        val outcomes =
            parallel(
                { asUser(secretary) { place(first, submission) } },
                { asUser(other) { place(second, submission) } },
            )

        assertThat(outcomes.count { it == null }).isEqualTo(1)
        assertThat(outcomes.filterNotNull()).allSatisfy {
            assertThat(it is ConcurrentModificationException || (it is RefusedException && it.reason == "ONE_PLACEMENT")).isTrue()
        }
        AppRoleJdbc.inTransaction(tenant.id) { c ->
            assertThat(c.long("select count(*) from meeting_item where submission_id = ? and removed_at is null", submission)).isEqualTo(1)
        }
        val placed = asUser(secretary) { submissions.get(credit, submission) }
        assertThat(placed.meetingId).isIn(first, second)
    }

    @Test
    fun `two changes from the same version keep the first, and the second gets 412`() {
        val meeting = meeting(30)
        val draft = asUser(secretary) { views.draft(meeting) }
        val opening =
            draft.sections
                .first()
                .items
                .map { it.id }

        val outcomes =
            parallel(
                { asUser(secretary) { moves.move(meeting, draft.version, MoveInput(itemId = opening[0], toPosition = 3)) } },
                { asUser(other) { moves.move(meeting, draft.version, MoveInput(itemId = opening[3], toPosition = 0)) } },
            )

        assertThat(outcomes.count { it == null }).isEqualTo(1)
        assertThat(outcomes.filterNotNull().single()).isInstanceOf(ConcurrentModificationException::class.java)
        assertThat(asUser(secretary) { views.draft(meeting) }.version).isEqualTo(draft.version + 1)
    }

    @Test
    fun `the same idempotency key twice adds one item`() {
        val meeting = meeting(30)
        val version = asUser(secretary) { views.draft(meeting) }.version
        val key = UUID.randomUUID().toString()
        val body = AgendaItemRequest(ItemKind.ITEM, title = "Strategy update", minutes = 20)

        val first = call(secretary) { controller.add(meeting, "\"$version\"", key, body) }
        val replay = call(secretary) { controller.add(meeting, "\"$version\"", key, body) }

        assertThat(first.statusCode).isEqualTo(HttpStatus.CREATED)
        assertThat(replay.statusCode).isEqualTo(HttpStatus.CREATED)
        assertThat(replay.body?.version).isEqualTo(first.body?.version)
        val titles = asUser(secretary) { views.draft(meeting) }.sections.flatMap { it.items }.map { it.title }
        assertThat(titles.count { it == "Strategy update" }).isEqualTo(1)
    }

    /**
     * The lock order (design E03 §1.8, as built in T3): every path that changes a placement takes the submission's row
     * before the agenda's, as a return does (the submission, then the agenda through `ForumSubmissionChanged`). Each round
     * races the two once; a deadlock would surface as an exception other than the refusals of a lost race.
     */
    @Test
    fun `a return racing a placement of the same submission completes without a deadlock`() {
        repeat(ROUNDS) { round ->
            val submission = submission()
            val meeting = meeting(30L + round)

            val outcomes =
                parallel(
                    { asUser(secretary) { place(meeting, submission) } },
                    { asUser(other) { returned(submission) } },
                )

            assertOnlyRefusals(outcomes)
            val after = asUser(secretary) { submissions.get(credit, submission) }
            if (after.status == SubmissionStatus.RETURNED) {
                assertThat(liveItem(submission) to after.meetingId).isEqualTo(null to null)
            } else {
                assertThat(liveItem(submission)).isNotNull()
                assertThat(after.meetingId).isEqualTo(meeting)
            }
        }
    }

    @Test
    fun `a return racing the removal of its item or the meeting's cancellation completes without a deadlock`() {
        repeat(ROUNDS) { round ->
            val submission = submission()
            val meeting = meeting(30L + round)
            asUser(secretary) { place(meeting, submission) }
            val item = checkNotNull(liveItem(submission))
            val version = asUser(secretary) { views.draft(meeting) }.version

            val removal =
                parallel(
                    { asUser(secretary) { items.remove(meeting, item, version) } },
                    { asUser(other) { returned(submission) } },
                )

            assertOnlyRefusals(removal)
            assertThat(liveItem(submission)).isNull()
            assertThat(asUser(secretary) { submissions.get(credit, submission) }.meetingId).isNull()
        }
        repeat(ROUNDS) { round ->
            val submission = submission()
            val meeting = meeting(50L + round)
            asUser(secretary) { place(meeting, submission) }
            val current = asUser(secretary) { reads.get(meeting) }

            val cancellation =
                parallel(
                    { asUser(secretary) { lifecycle.cancel(meeting, current.version, "No quorum") } },
                    { asUser(other) { returned(submission) } },
                )

            assertOnlyRefusals(cancellation)
            assertThat(liveItem(submission)).isNull()
            assertThat(asUser(secretary) { submissions.get(credit, submission) }.meetingId).isNull()
        }
    }

    /** A new submission of the committee, queued. */
    private fun submission(): UUID {
        val subject = UUID.randomUUID()
        asUser(initiator) { workflows.start(TestWorkflowSubject.TYPE, subject, emptyList(), definitionKey = "straight-to-committee") }
        return asUser(secretary) { submissions.queue(credit, emptySet()) }.single { it.subjectId == subject }.id
    }

    private fun returned(submission: UUID) =
        triage.returnForInformation(credit, submission, submissions.get(credit, submission).version, "Add the valuation")

    private fun liveItem(submission: UUID): UUID? =
        AppRoleJdbc.inTransaction(tenant.id) { c ->
            c.prepareStatement("select id from meeting_item where submission_id = ? and removed_at is null").use { st ->
                st.setObject(1, submission)
                st.executeQuery().use { if (it.next()) it.getObject(1, UUID::class.java) else null }
            }
        }

    /** Only the refusals a lost race may give: a stale version, a submission no longer open, an item or a meeting gone. */
    private fun assertOnlyRefusals(outcomes: List<Throwable?>) {
        assertThat(outcomes.filterNotNull()).allSatisfy {
            assertThat(
                it is ConcurrentModificationException ||
                    it is MeetingNotFoundException ||
                    (it is RefusedException && it.reason in setOf("SUBMISSION_STATE", "WRONG_STATE", "FROZEN")),
            ).describedAs("unexpected outcome %s", it).isTrue()
        }
    }

    private fun place(
        meeting: UUID,
        submission: UUID,
    ) = items.add(meeting, views.draft(meeting).version, ItemInput(ItemKind.SUBMISSION, submissionId = submission))

    /** Runs both blocks at once; each outcome is null on success or the exception it threw. */
    private fun parallel(vararg blocks: () -> Any?): List<Throwable?> {
        val pool = Executors.newFixedThreadPool(blocks.size)
        val go = CountDownLatch(1)
        try {
            val futures =
                blocks.map { block ->
                    pool.submit<Throwable?> {
                        go.await()
                        runCatching { block() }.exceptionOrNull()
                    }
                }
            go.countDown()
            return futures.map { it.get(WAIT_SECONDS, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
    }

    private fun meeting(days: Long): UUID {
        val at = LocalDate.now(ZoneOffset.UTC).plusDays(days).atTime(LocalTime.of(10, 0))
        return asUser(secretary) {
            val created =
                lifecycle.create(
                    credit,
                    MeetingInput(MeetingKind.ORDINARY, MeetingPlace(null, MeetingMode.IN_PERSON, "Board room", null, null), null),
                    TimesInput(at, at.plusHours(2), "Asia/Dhaka"),
                )
            starts.start(created.id, AgendaOrigin.TEMPLATE, null)
            created.id
        }
    }

    private fun <T> call(
        account: UserAccount,
        block: () -> T,
    ): T = ActorContext.call(ActorScope(Actor.User(account.id.value), null)) { TenantContext.call(tenant.id, block) }

    private fun <T> asUser(
        account: UserAccount,
        block: () -> T,
    ): T = call(account) { transactions.inTenant(tenant.id, block) }

    private companion object {
        const val WAIT_SECONDS = 60L
        const val ROUNDS = 6
    }
}
