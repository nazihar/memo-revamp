package com.memo.forum

import com.memo.common.authz.ForbiddenException
import com.memo.common.context.Actor
import com.memo.common.context.ActorContext
import com.memo.common.context.ActorScope
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantTransactions
import com.memo.common.web.RefusedException
import com.memo.forum.internal.ForumInput
import com.memo.forum.internal.ForumMembers
import com.memo.forum.internal.ForumNotFoundException
import com.memo.forum.internal.ForumRejectedException
import com.memo.forum.internal.ForumSubmissions
import com.memo.forum.internal.ForumTypePreset
import com.memo.forum.internal.ForumTypes
import com.memo.forum.internal.Forums
import com.memo.forum.internal.MemberInput
import com.memo.forum.internal.SubmissionTriage
import com.memo.identity.UserAccount
import com.memo.identity.UserAccounts
import com.memo.tenancy.TenantRegistry
import com.memo.tenancy.TenantSettings
import com.memo.testsupport.Audit
import com.memo.testsupport.IntegrationTest
import com.memo.testsupport.TestTenant
import com.memo.testsupport.TestWorkflowSubject
import com.memo.testsupport.aPassword
import com.memo.testsupport.aTestTenant
import com.memo.testsupport.anActiveUser
import com.memo.workflow.ForumOutcome
import com.memo.workflow.InstanceStatus
import com.memo.workflow.WorkflowQueries
import com.memo.workflow.WorkflowSeeding
import com.memo.workflow.Workflows
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/**
 * Submissions (test plan E02, T2; ADR-0058, ADR-0059): an item routed to a waiting forum step is queued for the forum in
 * the same transaction; the secretary accepts, defers, returns, declines or refers it; a decision resumes the item.
 */
class ForumSubmissionsIT : IntegrationTest() {
    @Autowired lateinit var registry: TenantRegistry

    @Autowired lateinit var settings: TenantSettings

    @Autowired lateinit var transactions: TenantTransactions

    @Autowired lateinit var accounts: UserAccounts

    @Autowired lateinit var types: ForumTypes

    @Autowired lateinit var forums: Forums

    @Autowired lateinit var members: ForumMembers

    @Autowired lateinit var submissions: ForumSubmissions

    @Autowired lateinit var triage: SubmissionTriage

    @Autowired lateinit var placements: SubmissionPlacements

    @Autowired lateinit var seeding: WorkflowSeeding

    @Autowired lateinit var workflows: Workflows

    @Autowired lateinit var queries: WorkflowQueries

    private lateinit var tenant: TestTenant
    private lateinit var admin: UserAccount
    private lateinit var checker: UserAccount
    private lateinit var initiator: UserAccount
    private lateinit var reviewer: UserAccount
    private lateinit var director: UserAccount
    private lateinit var secretary: UserAccount
    private lateinit var member: UserAccount
    private lateinit var credit: UUID
    private lateinit var board: UUID

    @BeforeEach
    fun setUp() {
        tenant = registry.aTestTenant(transactions, settings, "submissions", mfaRequired = false)
        admin = accounts.anActiveUser(transactions, tenant, aPassword(), tenantAdmin = true)
        checker = accounts.anActiveUser(transactions, tenant, aPassword(), tenantAdmin = true)
        initiator = accounts.anActiveUser(transactions, tenant, aPassword())
        reviewer = accounts.anActiveUser(transactions, tenant, aPassword())
        director = accounts.anActiveUser(transactions, tenant, aPassword())
        secretary = accounts.anActiveUser(transactions, tenant, aPassword())
        member = accounts.anActiveUser(transactions, tenant, aPassword())
        val type =
            asUser(
                admin,
            ) { types.create("MC", "Management committee", ForumTypePreset.MANAGEMENT_COMMITTEE, publish = true) }.type.id
        credit = asUser(admin) { forums.create(ForumInput("credit-committee", "MCC", "Management Credit Committee", "Credit", type)) }.id
        board = asUser(admin) { forums.create(ForumInput("board", "BOD", "Board", "Board", type)) }.id
        for (forum in listOf(credit, board)) {
            asUser(admin) { members.add(forum, MemberInput(userId = secretary.id.value, role = ForumRole.SECRETARY)) }
        }
        asUser(admin) { members.add(credit, MemberInput(userId = member.id.value)) }
        val body =
            """
            {"schemaVersion":2,"start":"review","steps":[
              {"id":"review","type":"approve","name":"Review","assignee":{"kind":"person","userId":"${reviewer.id.value}"},"next":"committee"},
              {"id":"committee","type":"escalate-to-forum","forum":"credit-committee","wait":true,"next":"director"},
              {"id":"director","type":"approve","name":"Director","assignee":{"kind":"person","userId":"${director.id.value}"},"next":"end"}
            ]}
            """
        asUser(admin) { seeding.submit("credit-to-committee", "Credit to committee", body) }
        asUser(checker) { seeding.approve("credit-to-committee") }
    }

    /** A new item started and reviewed: now waiting for the credit committee. */
    private fun escalated(): Pair<UUID, UUID> {
        val item = UUID.randomUUID()
        val started =
            asUser(initiator) { workflows.start(TestWorkflowSubject.TYPE, item, emptyList(), definitionKey = "credit-to-committee") }
        asUser(reviewer) { workflows.approve(checkNotNull(started.openTaskId)) }
        val queued = asUser(secretary) { submissions.queue(credit, emptySet()) }.single { it.subjectId == item }
        return item to queued.id
    }

    @Test
    fun `an escalated item is queued for the forum, waits, and the decision resumes it`() {
        val (item, id) = escalated()
        val row = asUser(secretary) { submissions.get(credit, id) }
        assertThat(row.status).isEqualTo(SubmissionStatus.QUEUED)
        assertThat(row.waits).isTrue()
        assertThat(row.submittedBy).isEqualTo(initiator.id.value)
        Audit.assertAudited(tenant.id, "forum.submission.created", id.toString(), null)
        val view = asUser(initiator) { queries.viewOf(TestWorkflowSubject.TYPE, item) }
        assertThat(view?.currentAssignees).isEmpty()
        assertThat(view?.status).isEqualTo(InstanceStatus.RUNNING)

        val accepted = asUser(secretary) { triage.accept(credit, id, row.version, null) }
        assertThat(accepted.status).isEqualTo(SubmissionStatus.ACCEPTED)
        Audit.assertAudited(tenant.id, "forum.submission.accepted", id.toString(), secretary.id.value.toString())

        asUser(secretary) { submissions.decide(id, ForumOutcome.APPROVED_WITH_CONDITIONS, "Subject to collateral") }
        Audit.assertAudited(tenant.id, "forum.submission.decided", id.toString(), secretary.id.value.toString())
        val after = asUser(initiator) { queries.viewOf(TestWorkflowSubject.TYPE, item) }
        assertThat(after?.currentAssignees).containsExactly(director.id.value)
    }

    @Test
    fun `return and decline need a reason and send the item back, and resubmitting queues it again, linked`() {
        val (item, id) = escalated()
        val row = asUser(secretary) { submissions.get(credit, id) }
        assertThat(problem { triage.returnForInformation(credit, id, row.version, " ") }).isEqualTo("reason:REASON_REQUIRED")
        val returned = asUser(secretary) { triage.returnForInformation(credit, id, row.version, "Add the valuation report") }
        assertThat(returned.status).isEqualTo(SubmissionStatus.RETURNED)
        Audit.assertAudited(tenant.id, "forum.submission.returned", id.toString(), secretary.id.value.toString())
        val view = asUser(initiator) { queries.viewOf(TestWorkflowSubject.TYPE, item) }
        assertThat(view?.status).isEqualTo(InstanceStatus.RETURNED)

        asUser(initiator) { workflows.resubmit(view!!.instanceId, emptyList()) }
        val again = asUser(secretary) { submissions.queue(credit, emptySet()) }
        assertThat(again).isEmpty()
        // The resubmitted round starts with the review again; after it, a new submission links the first.
        val reviewTask = asUser(initiator) { queries.viewOf(TestWorkflowSubject.TYPE, item) }!!.openTaskId!!
        asUser(reviewer) { workflows.approve(reviewTask) }
        val second = asUser(secretary) { submissions.queue(credit, emptySet()) }.single()
        assertThat(second.previousId).isEqualTo(id)

        val declined = asUser(secretary) { triage.decline(credit, second.id, second.version, "A board matter") }
        assertThat(declined.status).isEqualTo(SubmissionStatus.DECLINED)
        Audit.assertAudited(tenant.id, "forum.submission.declined", second.id.toString(), secretary.id.value.toString())
        assertThat(asUser(initiator) { queries.viewOf(TestWorkflowSubject.TYPE, item) }?.status).isEqualTo(InstanceStatus.RETURNED)
    }

    @Test
    fun `a referral moves the waiting item to another forum, whose decision resumes it`() {
        val (item, id) = escalated()
        val row = asUser(secretary) { submissions.get(credit, id) }
        val referred = asUser(secretary) { triage.refer(credit, id, row.version, board, "Above the committee's limit") }
        assertThat(referred.status).isEqualTo(SubmissionStatus.REFERRED)
        Audit.assertAudited(tenant.id, "forum.submission.referred", id.toString(), secretary.id.value.toString())
        val atBoard = asUser(secretary) { submissions.queue(board, emptySet()) }.single()
        assertThat(atBoard.previousId).isEqualTo(id)
        assertThat(atBoard.waits).isTrue()
        asUser(secretary) { submissions.decide(atBoard.id, ForumOutcome.REJECTED, "Outside risk appetite") }
        assertThat(asUser(initiator) { queries.viewOf(TestWorkflowSubject.TYPE, item) }?.status).isEqualTo(InstanceStatus.REJECTED)
    }

    @Test
    fun `defer keeps the item waiting, a withdrawal closes the submission, and only officers see the queue`() {
        val (item, id) = escalated()
        val row = asUser(secretary) { submissions.get(credit, id) }
        val deferred = asUser(secretary) { triage.defer(credit, id, row.version, "Next quarter's meeting", null) }
        assertThat(deferred.status).isEqualTo(SubmissionStatus.DEFERRED)
        Audit.assertAudited(tenant.id, "forum.submission.deferred", id.toString(), secretary.id.value.toString())
        refused("WRONG_STATE") { asUser(secretary) { triage.defer(credit, id, deferred.version, "Again", null) } }

        assertThatThrownBy { asUser(member) { submissions.queue(credit, emptySet()) } }.isInstanceOf(ForbiddenException::class.java)
        assertThatThrownBy { asUser(member) { submissions.get(credit, id) } }.isInstanceOf(ForumNotFoundException::class.java)
        assertThat(asUser(initiator) { submissions.get(credit, id) }.id).isEqualTo(id)

        val view = asUser(initiator) { queries.viewOf(TestWorkflowSubject.TYPE, item) }!!
        asUser(initiator) { workflows.withdraw(view.instanceId, "No longer needed") }
        assertThat(asUser(secretary) { submissions.get(credit, id) }.status).isEqualTo(SubmissionStatus.WITHDRAWN)
        Audit.assertAudited(tenant.id, "forum.submission.withdrawn", id.toString(), null)
    }

    @Test
    fun `a submission sits on one agenda at a time, a queued one is accepted first, and unplacing returns it to the tray`() {
        val (_, id) = escalated()
        val meeting = UUID.randomUUID()
        val date = LocalDate.now(ZoneOffset.UTC).plusDays(20)
        val placed = asUser(secretary) { placements.place(credit, id, meeting, date) }
        assertThat(placed.status).isEqualTo(SubmissionStatus.ACCEPTED)
        assertThat(placed.meetingId to placed.meetingDate).isEqualTo(meeting to date)
        Audit.assertAudited(tenant.id, "forum.submission.accepted", id.toString(), secretary.id.value.toString())
        Audit.assertAudited(tenant.id, "forum.submission.placed", id.toString(), secretary.id.value.toString())
        assertThat(asUser(secretary) { placements.placed(credit) }).containsExactly(id)
        assertThat(asUser(initiator) { submissions.get(credit, id) }.meetingDate).isEqualTo(date)
        refused("ONE_PLACEMENT") { asUser(secretary) { placements.place(credit, id, UUID.randomUUID(), date) } }
        assertThatThrownBy { asUser(secretary) { placements.place(board, id, meeting, date) } }
            .isInstanceOf(ForumNotFoundException::class.java)

        val unplaced = asUser(secretary) { placements.unplace(id) }
        assertThat(unplaced?.meetingId).isNull()
        assertThat(unplaced?.status).isEqualTo(SubmissionStatus.ACCEPTED)
        Audit.assertAudited(tenant.id, "forum.submission.unplaced", id.toString(), secretary.id.value.toString())
        assertThat(asUser(secretary) { placements.placed(credit) }).isEmpty()
        assertThat(asUser(secretary) { placements.unplace(id) }?.version).isEqualTo(unplaced?.version)

        val row = asUser(secretary) { submissions.get(credit, id) }
        asUser(secretary) { triage.decline(credit, id, row.version, "A board matter") }
        refused("SUBMISSION_STATE") { asUser(secretary) { placements.place(credit, id, meeting, date) } }
    }

    @Test
    fun `an escalation to a dissolved forum refuses the action that leads there`() {
        val forum = asUser(admin) { forums.get(credit) }
        asUser(admin) { forums.dissolve(credit, forum.version, null, "Merged") }
        val item = UUID.randomUUID()
        val started =
            asUser(initiator) { workflows.start(TestWorkflowSubject.TYPE, item, emptyList(), definitionKey = "credit-to-committee") }
        refused("FORUM_INACTIVE") { asUser(reviewer) { workflows.approve(checkNotNull(started.openTaskId)) } }
    }

    private fun refused(
        reason: String,
        block: () -> Any,
    ) = assertThatThrownBy { block() }.isInstanceOfSatisfying(RefusedException::class.java) { assertThat(it.reason).isEqualTo(reason) }

    private fun problem(block: () -> Any): String {
        val rejected =
            runCatching { asUser(secretary, block) }.exceptionOrNull() as? ForumRejectedException ?: error("expected a rejection")
        return rejected.problems.joinToString { "${it.field}:${it.code}" }
    }

    private fun <T> asUser(
        user: UserAccount,
        block: () -> T,
    ): T =
        ActorContext.call(ActorScope(Actor.User(user.id.value), null)) {
            TenantContext.call(tenant.id) { transactions.inTenant(tenant.id, block) }
        }
}
