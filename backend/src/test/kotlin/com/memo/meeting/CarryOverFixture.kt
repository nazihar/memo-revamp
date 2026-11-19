package com.memo.meeting

import com.memo.forum.internal.ForumSubmissions
import com.memo.forum.internal.SubmissionTriage
import com.memo.identity.UserAccount
import com.memo.meeting.internal.DeclarationRow
import com.memo.meeting.internal.DeclarationStore
import com.memo.meeting.internal.ItemInput
import com.memo.meeting.internal.ItemKind
import com.memo.meeting.internal.MeetingRow
import com.memo.memo.internal.MemoTypeFields
import com.memo.memo.internal.MemoTypes
import com.memo.memo.internal.RoutePolicy
import com.memo.testsupport.AppRoleJdbc
import com.memo.testsupport.Person
import com.memo.testsupport.aPassword
import com.memo.testsupport.anActiveUser
import com.memo.workflow.WorkflowSeeding
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import java.util.UUID

/**
 * The Board Risk Committee of [DeclarationFixture] receiving memos (test plan E04, T3): a memo type whose route is a
 * review by [reviewer], the committee (waiting for its decision), then a final approval by the BRC member `colleague`
 * with [deputy] as the fallback. Memos are raised by [initiator].
 */
abstract class CarryOverFixture : DeclarationFixture() {
    @Autowired lateinit var seeding: WorkflowSeeding

    @Autowired lateinit var memoTypes: MemoTypes

    @Autowired lateinit var submissions: ForumSubmissions

    @Autowired lateinit var triage: SubmissionTriage

    @Autowired lateinit var declarationStore: DeclarationStore

    lateinit var checker: UserAccount
    lateinit var initiator: Person
    lateinit var reviewer: Person
    lateinit var deputy: Person
    lateinit var memoType: UUID

    @BeforeEach
    fun memoRoute() {
        checker = accounts.anActiveUser(transactions, tenant, aPassword(), tenantAdmin = true)
        initiator = person()
        reviewer = person()
        deputy = person()
        val body =
            """
            {"schemaVersion":2,"start":"review","steps":[
              {"id":"review","type":"approve","name":"Review","assignee":{"kind":"person","userId":"${reviewer.id}"},"next":"committee"},
              {"id":"committee","type":"escalate-to-forum","forum":"board-risk","wait":true,"next":"final"},
              {"id":"final","type":"approve","name":"Final","assignee":{"kind":"person","userId":"${colleague.id}"},
               "fallback":{"kind":"person","userId":"${deputy.id}"},"next":"end"}
            ]}
            """
        asUser(admin) { seeding.submit("to-brc", "To the Board Risk Committee", body) }
        asUser(checker) { seeding.approve("to-brc") }
        memoType = asUser(admin) { memoTypes.create(MemoTypeFields("Risk paper", null, false, true, "to-brc", RoutePolicy.FIXED)) }.id
    }

    /**
     * A memo raised by [initiator] listing [interested] as having an interest, reviewed and escalated to the BRC; its id
     * and its submission's.
     */
    fun escalated(
        subject: String,
        vararg interested: Person,
    ): Pair<UUID, UUID> {
        val level = levels()[0].id
        val id =
            create(initiator.session, """{"subject":"$subject","typeId":"$memoType","classificationId":"$level"}""")
                .json()["id"]
                .asString()
        interested.forEach {
            assertThat(post("/api/v1/memos/$id/interests", initiator.session, """{"userId":"${it.id}","reason":"$MEMO_REASON"}"""))
                .hasStatusOk()
        }
        uploadDocument(id, initiator.session)
        assertThat(post("/api/v1/memos/$id/submit", initiator.session)).hasStatusOk()
        val task = get(id, reviewer.session).json()["myTask"]["id"].asString()
        assertThat(post("/api/v1/tasks/$task/approve", reviewer.session)).hasStatusOk()
        val submission = asUser(secretary.account) { submissions.queue(brc, emptySet()) }.single { it.subjectId.toString() == id }
        return UUID.fromString(id) to submission.id
    }

    /** Places [submission] on [meeting]'s draft; the new item's id. */
    fun place(
        meeting: MeetingRow,
        submission: UUID,
    ): UUID =
        asUser(secretary.account) {
            items.add(meeting.id, version(meeting), ItemInput(ItemKind.SUBMISSION, submissionId = submission))
        }

    /** The declarations on [item], the oldest first. */
    fun declarationsOn(
        meeting: MeetingRow,
        item: UUID,
    ): List<DeclarationRow> = asUser(admin) { declarationStore.ofMeeting(meeting.id) }.filter { it.itemId == item }

    /** The memo interests of [memo]: user id to (who declared it, the reason). */
    fun memoInterests(memo: UUID): Map<UUID, Pair<UUID, String>> =
        AppRoleJdbc.inTransaction(tenant.id) { c ->
            c.prepareStatement("select user_id, declared_by, reason from memo_interest where memo_id = ?").use { s ->
                s.setObject(1, memo)
                s.executeQuery().use { r ->
                    buildMap {
                        while (r.next()) put(r.getObject(1, UUID::class.java), r.getObject(2, UUID::class.java) to r.getString(3))
                    }
                }
            }
        }

    companion object {
        /** The reason the initiator gave on the memo: business content that never reaches the meeting. */
        const val MEMO_REASON = "CANARY-MEMO-REASON guarantor of the borrower"
    }
}
