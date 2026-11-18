package com.memo.meeting

import com.memo.common.authz.ForbiddenException
import com.memo.common.web.RefusedException
import com.memo.forum.ForumRole
import com.memo.forum.LateMode
import com.memo.forum.SubmissionStatus
import com.memo.forum.internal.ForumInput
import com.memo.forum.internal.ForumMembers
import com.memo.forum.internal.ForumSubmissions
import com.memo.forum.internal.ForumTypeLifecycle
import com.memo.forum.internal.ForumTypePreset
import com.memo.forum.internal.ForumTypes
import com.memo.forum.internal.Forums
import com.memo.forum.internal.MemberInput
import com.memo.forum.internal.SubmissionTriage
import com.memo.identity.UserAccount
import com.memo.meeting.internal.AgendaDraftResponse
import com.memo.meeting.internal.AgendaDraftViews
import com.memo.meeting.internal.AgendaItemResponse
import com.memo.meeting.internal.AgendaItems
import com.memo.meeting.internal.AgendaOrigin
import com.memo.meeting.internal.AgendaPapers
import com.memo.meeting.internal.AgendaStart
import com.memo.meeting.internal.ItemInput
import com.memo.meeting.internal.ItemKind
import com.memo.meeting.internal.Lateness
import com.memo.meeting.internal.MeetingInput
import com.memo.meeting.internal.MeetingLifecycle
import com.memo.meeting.internal.MeetingPaperPurpose
import com.memo.meeting.internal.MeetingPlace
import com.memo.meeting.internal.MeetingReads
import com.memo.meeting.internal.MeetingRow
import com.memo.meeting.internal.PaperChange
import com.memo.meeting.internal.PaperInput
import com.memo.meeting.internal.PaperSource
import com.memo.meeting.internal.TimesInput
import com.memo.memo.internal.MemoAttachmentPurpose
import com.memo.memo.internal.MemoTypeFields
import com.memo.memo.internal.MemoTypes
import com.memo.memo.internal.RoutePolicy
import com.memo.testsupport.AppRoleJdbc
import com.memo.testsupport.AppRoleJdbc.long
import com.memo.testsupport.Audit
import com.memo.testsupport.MemoIntegrationTest
import com.memo.testsupport.Person
import com.memo.testsupport.Samples
import com.memo.testsupport.aPassword
import com.memo.testsupport.anActiveUser
import com.memo.workflow.WorkflowSeeding
import com.memo.workflow.WorkingDays
import com.memo.workflow.internal.Calendars
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

/**
 * Placing submissions (test plan E03, T2; ADR-0060, ADR-0061): a memo's submission becomes an item with its subject,
 * cover sheet and pack; a queued one is accepted in the same step; the late modes apply against the paper deadline on
 * the tenant's calendar; the draft keeps in step with the forum; removing the item or cancelling the meeting returns the
 * submission to the tray; uploads for the meeting are papers too.
 */
class AgendaPlacementIT : MemoIntegrationTest() {
    @Autowired lateinit var forumTypes: ForumTypes

    @Autowired lateinit var typeLifecycle: ForumTypeLifecycle

    @Autowired lateinit var forums: Forums

    @Autowired lateinit var members: ForumMembers

    @Autowired lateinit var submissions: ForumSubmissions

    @Autowired lateinit var triage: SubmissionTriage

    @Autowired lateinit var seeding: WorkflowSeeding

    @Autowired lateinit var memoTypes: MemoTypes

    @Autowired lateinit var lifecycle: MeetingLifecycle

    @Autowired lateinit var reads: MeetingReads

    @Autowired lateinit var starts: AgendaStart

    @Autowired lateinit var items: AgendaItems

    @Autowired lateinit var papers: AgendaPapers

    @Autowired lateinit var views: AgendaDraftViews

    @Autowired lateinit var calendars: Calendars

    @Autowired lateinit var workingDays: WorkingDays

    private lateinit var checker: UserAccount
    private lateinit var alice: Person
    private lateinit var reviewer: Person
    private lateinit var secretary: Person
    private lateinit var chair: Person
    private lateinit var member: Person
    private lateinit var typeId: UUID
    private lateinit var credit: UUID
    private lateinit var memoType: UUID

    @BeforeEach
    fun committee() {
        checker = accounts.anActiveUser(transactions, tenant, aPassword(), tenantAdmin = true)
        alice = person()
        reviewer = person()
        secretary = person()
        chair = person()
        member = person()
        typeId =
            asUser(admin) { forumTypes.create("MC", "Management committee", ForumTypePreset.MANAGEMENT_COMMITTEE, publish = true) }.type.id
        credit = asUser(admin) { forums.create(ForumInput(KEY, "MCC", "Management Credit Committee", "Credit", typeId)) }.id
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
            asUser(admin) { memoTypes.create(MemoTypeFields("Credit paper", null, false, true, "to-committee", RoutePolicy.FIXED)) }.id
    }

    @Test
    fun `a placed memo brings its subject, cover sheet and pack, and removing the item returns it to the tray`() {
        val (memo, submission) = escalated("Loan to Acme Trading", annex = true)
        val meeting = meeting(LocalDate.now().plusDays(DAYS_AHEAD))
        assertThat(asUser(secretary.account) { submissions.ready(credit, null) }).isEmpty()
        assertThat(draft(meeting).traySize).isZero()

        // A queued submission is accepted and placed in one step.
        var draft = place(meeting, submission)
        Audit.assertAudited(tenant.id, "forum.submission.accepted", submission.toString(), secretary.id.toString())
        Audit.assertAudited(tenant.id, "forum.submission.placed", submission.toString(), secretary.id.toString())
        Audit.assertAudited(tenant.id, "meeting.agenda.item_added", meeting.id.toString(), secretary.id.toString())
        val item = draft.submission(submission)
        assertThat(item.title).isEqualTo("Loan to Acme Trading")
        assertThat(item.memoId.toString()).isEqualTo(memo)
        assertThat(item.late).isEqualTo(Lateness.NONE)
        assertThat(
            draft.sections
                .single { it.receivesSubmissions }
                .items
                .map { it.id },
        ).contains(item.id)
        assertThat(item.papers.map { it.source }).containsExactly(PaperSource.MEMO_DOCUMENT, PaperSource.MEMO_ATTACHMENT)
        assertThat(item.papers[0].title).startsWith("Paper: ")
        assertThat(item.papers[1].title).isEqualTo("valuation.pdf")
        assertThat(item.outOfPack).isEmpty()

        // The memo's forum panel shows the meeting; the submission is no longer in the tray.
        val panel = read("/api/v1/memos/$memo/forum-submissions", alice.session).json()[0]
        assertThat(panel["meetingId"].asString()).isEqualTo(meeting.id.toString())
        assertThat(panel["meetingDate"].asString()).isEqualTo(meeting.date.toString())
        assertThat(read("/api/v1/forums/$credit/submissions/ready", secretary.session).json().size()).isZero()
        assertThat(read("/api/v1/forums/$credit/submissions/ready", member.session)).hasStatus(HttpStatus.FORBIDDEN)
        refused("ONE_PLACEMENT") { place(meeting(LocalDate.now().plusDays(DAYS_AHEAD + 1)), submission) }

        // An annex not for the committee leaves the pack and can be put back.
        draft =
            asUser(secretary.account) { papers.remove(meeting.id, item.papers[1].id, versionOf(meeting)).let { views.draft(meeting.id) } }
        assertThat(draft.submission(submission).outOfPack.map { it.title }).containsExactly("valuation.pdf")
        Audit.assertAudited(tenant.id, "meeting.agenda.paper_removed", meeting.id.toString(), secretary.id.toString())
        val annex = item.papers[1].fileId
        draft =
            asUser(
                secretary.account,
            ) { papers.add(meeting.id, item.id, versionOf(meeting), PaperInput(annex)).let { views.draft(meeting.id) } }
        assertThat(draft.submission(submission).papers.map { it.title }).containsExactly(item.papers[0].title, "valuation.pdf")
        Audit.assertAudited(tenant.id, "meeting.agenda.paper_added", meeting.id.toString(), secretary.id.toString())
        refused("ALREADY_IN_PACK") { asUser(secretary.account) { papers.add(meeting.id, item.id, versionOf(meeting), PaperInput(annex)) } }
        val retitled = draft.submission(submission).papers[1]
        asUser(secretary.account) { papers.update(meeting.id, retitled.id, versionOf(meeting), PaperChange("Valuation report")) }
        Audit.assertAudited(tenant.id, "meeting.agenda.paper_updated", meeting.id.toString(), secretary.id.toString())

        // Removing the item frees the submission: back in the tray, still accepted.
        asUser(secretary.account) { items.remove(meeting.id, item.id, versionOf(meeting)) }
        Audit.assertAudited(tenant.id, "forum.submission.unplaced", submission.toString(), secretary.id.toString())
        val back = asUser(secretary.account) { submissions.get(credit, submission) }
        assertThat(back.status to back.meetingId).isEqualTo(SubmissionStatus.ACCEPTED to null)
        assertThat(asUser(secretary.account) { submissions.ready(credit, null) }.map { it.id }).containsExactly(submission)
        assertThat(draft(meeting).traySize).isEqualTo(1)
        // And it can go on another agenda.
        val other = meeting(LocalDate.now().plusDays(DAYS_AHEAD + 2))
        assertThat(place(other, submission).submission(submission).title).isEqualTo("Loan to Acme Trading")
    }

    @Test
    fun `a submission returned, declined or deferred in the queue leaves the draft, and its placement goes`() {
        val meeting = meeting(LocalDate.now().plusDays(DAYS_AHEAD))
        val (_, returned) = escalated("Returned paper")
        val (_, declined) = escalated("Declined paper")
        val (_, deferred) = escalated("Deferred paper")
        listOf(returned, declined, deferred).forEach { place(meeting, it) }

        asUser(secretary.account) {
            triage.returnForInformation(credit, returned, submissions.get(credit, returned).version, "Add the valuation")
        }
        asUser(secretary.account) { triage.decline(credit, declined, submissions.get(credit, declined).version, "A board matter") }
        asUser(secretary.account) { triage.defer(credit, deferred, submissions.get(credit, deferred).version, "Next quarter", null) }

        assertThat(draft(meeting).sections.flatMap { it.items }.filter { it.kind == ItemKind.SUBMISSION }).isEmpty()
        for (id in listOf(returned, declined, deferred)) {
            assertThat(asUser(secretary.account) { submissions.get(credit, id) }.meetingId).isNull()
        }
        AppRoleJdbc.inTransaction(tenant.id) { c ->
            assertThat(
                c.long("select count(*) from meeting_item where meeting_id = ? and removed_reason = 'SUBMISSION_CHANGED'", meeting.id),
            ).isEqualTo(3)
        }
    }

    @Test
    fun `after the paper deadline the committee's late mode applies, counted on the tenant's calendar`() {
        // CHAIR_APPROVAL (the preset): tomorrow's meeting is past a five-working-day deadline.
        val tomorrow = meeting(LocalDate.now().plusDays(1))
        assertThat(draft(tomorrow).deadlinePassed).isTrue()
        val (_, waiting) = escalated("Urgent facility")
        val draft = place(tomorrow, waiting)
        val item = draft.submission(waiting)
        assertThat(item.late).isEqualTo(Lateness.AWAITING_CHAIR)
        assertThat(item.number).isNull()
        assertThat(item.startsAt).isNull()
        Audit.assertAudited(tenant.id, "meeting.late.requested", tomorrow.id.toString(), secretary.id.toString())
        assertThat(notified(chair, "MEETING_LATE_PAPER")).isEqualTo(1)
        assertThat(notified(member, "MEETING_LATE_PAPER")).isZero()

        // BLOCK: refused before anything changes; the submission stays in the tray for a later meeting.
        publish(LateMode.BLOCK)
        val blocked = meeting(LocalDate.now().plusDays(1))
        val (_, late) = escalated("Late paper")
        asUser(secretary.account) { triage.accept(credit, late, submissions.get(credit, late).version, null) }
        refused("PAPER_DEADLINE_PASSED") { place(blocked, late) }
        assertThat(draft(blocked).sections.flatMap { it.items }.none { it.kind == ItemKind.SUBMISSION }).isTrue()
        assertThat(asUser(secretary.account) { submissions.get(credit, late) }.meetingId).isNull()
        // A meeting further out takes it on time.
        assertThat(place(meeting(LocalDate.now().plusDays(DAYS_AHEAD)), late).submission(late).late).isEqualTo(Lateness.NONE)

        // ALLOW: placed, marked late.
        publish(LateMode.ALLOW)
        val allowed = meeting(LocalDate.now().plusDays(1))
        val (_, allowedLate) = escalated("Allowed late paper")
        assertThat(place(allowed, allowedLate).submission(allowedLate).late).isEqualTo(Lateness.LATE)

        // The deadline is counted in working days on the tenant's calendar: a holiday moves it earlier.
        val far = meeting(LocalDate.now().plusDays(DAYS_AHEAD))
        val deadline = checkNotNull(draft(far).paperDeadline)
        asUser(admin) { calendars.setDays(deadline, null, emptyList(), "Holiday") }
        val moved = checkNotNull(draft(far).paperDeadline)
        assertThat(moved).isBefore(deadline)
        assertThat(moved).isEqualTo(asUser(admin) { workingDays.before(far.date, DEADLINE_DAYS) })
    }

    @Test
    fun `cancelling a meeting returns its submissions to the tray, and a rescheduled meeting's placements follow its date`() {
        val meeting = meeting(LocalDate.now().plusDays(DAYS_AHEAD))
        val (_, submission) = escalated("Facility renewal")
        place(meeting, submission)

        val current = asUser(secretary.account) { reads.get(meeting.id) }
        val moved =
            asUser(secretary.account) { lifecycle.reschedule(meeting.id, current.version, times(meeting.date.plusDays(7)), "Chair away") }
        assertThat(asUser(secretary.account) { submissions.get(credit, submission) }.meetingDate).isEqualTo(moved.date)

        asUser(secretary.account) { lifecycle.cancel(meeting.id, moved.version, "No quorum") }
        Audit.assertAudited(tenant.id, "meeting.agenda.released", meeting.id.toString(), secretary.id.toString())
        Audit.assertAudited(tenant.id, "forum.submission.unplaced", submission.toString(), secretary.id.toString())
        val back = asUser(secretary.account) { submissions.get(credit, submission) }
        assertThat(back.status to back.meetingId).isEqualTo(SubmissionStatus.ACCEPTED to null)
        AppRoleJdbc.inTransaction(tenant.id) { c ->
            assertThat(
                c.long("select count(*) from meeting_item where meeting_id = ? and removed_reason = 'MEETING_CANCELLED'", meeting.id),
            ).isEqualTo(1)
        }
        val cancelled = draft(meeting)
        assertThat(cancelled.sections.flatMap { it.items }.none { it.kind == ItemKind.SUBMISSION }).isTrue()
        refused(
            "FROZEN",
        ) { asUser(secretary.account) { items.add(meeting.id, cancelled.version, ItemInput(ItemKind.ITEM, title = "Late")) } }
        assertThat(
            place(meeting(LocalDate.now().plusDays(DAYS_AHEAD + 3)), submission).submission(submission).late,
        ).isEqualTo(Lateness.NONE)
    }

    @Test
    fun `uploads for the meeting are papers its officers read, replaced by a new version until the meeting is held`() {
        val meeting = meeting(LocalDate.now().plusDays(DAYS_AHEAD))
        var draft =
            asUser(secretary.account) { items.add(meeting.id, versionOf(meeting), ItemInput(ItemKind.ITEM, title = "Market update")) }
                .let { draft(meeting) }
        val item = draft.sections.flatMap { it.items }.single { it.title == "Market update" }

        // Only the committee's secretaries upload them; members cannot read them before the paper rule (T4).
        assertThat(upload(member.session, Samples.pdf(), uploadUri(meeting), "market.pdf").response.status).isIn(403, 404)
        val uploaded = upload(secretary.session, Samples.pdf(), uploadUri(meeting), "market.pdf")
        assertThat(uploaded).hasStatus(HttpStatus.CREATED)
        val fileId = UUID.fromString(uploaded.json()["fileId"].asString())
        draft =
            asUser(
                secretary.account,
            ) { papers.add(meeting.id, item.id, versionOf(meeting), PaperInput(fileId)).let { views.draft(meeting.id) } }
        val paper =
            draft.sections
                .flatMap { it.items }
                .single { it.id == item.id }
                .papers
                .single()
        assertThat(paper.source to paper.title).isEqualTo(PaperSource.UPLOAD to "market.pdf")
        assertThat(paper.mediaType).isEqualTo("application/pdf")
        assertThat(read("/api/v1/files/$fileId", chair.session)).hasStatusOk()
        assertThat(
            read("/api/v1/files/$fileId", member.session).response.status,
        ).isIn(HttpStatus.NOT_FOUND.value(), HttpStatus.FORBIDDEN.value())
        // A file of another purpose, or of another meeting, is not a paper here; nor is a memo attachment.
        val elsewhere = meeting(LocalDate.now().plusDays(DAYS_AHEAD + 1))
        val foreign =
            UUID.fromString(
                upload(secretary.session, Samples.pdf(), uploadUri(elsewhere), "other.pdf").json()["fileId"].asString(),
            )
        assertThat(problem { asUser(secretary.account) { papers.add(meeting.id, item.id, versionOf(meeting), PaperInput(foreign)) } })
            .isEqualTo("fileId:unknown")
        assertThat(MemoAttachmentPurpose.KEY).isNotEqualTo(MeetingPaperPurpose.KEY)

        // A new version replaces the paper's content.
        val file = read("/api/v1/files/$fileId", secretary.session).json()
        val replaced =
            upload(
                secretary.session,
                Samples.pdf(2),
                "/api/v1/files/$fileId/versions",
                "market-v2.pdf",
                mapOf("If-Match" to "\"${file["version"]}\""),
            )
        assertThat(replaced).hasStatus(HttpStatus.CREATED)
        Audit.assertAudited(tenant.id, "meeting.agenda.paper_replaced", meeting.id.toString(), secretary.id.toString())
    }

    @Test
    fun `breaks take no papers, the chair builds nothing, and a held meeting's upload takes no new version`() {
        val meeting = meeting(LocalDate.now().plusDays(DAYS_AHEAD))
        val item =
            asUser(secretary.account) {
                items.add(meeting.id, versionOf(meeting), ItemInput(ItemKind.ITEM, title = "Market update"))
            }
        val fileId = UUID.fromString(upload(secretary.session, Samples.pdf(), uploadUri(meeting), "market.pdf").json()["fileId"].asString())
        asUser(secretary.account) { papers.add(meeting.id, item, versionOf(meeting), PaperInput(fileId)) }

        assertThatThrownBy { asUser(chair.account) { papers.add(meeting.id, item, versionOf(meeting), PaperInput(fileId)) } }
            .isInstanceOf(ForbiddenException::class.java)
        val pause =
            asUser(secretary.account) {
                items.add(meeting.id, versionOf(meeting), ItemInput(ItemKind.BREAK, minutes = 10))
                views.draft(meeting.id)
            }.sections.flatMap { it.items }.single { it.kind == ItemKind.BREAK }
        refused("WRONG_KIND") { asUser(secretary.account) { papers.add(meeting.id, pause.id, versionOf(meeting), PaperInput(fileId)) } }

        // Once the meeting is held, a new version is refused.
        val scheduled =
            asUser(secretary.account) {
                reads.get(meeting.id)
            }.let { asUser(secretary.account) { lifecycle.schedule(it.id, it.version) } }
        val started = asUser(secretary.account) { lifecycle.start(scheduled.id, scheduled.version) }
        asUser(secretary.account) { lifecycle.end(started.id, started.version) }
        val latest = read("/api/v1/files/$fileId", secretary.session).json()
        val refusedUpload =
            upload(
                secretary.session,
                Samples.pdf(),
                "/api/v1/files/$fileId/versions",
                "market-v3.pdf",
                mapOf("If-Match" to "\"${latest["version"]}\""),
            )
        assertThat(refusedUpload.response.status).isIn(HttpStatus.CONFLICT.value(), HttpStatus.FORBIDDEN.value())
    }

    /** A memo of alice's on [subject], with its document (and an annex), submitted and reviewed: waiting for the committee. */
    private fun escalated(
        subject: String,
        annex: Boolean = false,
    ): Pair<String, UUID> {
        val level = levels()[0].id
        val id =
            create(
                alice.session,
                """{"subject":"$subject","typeId":"$memoType","classificationId":"$level"}""",
            ).json()["id"].asString()
        uploadDocument(id, alice.session)
        if (annex) {
            val attached =
                upload(alice.session, Samples.pdf(), "/api/v1/files?purpose=${MemoAttachmentPurpose.KEY}&owner=$id", "valuation.pdf")
            assertThat(attached).hasStatus(HttpStatus.CREATED)
        }
        assertThat(post("/api/v1/memos/$id/submit", alice.session)).hasStatusOk()
        val task = get(id, reviewer.session).json()["myTask"]["id"].asString()
        assertThat(post("/api/v1/tasks/$task/approve", reviewer.session)).hasStatusOk()
        val submission = asUser(secretary.account) { submissions.queue(credit, emptySet()) }.single { it.subjectId.toString() == id }
        return id to submission.id
    }

    /** Publishes a new version of the committee type with [mode]; meetings created afterwards pin it. */
    private fun publish(mode: LateMode) {
        val draft = asUser(admin) { typeLifecycle.newDraft(typeId) }
        val settings = draft.settings.copy(submissionPolicy = draft.settings.submissionPolicy.copy(lateMode = mode))
        val saved = asUser(admin) { typeLifecycle.replace(typeId, draft.number, draft.revision, settings) }
        asUser(admin) { typeLifecycle.submit(typeId, saved.number, saved.revision) }
        asUser(checker) { typeLifecycle.approve(typeId, saved.number, null) }
    }

    /** A meeting of the committee on [day], with its agenda started from the template. */
    private fun meeting(day: LocalDate): MeetingRow =
        asUser(secretary.account) {
            val created =
                lifecycle.create(
                    credit,
                    MeetingInput(MeetingKind.ORDINARY, MeetingPlace(null, MeetingMode.IN_PERSON, "Board room", null, null), null),
                    times(day),
                )
            starts.start(created.id, AgendaOrigin.TEMPLATE, null)
            created
        }

    private fun times(day: LocalDate) = TimesInput(day.atTime(LocalTime.of(10, 0)), day.atTime(LocalTime.of(12, 0)), ZONE.id)

    private fun place(
        meeting: MeetingRow,
        submission: UUID,
    ): AgendaDraftResponse =
        asUser(secretary.account) {
            items.add(meeting.id, views.draft(meeting.id).version, ItemInput(ItemKind.SUBMISSION, submissionId = submission))
            views.draft(meeting.id)
        }

    private fun draft(meeting: MeetingRow): AgendaDraftResponse = asUser(secretary.account) { views.draft(meeting.id) }

    private fun versionOf(meeting: MeetingRow): Long = views.draft(meeting.id).version

    private fun AgendaDraftResponse.submission(id: UUID): AgendaItemResponse =
        sections.flatMap { it.items }.single { it.submissionId == id }

    private fun uploadUri(meeting: MeetingRow) = "/api/v1/files?purpose=${MeetingPaperPurpose.KEY}&owner=${meeting.id}"

    private fun notified(
        person: Person,
        kind: String,
    ): Long =
        AppRoleJdbc.inTransaction(tenant.id) { c ->
            c.long("select count(*) from notification_item where user_id = ? and kind = ?", person.id, kind)
        }

    private fun refused(
        reason: String,
        block: () -> Any?,
    ) = assertThatThrownBy { block() }.isInstanceOfSatisfying(RefusedException::class.java) { assertThat(it.reason).isEqualTo(reason) }

    private fun problem(block: () -> Any?): String {
        val rejected =
            runCatching { block() }.exceptionOrNull() as? com.memo.meeting.internal.MeetingRejectedException
                ?: error("expected a rejection")
        return rejected.problems.joinToString { "${it.field}:${it.code}" }
    }

    private companion object {
        const val KEY = "credit-committee"
        const val DAYS_AHEAD = 40L
        const val DEADLINE_DAYS = 5
        val ZONE: ZoneId = ZoneId.of("Asia/Dhaka")
    }
}
