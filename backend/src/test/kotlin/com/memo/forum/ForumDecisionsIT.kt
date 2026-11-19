package com.memo.forum

import com.memo.common.concurrency.ConcurrentModificationException
import com.memo.common.context.Actor
import com.memo.common.context.ActorContext
import com.memo.common.context.ActorScope
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantTransactions
import com.memo.common.web.RefusedException
import com.memo.forum.internal.ForumInput
import com.memo.forum.internal.ForumMembers
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
import com.memo.workflow.ActionKind
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
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * A meeting's decision sent to its submission (ADR-0065; test plan T2 `ForumDecisionsIT`, `WorkflowForumsDetailsIT`):
 * decided with the resolution in the FORUM_DECIDED action's details, deferred off the agenda, referred to another
 * committee; a second release changes nothing, and a triage racing the release takes the submission's row in the same
 * order, so one of them wins and neither deadlocks.
 */
class ForumDecisionsIT : IntegrationTest() {
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

    @Autowired lateinit var decisions: ForumDecisions

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
    private lateinit var credit: UUID
    private lateinit var board: UUID

    @BeforeEach
    fun setUp() {
        tenant = registry.aTestTenant(transactions, settings, "decisions", mfaRequired = false)
        admin = accounts.anActiveUser(transactions, tenant, aPassword(), tenantAdmin = true)
        checker = accounts.anActiveUser(transactions, tenant, aPassword(), tenantAdmin = true)
        initiator = accounts.anActiveUser(transactions, tenant, aPassword())
        reviewer = accounts.anActiveUser(transactions, tenant, aPassword())
        director = accounts.anActiveUser(transactions, tenant, aPassword())
        secretary = accounts.anActiveUser(transactions, tenant, aPassword())
        val type =
            asUser(
                admin,
            ) { types.create("MC", "Management committee", ForumTypePreset.MANAGEMENT_COMMITTEE, publish = true) }.type.id
        credit = asUser(admin) { forums.create(ForumInput("credit-committee", "MCC", "Management Credit Committee", "Credit", type)) }.id
        board = asUser(admin) { forums.create(ForumInput("board", "BOD", "Board", "Board", type)) }.id
        for (forum in listOf(credit, board)) {
            asUser(admin) { members.add(forum, MemberInput(userId = secretary.id.value, role = ForumRole.SECRETARY)) }
        }
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

    /** A new item escalated to the credit committee, accepted and placed on a meeting held on [date]. */
    private fun placed(date: LocalDate = LocalDate.of(2026, 11, 19)): Triple<UUID, UUID, UUID> {
        val item = UUID.randomUUID()
        val started =
            asUser(initiator) { workflows.start(TestWorkflowSubject.TYPE, item, emptyList(), definitionKey = "credit-to-committee") }
        asUser(reviewer) { workflows.approve(checkNotNull(started.openTaskId)) }
        val queued = asUser(secretary) { submissions.queue(credit, emptySet()) }.single { it.subjectId == item }
        val meeting = UUID.randomUUID()
        asUser(secretary) { placements.place(credit, queued.id, meeting, date) }
        return Triple(item, queued.id, meeting)
    }

    private fun instance(item: UUID) = checkNotNull(asUser(initiator) { queries.viewOf(TestWorkflowSubject.TYPE, item) })

    @Test
    fun `a decision resumes the memo, with the resolution in the forum action's details, and only once`() {
        val (item, id, meeting) = placed()
        val details = mapOf("resolution" to "MCC/2026/001", "decisionId" to UUID.randomUUID().toString(), "meetingLabel" to "MCC 3/2026")
        val remark = "Approved with conditions. Resolution MCC/2026/001: The facility is approved. Conditions: 1. Collateral."
        val decided = asUser(secretary) { decisions.decide(id, OutcomeKind.APPROVED_WITH_CONDITIONS, remark, details) }
        assertThat(decided.status).isEqualTo(SubmissionStatus.DECIDED)
        assertThat(decided.meetingId).describedAs("decided at the meeting it was placed on").isEqualTo(meeting)
        Audit.assertAudited(tenant.id, "forum.submission.decided", id.toString(), secretary.id.value.toString())
        val view = instance(item)
        assertThat(view.currentAssignees).containsExactly(director.id.value)

        val action = asUser(initiator) { queries.history(view.instanceId) }.single { it.kind == ActionKind.FORUM_DECIDED }
        assertThat(action.remark).isEqualTo(remark)
        assertThat(action.details)
            .containsEntry("resolution", "MCC/2026/001")
            .containsEntry("meetingLabel", "MCC 3/2026")
            .containsEntry("decisionId", details["decisionId"])
            .containsEntry("outcome", "APPROVED_WITH_CONDITIONS")
            .containsEntry("step", "committee")

        refused("WRONG_STATE") { asUser(secretary) { decisions.decide(id, OutcomeKind.APPROVED, "Again", details) } }
        assertThatThrownBy { asUser(secretary) { decisions.decide(id, OutcomeKind.DEFERRED, null) } }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `the forum action's own details are never replaced by the caller's`() {
        val (item, id, _) = placed()
        asUser(
            secretary,
        ) { decisions.decide(id, OutcomeKind.REJECTED, "Outside risk appetite", mapOf("outcome" to "APPROVED", "step" to "x")) }
        val view = instance(item)
        assertThat(view.status).isEqualTo(InstanceStatus.REJECTED)
        val action = asUser(initiator) { queries.history(view.instanceId) }.single { it.kind == ActionKind.FORUM_DECIDED }
        assertThat(action.details).containsEntry("outcome", "REJECTED").containsEntry("step", "committee")
    }

    @Test
    fun `deferred at the meeting, the submission leaves the agenda with its target and the memo keeps waiting`() {
        val (item, id, _) = placed()
        assertThat(problem { decisions.deferAtMeeting(id, " ", null) }).isEqualTo("reason:REASON_REQUIRED")
        val target = LocalDate.of(2026, 12, 17)
        val deferred = asUser(secretary) { decisions.deferAtMeeting(id, "Waiting for the valuation", target) }
        assertThat(deferred.status).isEqualTo(SubmissionStatus.DEFERRED)
        assertThat(deferred.meetingId).isNull()
        assertThat(deferred.meetingDate).isNull()
        assertThat(deferred.targetDate).isEqualTo(target)
        Audit.assertAudited(tenant.id, "forum.submission.deferred", id.toString(), secretary.id.value.toString())
        val view = instance(item)
        assertThat(view.status).isEqualTo(InstanceStatus.RUNNING)
        assertThat(view.currentAssignees).isEmpty()
        refused("WRONG_STATE") { asUser(secretary) { decisions.deferAtMeeting(id, "Again", null) } }
        // Accepted again, it can be placed on the next meeting.
        val again = asUser(secretary) { triage.accept(credit, id, deferred.version, null) }
        assertThat(asUser(secretary) { placements.place(credit, again.id, UUID.randomUUID(), target) }.meetingDate).isEqualTo(target)
    }

    @Test
    fun `referred at the meeting, a linked submission waits at the other committee, whose decision resumes the memo`() {
        val (item, id, _) = placed()
        refused("SAME_FORUM") { asUser(secretary) { decisions.referAtMeeting(id, credit, "Wrong committee") } }
        assertThat(problem { decisions.referAtMeeting(id, UUID.randomUUID(), "Nowhere") }).isEqualTo("forumId:unknown")
        val referred = asUser(secretary) { decisions.referAtMeeting(id, board, "Above the committee's limit") }
        assertThat(referred.status).isEqualTo(SubmissionStatus.REFERRED)
        Audit.assertAudited(tenant.id, "forum.submission.referred", id.toString(), secretary.id.value.toString())
        val atBoard = asUser(secretary) { submissions.queue(board, emptySet()) }.single()
        assertThat(atBoard.previousId).isEqualTo(id)
        assertThat(atBoard.status).isEqualTo(SubmissionStatus.QUEUED)
        assertThat(atBoard.meetingId).isNull()
        assertThat(instance(item).status).isEqualTo(InstanceStatus.RUNNING)
        asUser(secretary) { decisions.decide(atBoard.id, OutcomeKind.APPROVED, "Approved by the board") }
        assertThat(instance(item).currentAssignees).containsExactly(director.id.value)
    }

    @Test
    fun `a release racing a triage of the same submission, one wins, the other is refused, nothing deadlocks`() {
        repeat(3) {
            val (item, id, _) = placed()
            val version = asUser(secretary) { submissions.get(credit, id) }.version
            val start = CountDownLatch(1)
            val pool = Executors.newFixedThreadPool(2)
            try {
                val release =
                    pool.submit<Result<Any>> {
                        start.await()
                        runCatching { asUser(secretary) { decisions.decide(id, OutcomeKind.APPROVED, "Approved") } }
                    }
                val triaged =
                    pool.submit<Result<Any>> {
                        start.await()
                        runCatching { asUser(secretary) { triage.returnForInformation(credit, id, version, "More information") } }
                    }
                start.countDown()
                val outcomes = listOf(release.get(30, TimeUnit.SECONDS), triaged.get(30, TimeUnit.SECONDS))
                assertThat(outcomes.count { it.isSuccess }).isEqualTo(1)
                val failure = outcomes.single { it.isFailure }.exceptionOrNull()
                assertThat(failure).isInstanceOfAny(RefusedException::class.java, ConcurrentModificationException::class.java)
                val status = asUser(secretary) { submissions.get(credit, id) }.status
                val expected = if (outcomes[0].isSuccess) InstanceStatus.RUNNING else InstanceStatus.RETURNED
                assertThat(status).isIn(SubmissionStatus.DECIDED, SubmissionStatus.RETURNED)
                assertThat(instance(item).status).isEqualTo(expected)
            } finally {
                pool.shutdownNow()
            }
        }
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
