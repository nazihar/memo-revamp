package com.memo.forum

import com.memo.common.context.ActorContext
import com.memo.common.tenant.TenantDsl
import com.memo.forum.internal.ForumInput
import com.memo.forum.internal.ForumMembers
import com.memo.forum.internal.ForumTypePreset
import com.memo.forum.internal.ForumTypes
import com.memo.forum.internal.Forums
import com.memo.forum.internal.MemberInput
import com.memo.forum.internal.SubmissionReminders
import com.memo.identity.UserAccount
import com.memo.jooq.tables.references.FORUM_SUBMISSION
import com.memo.memo.internal.MemoTypeFields
import com.memo.memo.internal.MemoTypes
import com.memo.memo.internal.RoutePolicy
import com.memo.testsupport.Audit
import com.memo.testsupport.MemoIntegrationTest
import com.memo.testsupport.Person
import com.memo.testsupport.aPassword
import com.memo.testsupport.anActiveUser
import com.memo.testsupport.sendJson
import com.memo.workflow.WorkflowSeeding
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import tools.jackson.databind.JsonNode
import java.time.OffsetDateTime
import java.util.UUID

/**
 * The memo side of submissions (test plan E02, T3; ADR-0059): a committee's chair and secretaries read a memo while it is
 * in their queue and lose that access when it leaves; clearance still applies; the memo shows which committee has it; the
 * note sheet names the committee that returned it; a paper left untriaged is chased once.
 */
class ForumReadersIT : MemoIntegrationTest() {
    @Autowired lateinit var forumTypes: ForumTypes

    @Autowired lateinit var forums: Forums

    @Autowired lateinit var members: ForumMembers

    @Autowired lateinit var seeding: WorkflowSeeding

    @Autowired lateinit var memoTypes: MemoTypes

    @Autowired lateinit var reminders: SubmissionReminders

    @Autowired lateinit var tenantDsl: TenantDsl

    private lateinit var checker: UserAccount
    private lateinit var alice: Person
    private lateinit var reviewer: Person
    private lateinit var secretary: Person
    private lateinit var chair: Person
    private lateinit var member: Person
    private lateinit var stranger: Person
    private lateinit var credit: UUID
    private lateinit var memoType: UUID

    @BeforeEach
    fun committee() {
        checker = accounts.anActiveUser(transactions, tenant, aPassword(), tenantAdmin = true)
        alice = person(clearance = 1)
        reviewer = person(clearance = 1)
        secretary = person(clearance = 1)
        chair = person()
        member = person(clearance = 1)
        stranger = person(clearance = 1)
        val type = asUser(admin) { forumTypes.create("MC", "Management committee", ForumTypePreset.MANAGEMENT_COMMITTEE, publish = true) }
        credit = asUser(admin) { forums.create(ForumInput(KEY, "MCC", NAME, "Credit", type.type.id)) }.id
        asUser(admin) { members.add(credit, MemberInput(userId = secretary.id, role = ForumRole.SECRETARY)) }
        asUser(admin) { members.add(credit, MemberInput(userId = chair.id, role = ForumRole.CHAIR)) }
        asUser(admin) { members.add(credit, MemberInput(userId = member.id)) }
        val body =
            """
            {"schemaVersion":2,"start":"review","steps":[
              {"id":"review","type":"approve","name":"Review","assignee":{"kind":"person","userId":"${reviewer.id}"},"next":"committee"},
              {"id":"committee","type":"escalate-to-forum","forum":"$KEY","wait":true,"next":"end"}
            ]}
            """
        asUser(admin) { seeding.submit("to-committee", "To the credit committee", body) }
        asUser(checker) { seeding.approve("to-committee") }
        memoType =
            asUser(admin) {
                memoTypes.create(MemoTypeFields("Credit paper", null, false, true, "to-committee", RoutePolicy.FIXED))
            }.id
    }

    /** A memo of alice's at [rank], submitted and reviewed: now waiting for the credit committee. */
    private fun escalated(rank: Int = 0): String {
        val level = levels()[rank].id
        val id = create(alice.session, """{"subject":"Loan","typeId":"$memoType","classificationId":"$level"}""").json()["id"].asString()
        uploadDocument(id, alice.session)
        assertThat(post("/api/v1/memos/$id/submit", alice.session)).hasStatusOk()
        val task = get(id, reviewer.session).json()["myTask"]["id"].asString()
        assertThat(post("/api/v1/tasks/$task/approve", reviewer.session)).hasStatusOk()
        return id
    }

    private fun queue(person: Person): JsonNode = read("/api/v1/forums/$credit/submissions", person.session).json()

    @Test
    fun `the committee's officers read the memo while it waits, the memo says where it is, and a return names the committee`() {
        val id = escalated()
        assertThat(get(id, secretary.session)).hasStatusOk()
        assertThat(get(id, chair.session)).hasStatusOk()
        assertThat(get(id, member.session)).hasStatus(HttpStatus.NOT_FOUND)
        Audit.assertAudited(tenant.id, "memo.reader.forum_added", id)

        assertThat(get(id, alice.session).json()["withForum"].asString()).isEqualTo(NAME)
        val sent = read("/api/v1/memos?box=SENT", alice.session).json()["items"]
        assertThat(sent[0]["withForum"].asString()).isEqualTo(NAME)
        val panel = read("/api/v1/memos/$id/forum-submissions", alice.session).json()
        assertThat(panel[0]["forumName"].asString()).isEqualTo(NAME)
        assertThat(panel[0]["memo"]["subject"].asString()).isEqualTo("Loan")
        assertThat(read("/api/v1/memos/$id/forum-submissions", stranger.session)).hasStatus(HttpStatus.NOT_FOUND)
        val submission = queue(secretary)[0]
        assertThat(read("/api/v1/forums/$credit/submissions/${submission["id"].asString()}", alice.session)).hasStatusOk()

        // A secretary appointed while the paper waits reads it too.
        val second = person(clearance = 1)
        asUser(admin) { members.add(credit, MemberInput(userId = second.id, role = ForumRole.SECRETARY)) }
        assertThat(get(id, second.session)).hasStatusOk()

        val returned =
            mvc.sendJson(
                HttpMethod.POST,
                tenant,
                "/api/v1/forums/$credit/submissions/${submission["id"].asString()}/return",
                secretary.session,
                """{"reason":"Add the valuation report"}""",
                mapOf("If-Match" to "\"${submission["version"].asLong()}\"", "Idempotency-Key" to UUID.randomUUID().toString()),
            )
        assertThat(returned).hasStatusOk()
        assertThat(get(id, chair.session)).hasStatus(HttpStatus.NOT_FOUND)
        assertThat(get(id, secretary.session)).hasStatus(HttpStatus.NOT_FOUND)
        Audit.assertAudited(tenant.id, "memo.reader.forum_retired", id)

        val memo = get(id, alice.session).json()
        assertThat(memo["status"].asString()).isEqualTo("RETURNED")
        assertThat(memo.absent("withForum")).isTrue()
        val entry = read("/api/v1/memos/$id/notes", alice.session).json()["entries"].single { it["committee"]?.isNull == false }
        assertThat(entry["committee"].asString()).isEqualTo(NAME)
        assertThat(entry["committeeOutcome"].asString()).isEqualTo("RETURNED")
        assertThat(entry["text"].asString()).isEqualTo("Add the valuation report")
        assertThat(entry["author"]["id"].asString()).isEqualTo(secretary.id.toString())
    }

    @Test
    fun `clearance still applies to committee readers, and the queue hides a memo its reader cannot read`() {
        val id = escalated(rank = 1)
        assertThat(get(id, secretary.session)).hasStatusOk()
        assertThat(get(id, chair.session)).hasStatus(HttpStatus.NOT_FOUND)
        assertThat(queue(secretary)[0]["memo"]["subject"].asString()).isEqualTo("Loan")
        assertThat(queue(chair)[0].absent("memo")).isTrue()
    }

    @Test
    fun `a paper left untriaged past the type's working days is chased once`() {
        escalated()
        assertThat(remind()).isZero()
        val submission = UUID.fromString(queue(secretary)[0]["id"].asString())
        asUser(admin) {
            tenantDsl
                .current()
                .update(FORUM_SUBMISSION)
                .set(FORUM_SUBMISSION.SUBMITTED_AT, OffsetDateTime.now().minusDays(BACKDATE_DAYS))
                .where(FORUM_SUBMISSION.ID.eq(submission))
                .execute()
        }
        assertThat(remind()).isEqualTo(1)
        assertThat(remind()).isZero()
        Audit.assertAudited(tenant.id, "forum.submission.reminded", submission.toString())
    }

    private fun remind(): Int = ActorContext.asSystem("test") { transactions.inTenant(tenant.id) { reminders.remindWaiting() } }

    private companion object {
        const val KEY = "credit-committee"
        const val NAME = "Management Credit Committee"
        const val BACKDATE_DAYS = 30L
    }
}
