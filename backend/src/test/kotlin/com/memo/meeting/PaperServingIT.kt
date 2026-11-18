package com.memo.meeting

import com.memo.forum.internal.ForumSubmissions
import com.memo.identity.UserAccount
import com.memo.meeting.internal.ItemInput
import com.memo.meeting.internal.ItemKind
import com.memo.meeting.internal.MeetingRow
import com.memo.memo.internal.MemoTypeFields
import com.memo.memo.internal.MemoTypes
import com.memo.memo.internal.RoutePolicy
import com.memo.testsupport.Audit
import com.memo.testsupport.Person
import com.memo.testsupport.Samples
import com.memo.testsupport.aPassword
import com.memo.testsupport.anActiveUser
import com.memo.testsupport.onHost
import com.memo.workflow.WorkflowSeeding
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import org.springframework.test.web.servlet.assertj.MvcTestResult
import java.util.UUID

/**
 * Paper opens (test plan E03, T4; ADR-0061): members get the PDF of the version the revision pinned, with ranges, an
 * earlier revision's version through `?revision=`, the original for secretaries only, one `meeting.paper.opened` per
 * open (not per range), the "Opened" tick; the generic files endpoints refuse members; and a memo's papers are read
 * through the meeting while the memo itself stays closed to members.
 */
class PaperServingIT : PaperFixture() {
    @Autowired lateinit var seeding: WorkflowSeeding

    @Autowired lateinit var memoTypes: MemoTypes

    @Autowired lateinit var submissions: ForumSubmissions

    @Test
    fun `members read the pinned PDF with ranges, an earlier revision's version, and each open is audited once`() {
        val meeting = meeting()
        val item = addItem(meeting, "Capital plan")
        val first = Samples.pdf(1)
        val file = upload(meeting, "capital.pdf", first)
        val paper = paper(meeting, item, file)
        publish(meeting)

        val whole = open(d1, meeting, paper)
        assertThat(whole).hasStatusOk()
        assertThat(whole.response.contentType).isEqualTo("application/pdf")
        assertThat(whole.response.getHeader("Content-Disposition")).startsWith("inline")
        assertThat(whole.response.getHeader("Cache-Control")).isEqualTo("private, no-store")
        assertThat(whole.response.contentAsByteArray).isEqualTo(first)
        val ranged = ranged(d1, meeting, paper, "bytes=0-9")
        assertThat(ranged).hasStatus(HttpStatus.PARTIAL_CONTENT)
        assertThat(ranged.response.getHeader("Content-Range")).isEqualTo("bytes 0-9/${first.size}")
        val etag = whole.response.getHeader("ETag")
        val unchanged = mvc.get().uri("/api/v1/meetings/${meeting.id}/papers/$paper/content").with(onHost(tenant.slug))
        assertThat(unchanged.cookie(d1.session).header("If-None-Match", etag.orEmpty()).exchange()).hasStatus(HttpStatus.NOT_MODIFIED)

        // One audit entry per open by each person, with the paper, the revision and the pinned version; ranges add none.
        assertThat(opens(meeting)).isEqualTo(1)
        val audited = Audit.assertAudited(tenant.id, "meeting.paper.opened", meeting.id.toString(), d1.id.toString())
        assertThat(audited.canonical).contains(paper.toString()).contains("\"revision\":1")
        assertThat(open(d2, meeting, paper)).hasStatusOk()
        assertThat(opens(meeting)).isEqualTo(2)
        // The "Opened" tick, per person.
        assertThat(agenda(d1, meeting).lines().getValue(item)["papers"][0]["opened"].asBoolean()).isTrue()
        assertThat(agenda(d3, meeting).lines().getValue(item)["papers"][0]["opened"].asBoolean()).isFalse()

        // Originals for the secretaries only.
        assertThat(open(d1, meeting, paper, "?kind=ORIGINAL")).hasStatus(HttpStatus.FORBIDDEN)
        assertThat(open(chair, meeting, paper, "?kind=ORIGINAL")).hasStatus(HttpStatus.FORBIDDEN)
        assertThat(open(secretary, meeting, paper, "?kind=ORIGINAL")).hasStatusOk()

        // A new version, published in revision 2: the latest revision serves it, revision 1 still its own.
        replace(file)
        assertThat(open(d1, meeting, paper).response.contentAsByteArray).isEqualTo(first)
        publish(meeting, "Updated capital plan")
        val second = open(d1, meeting, paper).response.contentAsByteArray
        assertThat(second).isNotEqualTo(first)
        assertThat(open(d1, meeting, paper, "?revision=1").response.contentAsByteArray).isEqualTo(first)
        assertThat(open(d1, meeting, paper, "?revision=3")).hasStatus(HttpStatus.NOT_FOUND)

        // The generic files endpoints cannot tell which version a revision pinned: members are refused there.
        val current = read("/api/v1/files/$file", secretary.session).json()["versions"][0]["versionId"].asString()
        assertThat(read("/api/v1/files/$file/content", d1.session)).hasStatus(HttpStatus.FORBIDDEN)
        assertThat(read("/api/v1/files/$file/versions/$current/content", d1.session)).hasStatus(HttpStatus.FORBIDDEN)
        assertThat(read("/api/v1/files/$file/versions/$current/renditions/pdf", d1.session)).hasStatus(HttpStatus.FORBIDDEN)
    }

    @Test
    fun `a document is read as its PDF rendition, and a memo's papers through the meeting while the memo stays closed`() {
        val meeting = meeting()
        val item = addItem(meeting, "Operating model")
        val docx = paper(meeting, item, upload(meeting, "model.docx", Samples.docx()))
        val (memo, submission) = escalated("Credit risk appetite")
        asUser(secretary.account) { items.add(meeting.id, version(meeting), ItemInput(ItemKind.SUBMISSION, submissionId = submission)) }
        val placed =
            asUser(secretary.account) { views.draft(meeting.id) }.sections.flatMap { it.items }.single {
                it.submissionId ==
                    submission
            }
        val memoPaper = placed.papers.first()
        publish(meeting)

        // The upload's PDF rendition once it is built (until then: 409 PAPER_NOT_READY); the original stays a document.
        readable(d1, meeting, docx)
        assertThat(open(secretary, meeting, docx, "?kind=ORIGINAL").response.contentType).contains("wordprocessingml")

        // The memo's document reaches the member as a paper of the item; the memo and its file stay closed to them.
        readable(d1, meeting, memoPaper.id)
        assertThat(get(memo, d1.session).response.status).isIn(HttpStatus.NOT_FOUND.value(), HttpStatus.FORBIDDEN.value())
        assertThat(read("/api/v1/files/${memoPaper.fileId}/content", d1.session).response.status)
            .isIn(HttpStatus.NOT_FOUND.value(), HttpStatus.FORBIDDEN.value())
    }

    private fun ranged(
        person: Person,
        meeting: MeetingRow,
        paper: UUID,
        range: String,
    ): MvcTestResult =
        mvc
            .get()
            .uri("/api/v1/meetings/${meeting.id}/papers/$paper/content")
            .with(onHost(tenant.slug))
            .cookie(person.session)
            .header("Range", range)
            .exchange()

    /**
     * Opens the paper, waiting a while for its PDF (the rendition job runs in the background, and test contexts share the
     * scheduler table, so it may not be built here): the PDF, or 409 `PAPER_NOT_READY`, never a refusal.
     */
    private fun readable(
        person: Person,
        meeting: MeetingRow,
        paper: UUID,
    ) {
        val deadline = System.currentTimeMillis() + WAIT_MILLIS
        var result = open(person, meeting, paper)
        while (result.response.status == HttpStatus.CONFLICT.value() && System.currentTimeMillis() < deadline) {
            Thread.sleep(POLL_MILLIS)
            result = open(person, meeting, paper)
        }
        if (result.response.status == HttpStatus.CONFLICT.value()) {
            assertThat(result.json()["reason"].asString()).isEqualTo("PAPER_NOT_READY")
        } else {
            assertThat(result).hasStatusOk()
            assertThat(result.response.contentType).isEqualTo("application/pdf")
        }
    }

    private fun opens(meeting: MeetingRow): Int =
        Audit.entries(tenant.id).count { it.action == "meeting.paper.opened" && it.objectId == meeting.id.toString() }

    /** A memo of its author's on [subject], submitted and reviewed: waiting for the committee. The memo and the submission. */
    private fun escalated(subject: String): Pair<String, UUID> {
        val author = person()
        val reviewer = person()
        val checker: UserAccount = accounts.anActiveUser(transactions, tenant, aPassword(), tenantAdmin = true)
        val body =
            """
            {"schemaVersion":2,"start":"review","steps":[
              {"id":"review","type":"approve","name":"Review","assignee":{"kind":"person","userId":"${reviewer.id}"},"next":"committee"},
              {"id":"committee","type":"escalate-to-forum","forum":"board-risk","wait":true,"next":"end"}
            ]}
            """
        asUser(admin) { seeding.submit("to-risk", "To the risk committee", body) }
        asUser(checker) { seeding.approve("to-risk") }
        val type = asUser(admin) { memoTypes.create(MemoTypeFields("Risk paper", null, false, true, "to-risk", RoutePolicy.FIXED)) }.id
        val id =
            create(author.session, """{"subject":"$subject","typeId":"$type","classificationId":"${levels()[0].id}"}""")
                .json()["id"]
                .asString()
        uploadDocument(id, author.session)
        assertThat(post("/api/v1/memos/$id/submit", author.session)).hasStatusOk()
        val task = get(id, reviewer.session).json()["myTask"]["id"].asString()
        assertThat(post("/api/v1/tasks/$task/approve", reviewer.session)).hasStatusOk()
        val submission = asUser(secretary.account) { submissions.queue(brc, emptySet()) }.single { it.subjectId.toString() == id }
        return id to submission.id
    }

    private companion object {
        const val WAIT_MILLIS = 15_000L
        const val POLL_MILLIS = 200L
    }
}
