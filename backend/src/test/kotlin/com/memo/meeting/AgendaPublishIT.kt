package com.memo.meeting

import com.memo.common.authz.ForbiddenException
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
import com.memo.meeting.internal.AgendaChair
import com.memo.meeting.internal.AgendaDraftResponse
import com.memo.meeting.internal.AgendaDraftViews
import com.memo.meeting.internal.AgendaItemResponse
import com.memo.meeting.internal.AgendaItems
import com.memo.meeting.internal.AgendaMoves
import com.memo.meeting.internal.AgendaNoticeScheduler
import com.memo.meeting.internal.AgendaOrigin
import com.memo.meeting.internal.AgendaPapers
import com.memo.meeting.internal.AgendaPublisher
import com.memo.meeting.internal.AgendaStart
import com.memo.meeting.internal.AgendaState
import com.memo.meeting.internal.ItemFlag
import com.memo.meeting.internal.ItemInput
import com.memo.meeting.internal.ItemKind
import com.memo.meeting.internal.LateDecision
import com.memo.meeting.internal.Lateness
import com.memo.meeting.internal.MeetingInput
import com.memo.meeting.internal.MeetingLifecycle
import com.memo.meeting.internal.MeetingPaperPurpose
import com.memo.meeting.internal.MeetingPlace
import com.memo.meeting.internal.MeetingReads
import com.memo.meeting.internal.MeetingRejectedException
import com.memo.meeting.internal.MeetingRow
import com.memo.meeting.internal.MoveInput
import com.memo.meeting.internal.PaperFlag
import com.memo.meeting.internal.PaperInput
import com.memo.meeting.internal.PaperNotReadableException
import com.memo.meeting.internal.PublishedLate
import com.memo.meeting.internal.RevisionStore
import com.memo.meeting.internal.Snapshot
import com.memo.meeting.internal.TimesInput
import com.memo.memo.internal.MemoTypeFields
import com.memo.memo.internal.MemoTypes
import com.memo.memo.internal.RoutePolicy
import com.memo.testsupport.AppRoleJdbc
import com.memo.testsupport.AppRoleJdbc.long
import com.memo.testsupport.Audit
import com.memo.testsupport.MeetingEventsRecorder
import com.memo.testsupport.MemoIntegrationTest
import com.memo.testsupport.Person
import com.memo.testsupport.Samples
import com.memo.testsupport.aPassword
import com.memo.testsupport.anActiveUser
import com.memo.testsupport.sendJson
import com.memo.workflow.WorkflowSeeding
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

/**
 * Publishing and revisions (test plan E03, T3; ADR-0060, ADR-0061): the chair's agreement to revision 1 (in the app, or
 * recorded on the chair's behalf), the meeting's number and the code lock, numbers, times and pinned paper versions;
 * revision 2 with a note and its flags while members still read revision 1; items waiting for the chair left out until
 * the chair agrees, and a declined one back in the tray; notify off; the chair's comments; and an item leaving a
 * published agenda.
 */
class AgendaPublishIT : MemoIntegrationTest() {
    @Autowired lateinit var forumTypes: ForumTypes

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

    @Autowired lateinit var moves: AgendaMoves

    @Autowired lateinit var papers: AgendaPapers

    @Autowired lateinit var views: AgendaDraftViews

    @Autowired lateinit var chairing: AgendaChair

    @Autowired lateinit var publisher: AgendaPublisher

    @Autowired lateinit var revisions: RevisionStore

    @Autowired lateinit var notices: AgendaNoticeScheduler

    private lateinit var checker: UserAccount
    private lateinit var alice: Person
    private lateinit var reviewer: Person
    private lateinit var secretary: Person
    private lateinit var chair: Person
    private lateinit var director: Person
    private lateinit var brc: UUID
    private lateinit var memoType: UUID

    @BeforeEach
    fun committee() {
        // The notifications are AgendaNotificationsIT's: here they are not scheduled.
        notices.jobs = false
        checker = accounts.anActiveUser(transactions, tenant, aPassword(), tenantAdmin = true)
        alice = person()
        reviewer = person()
        secretary = person()
        chair = person()
        director = person()
        val type = asUser(admin) { forumTypes.create("BC", "Board committee", ForumTypePreset.BOARD_COMMITTEE, publish = true) }.type.id
        brc = asUser(admin) { forums.create(ForumInput(KEY, "BRC", "Board Risk Committee", "Risk", type)) }.id
        asUser(admin) { members.add(brc, MemberInput(userId = secretary.id, role = ForumRole.SECRETARY)) }
        asUser(admin) { members.add(brc, MemberInput(userId = chair.id, role = ForumRole.CHAIR)) }
        asUser(admin) { members.add(brc, MemberInput(userId = director.id)) }
        val body =
            """
            {"schemaVersion":2,"start":"review","steps":[
              {"id":"review","type":"approve","name":"Review","assignee":{"kind":"person","userId":"${reviewer.id}"},"next":"committee"},
              {"id":"committee","type":"escalate-to-forum","forum":"$KEY","wait":true,"next":"end"}
            ]}
            """
        asUser(admin) { seeding.submit("to-risk", "To the risk committee", body) }
        asUser(checker) { seeding.approve("to-risk") }
        memoType = asUser(admin) { memoTypes.create(MemoTypeFields("Risk paper", null, false, true, "to-risk", RoutePolicy.FIXED)) }.id
    }

    @AfterEach
    fun jobsBack() {
        notices.jobs = true
    }

    @Test
    fun `revision 1 needs the chair's agreement, and recorded on the chair's behalf, it publishes and numbers the meeting`() {
        val meeting = meeting(LocalDate.now().plusDays(DAYS_AHEAD), schedule = false)
        val (_, submission) = escalated("Credit risk appetite")
        place(meeting, submission)

        refused("MEETING_NOT_SCHEDULED") { publish(meeting) }
        schedule(meeting)
        refused("CHAIR_AGREEMENT_REQUIRED") { publish(meeting) }
        assertThat(asUser(secretary.account) { publisher.preview(meeting.id) }.second?.chairAgreementMissing).isTrue()

        recordedOnBehalf(meeting)

        assertThat(publish(meeting)).isEqualTo(1)
        // What E04–E07 hear: the meeting's changes and the revision.
        assertThat(MeetingEventsRecorder.CHANGED.filter { it.meetingId == meeting.id }.map { it.change })
            .contains(MeetingChange.CREATED, MeetingChange.SCHEDULED, MeetingChange.NUMBERED)
        assertThat(MeetingEventsRecorder.PUBLISHED.filter { it.meetingId == meeting.id }.map { it.revision }).containsExactly(1)
        Audit.assertAudited(tenant.id, "meeting.agenda.published", meeting.id.toString(), secretary.id.toString())
        Audit.assertAudited(tenant.id, "meeting.numbered", meeting.id.toString(), secretary.id.toString())
        Audit.assertAudited(tenant.id, "forum.code_locked", brc.toString(), secretary.id.toString())
        assertThat(asUser(secretary.account) { reads.get(meeting.id) }.label).isEqualTo("BRC 1/${meeting.date.year}")
        AppRoleJdbc.inTransaction(tenant.id) { c ->
            assertThat(c.long("select count(*) from forum where id = ? and code_locked_at is not null", brc)).isEqualTo(1)
        }
        val published = draft(meeting)
        assertThat(published.state to published.currentRevision).isEqualTo(AgendaState.PUBLISHED to 1)
        assertThat(published.dirty).isFalse()
        assertThat(
            published.revisions
                .single()
                .publishedBy
                ?.id,
        ).isEqualTo(secretary.id)

        assertCopied(meeting, published, submission)

        // Nothing changed: refused; and from revision 2 a note is required.
        assertThat(problem { publish(meeting, note = null) }).isEqualTo("note:NOTE_REQUIRED")
        refused("NOTHING_TO_PUBLISH") { publish(meeting, note = "No change") }

        // The next meeting the same day (numbers run per calendar year), the chair agreeing in the app: the next number.
        val next = meeting(meeting.date)
        asUser(chair.account) { chairing.agree(next.id, version(next), false, null) }
        assertThat(draft(next).chairAgreement?.onBehalfBy).isNull()
        publish(next)
        assertThat(asUser(secretary.account) { reads.get(next.id) }.label).isEqualTo("BRC 2/${next.date.year}")
    }

    @Test
    fun `revision 2 flags a replaced paper and a moved item with its old number, and members read revision 1 until then`() {
        val meeting = meeting(LocalDate.now().plusDays(DAYS_AHEAD))
        val credit = addItem(meeting, "Credit facility for Acme", 30)
        addItem(meeting, "Liquidity report", 15)
        val fileId = uploadPaper(meeting, "credit.pdf")
        asUser(secretary.account) { papers.add(meeting.id, credit, version(meeting), PaperInput(fileId)) }
        asUser(chair.account) { chairing.agree(meeting.id, version(meeting), false, null) }
        publish(meeting)
        val first = latest(meeting)
        assertThat(first.items.single { it.itemId == credit }.number).isEqualTo("5")
        val firstVersion = first.papers.single().fileVersionId

        // A new version of the paper, and item 5 moved to position 3: the draft changes, members still read revision 1.
        val file = read("/api/v1/files/$fileId", secretary.session).json()
        val replaced =
            upload(
                secretary.session,
                Samples.pdf(2),
                "/api/v1/files/$fileId/versions",
                "credit-v2.pdf",
                mapOf("If-Match" to "\"${file["version"]}\""),
            )
        assertThat(replaced).hasStatus(HttpStatus.CREATED)
        val opening = draft(meeting).sections.first().id
        asUser(
            secretary.account,
        ) { moves.move(meeting.id, version(meeting), MoveInput(itemId = credit, toSectionId = opening, toPosition = 2)) }
        assertThat(draft(meeting).dirty).isTrue()
        assertThat(asUser(secretary.account) { revisions.heads(meeting.id) }.map { it.number }).containsExactly(1)
        assertThat(latest(meeting).papers.single().fileVersionId).isEqualTo(firstVersion)

        val preview = asUser(secretary.account) { publisher.preview(meeting.id) }.second
        assertThat(preview?.revision).isEqualTo(2)
        assertThat(
            preview
                ?.snapshot
                ?.items
                ?.single { it.itemId == credit }
                ?.changes,
        ).containsExactlyInAnyOrder(ItemFlag.MOVED, ItemFlag.PAPER_REPLACED)

        assertThat(publish(meeting, note = "Updated credit paper", notify = false)).isEqualTo(2)
        val second = latest(meeting)
        val moved = second.items.single { it.itemId == credit }
        assertThat(moved.number to moved.previousNumber).isEqualTo("3" to "5")
        assertThat(moved.changes).containsExactlyInAnyOrder(ItemFlag.MOVED, ItemFlag.PAPER_REPLACED)
        assertThat(second.items.filter { it.changes.isNotEmpty() }.map { it.itemId }).containsExactly(credit)
        assertThat(second.papers.single().changes).containsExactly(PaperFlag.REPLACED)
        assertThat(second.papers.single().fileVersionId).isNotEqualTo(firstVersion)
        val heads = asUser(secretary.account) { revisions.heads(meeting.id) }
        assertThat(heads.map { it.note to it.notified }).containsExactly(null to true, "Updated credit paper" to false)
        // Revision 1 is unchanged.
        assertThat(asUser(secretary.account) { revisions.snapshot(heads.first().id) }.papers.single().fileVersionId).isEqualTo(firstVersion)
        assertAppendOnly(meeting)
    }

    @Test
    fun `late items wait for the chair, who agrees them into the next revision or declines them back to the tray`() {
        // The preset's late mode is CHAIR_APPROVAL: a meeting tomorrow is past its five-working-day deadline.
        val meeting = meeting(LocalDate.now().plusDays(1))
        val (_, waiting) = escalated("Urgent facility")
        val (_, declined) = escalated("Late restructuring")
        place(meeting, waiting)
        place(meeting, declined)
        val first = draft(meeting)
        val waitingItem = first.submission(waiting)
        val declinedItem = first.submission(declined)
        assertThat(listOf(waitingItem.late, declinedItem.late)).containsOnly(Lateness.AWAITING_CHAIR)

        asUser(chair.account) { chairing.agree(meeting.id, version(meeting), false, null) }
        val preview = asUser(secretary.account) { publisher.preview(meeting.id) }.second
        assertThat(preview?.awaiting?.map { it.first.id }).containsExactlyInAnyOrder(waitingItem.id, declinedItem.id)
        publish(meeting)
        assertThat(latest(meeting).items.map { it.itemId }).doesNotContain(waitingItem.id, declinedItem.id)

        // The secretary cannot answer in the chair's name without saying how; a member cannot answer at all.
        assertThatThrownBy {
            asUser(
                director.account,
            ) { chairing.decideItem(meeting.id, waitingItem.id, version(meeting), LateDecision(true)) }
        }.isInstanceOf(ForbiddenException::class.java)
        assertThat(
            problem {
                asUser(secretary.account) { chairing.decideItem(meeting.id, declinedItem.id, version(meeting), LateDecision(false, true)) }
            },
        ).isEqualTo("note:NOTE_REQUIRED")

        asUser(chair.account) { chairing.decideItem(meeting.id, waitingItem.id, version(meeting), LateDecision(true)) }
        Audit.assertAudited(tenant.id, "meeting.late.agreed", meeting.id.toString(), chair.id.toString())
        asUser(secretary.account) {
            chairing.decideItem(meeting.id, declinedItem.id, version(meeting), LateDecision(false, true, "By phone on 11 Dec"))
        }
        Audit.assertAudited(tenant.id, "meeting.late.declined", meeting.id.toString(), secretary.id.toString())
        refused(
            "WRONG_STATE",
        ) { asUser(chair.account) { chairing.decideItem(meeting.id, waitingItem.id, version(meeting), LateDecision(false)) } }

        // Declined: off the agenda, back in the tray, still accepted.
        val back = asUser(secretary.account) { submissions.get(brc, declined) }
        assertThat(back.status to back.meetingId).isEqualTo(SubmissionStatus.ACCEPTED to null)
        AppRoleJdbc.inTransaction(tenant.id) { c ->
            assertThat(
                c.long("select count(*) from meeting_item where id = ? and removed_reason = 'CHAIR_DECLINED'", declinedItem.id),
            ).isEqualTo(1)
        }
        assertThat(draft(meeting).submission(waiting).late).isEqualTo(Lateness.CHAIR_AGREED)

        // Agreed: in the next revision, added and late.
        publish(meeting, note = "The chair agreed a late paper")
        val agreed = latest(meeting).items.single { it.itemId == waitingItem.id }
        assertThat(agreed.changes).containsExactlyInAnyOrder(ItemFlag.ADDED, ItemFlag.LATE)
        assertThat(agreed.late).isEqualTo(PublishedLate.LATE)

        // A late upload waits on the paper; the chair agrees it.
        val fileId = uploadPaper(meeting, "annex.pdf")
        asUser(secretary.account) { papers.add(meeting.id, waitingItem.id, version(meeting), PaperInput(fileId)) }
        val paper = draft(meeting).submission(waiting).papers.single { it.fileId == fileId }
        assertThat(paper.late).isEqualTo(Lateness.AWAITING_CHAIR)
        asUser(chair.account) { chairing.decidePaper(meeting.id, paper.id, version(meeting), LateDecision(true)) }
        assertThat(
            draft(meeting)
                .submission(waiting)
                .papers
                .single { it.id == paper.id }
                .late,
        ).isEqualTo(Lateness.CHAIR_AGREED)
    }

    @Test
    fun `the chair comments to the secretaries, and a published item that leaves tells them to publish again`() {
        val meeting = meeting(LocalDate.now().plusDays(DAYS_AHEAD))
        val (_, submission) = escalated("Market risk limits")
        place(meeting, submission)

        assertThatThrownBy { asUser(secretary.account) { chairing.comment(meeting.id, "Please add the liquidity paper") } }
            .isInstanceOf(ForbiddenException::class.java)
        assertThat(problem { asUser(chair.account) { chairing.comment(meeting.id, "  ") } }).isEqualTo("text:LENGTH")
        asUser(chair.account) { chairing.comment(meeting.id, "Please add the liquidity paper") }
        Audit.assertAudited(tenant.id, "meeting.agenda.chair_commented", meeting.id.toString(), chair.id.toString())
        assertThat(draft(meeting).chairComments.map { it.text to it.by?.id }).containsExactly("Please add the liquidity paper" to chair.id)
        assertThat(notified(secretary, "MEETING_CHAIR_COMMENTED")).isEqualTo(1)

        asUser(chair.account) { chairing.agree(meeting.id, version(meeting), false, null) }
        publish(meeting)
        // The submission is returned in the queue (by an administrator): it leaves the draft, and the secretary is told.
        asUser(admin) { triage.returnForInformation(brc, submission, submissions.get(brc, submission).version, "Add the stress test") }
        assertThat(draft(meeting).sections.flatMap { it.items }.none { it.submissionId == submission }).isTrue()
        assertThat(notified(secretary, "MEETING_ITEM_WITHDRAWN")).isEqualTo(1)
        // Members still read it in revision 1 until the next one.
        assertThat(latest(meeting).items.any { it.submissionId == submission }).isTrue()
        publish(meeting, note = "The market risk paper was withdrawn")
        val removed = latest(meeting).items.single { it.submissionId == submission }
        assertThat(removed.removed to removed.changes).isEqualTo(true to setOf(ItemFlag.REMOVED))
    }

    @Test
    fun `over HTTP publishing needs If-Match and an Idempotency-Key, answers the draft, and names the papers it cannot read`() {
        val meeting = meeting(LocalDate.now().plusDays(DAYS_AHEAD))
        val item = addItem(meeting, "Strategy deck", 20)
        val deck = uploadPaper(meeting, "strategy.pptx", Samples.pptx())
        asUser(secretary.account) { papers.add(meeting.id, item, version(meeting), PaperInput(deck)) }
        val agreed = send(HttpMethod.POST, "/api/v1/meetings/${meeting.id}/agenda/chair-agreement", chair, "{}", version(meeting))
        assertThat(agreed).hasStatusOk()

        assertThat(
            send(HttpMethod.POST, "/api/v1/meetings/${meeting.id}/agenda/publish", secretary, "{}", null),
        ).hasStatus(HttpStatus.PRECONDITION_REQUIRED)
        val unreadable = send(HttpMethod.POST, "/api/v1/meetings/${meeting.id}/agenda/publish", secretary, "{}", version(meeting))
        assertThat(unreadable).hasStatus(HttpStatus.CONFLICT)
        assertThat(unreadable.json()["reason"].asString()).isEqualTo("PAPER_NOT_READABLE")
        val paperId =
            draft(meeting)
                .sections
                .flatMap { it.items }
                .single { it.id == item }
                .papers
                .single()
                .id
        assertThat(unreadable.json()["paperIds"][0].asString()).isEqualTo(paperId.toString())
        assertThatThrownBy { publish(meeting) }.isInstanceOf(PaperNotReadableException::class.java)
        val preview = read("/api/v1/meetings/${meeting.id}/agenda/publish-preview", secretary.session)
        assertThat(preview).hasStatusOk()
        assertThat(preview.json()["unreadablePapers"][0].asString()).isEqualTo(paperId.toString())
        assertThat(read("/api/v1/meetings/${meeting.id}/agenda/publish-preview", director.session)).hasStatus(HttpStatus.FORBIDDEN)

        asUser(secretary.account) { papers.remove(meeting.id, paperId, version(meeting)) }
        val stale = send(HttpMethod.POST, "/api/v1/meetings/${meeting.id}/agenda/publish", secretary, "{}", version(meeting) - 1)
        assertThat(stale).hasStatus(HttpStatus.PRECONDITION_FAILED)
        val key = UUID.randomUUID().toString()
        val ok = send(HttpMethod.POST, "/api/v1/meetings/${meeting.id}/agenda/publish", secretary, "{}", version(meeting), key)
        assertThat(ok).hasStatus(HttpStatus.CREATED)
        assertThat(ok.json()["currentRevision"].asInt()).isEqualTo(1)
        assertThat(ok.response.getHeader("ETag")).isEqualTo("\"${ok.json()["version"].asLong()}\"")
        assertThat(send(HttpMethod.POST, "/api/v1/meetings/${meeting.id}/agenda/publish", director, "{}", version(meeting)))
            .hasStatus(HttpStatus.FORBIDDEN)
        assertThat(send(HttpMethod.POST, "/api/v1/meetings/${meeting.id}/agenda/chair-comments", chair, """{"text":"Thank you"}""", null))
            .hasStatus(HttpStatus.CREATED)
    }

    /** The chair's agreement recorded by the secretary on the chair's behalf, after the refusals of who may and how. */
    private fun recordedOnBehalf(meeting: MeetingRow) {
        // Only the chair agrees in the app; only a secretary records it, saying how it was given.
        assertThatThrownBy { asUser(director.account) { chairing.agree(meeting.id, version(meeting), false, null) } }
            .isInstanceOf(ForbiddenException::class.java)
        assertThatThrownBy { asUser(secretary.account) { chairing.agree(meeting.id, version(meeting), false, null) } }
            .isInstanceOf(ForbiddenException::class.java)
        assertThatThrownBy { asUser(chair.account) { chairing.agree(meeting.id, version(meeting), true, "by phone") } }
            .isInstanceOf(ForbiddenException::class.java)
        assertThat(problem { asUser(secretary.account) { chairing.agree(meeting.id, version(meeting), true, " ") } })
            .isEqualTo("note:NOTE_REQUIRED")
        asUser(secretary.account) { chairing.agree(meeting.id, version(meeting), true, "By email on 4 Dec") }
        Audit.assertAudited(tenant.id, "meeting.agenda.chair_agreed", meeting.id.toString(), secretary.id.toString())
        val agreed = draft(meeting).chairAgreement
        assertThat(agreed?.by?.id to agreed?.onBehalfBy?.id).isEqualTo(chair.id to secretary.id)
        assertThat(agreed?.note).isEqualTo("By email on 4 Dec")
        refused("ALREADY_AGREED") { asUser(chair.account) { chairing.agree(meeting.id, version(meeting), false, null) } }
    }

    /** The copy of revision 1: numbers and times as the draft computed them, the memo's papers pinned to their current versions. */
    private fun assertCopied(
        meeting: MeetingRow,
        published: AgendaDraftResponse,
        submission: UUID,
    ) {
        val first = latest(meeting)
        val placed = published.submission(submission)
        val copied = first.items.single { it.itemId == placed.id }
        assertThat(copied.number).isEqualTo(placed.number)
        assertThat(copied.startsAt).isEqualTo(placed.startsAt)
        assertThat(first.items.map { it.number }).containsExactly("1", "2", "3", "4", "5", "6")
        assertThat(first.papers.map { it.paperId }).containsExactlyInAnyOrderElementsOf(placed.papers.map { it.id })
        AppRoleJdbc.inTransaction(tenant.id) { c ->
            val pinned =
                c.long(
                    "select count(*) from meeting_revision_paper p join files_file f on f.id = p.file_id " +
                        "where p.file_version_id = f.current_version_id and p.paper_id = ?",
                    placed.papers.first().id,
                )
            assertThat(pinned).isEqualTo(1)
        }
        assertThat(first.items.flatMap { it.changes }).isEmpty()
    }

    /** Revisions are append-only: the app role neither changes nor deletes them. */
    private fun assertAppendOnly(meeting: MeetingRow) {
        listOf("update meeting_revision set note = 'x' where meeting_id = ?", "delete from meeting_revision where meeting_id = ?").forEach {
            assertThat(
                runCatching {
                    AppRoleJdbc.inTransaction(tenant.id) { c ->
                        c.prepareStatement(it).use { s ->
                            s.setObject(1, meeting.id)
                            s.executeUpdate()
                        }
                    }
                }.exceptionOrNull(),
            ).describedAs(it)
                .isNotNull()
        }
    }

    /** A memo of alice's on [subject], submitted and reviewed: waiting for the committee. */
    private fun escalated(subject: String): Pair<String, UUID> {
        val level = levels()[0].id
        val id =
            create(
                alice.session,
                """{"subject":"$subject","typeId":"$memoType","classificationId":"$level"}""",
            ).json()["id"].asString()
        uploadDocument(id, alice.session)
        assertThat(post("/api/v1/memos/$id/submit", alice.session)).hasStatusOk()
        val task = get(id, reviewer.session).json()["myTask"]["id"].asString()
        assertThat(post("/api/v1/tasks/$task/approve", reviewer.session)).hasStatusOk()
        val submission = asUser(secretary.account) { submissions.queue(brc, emptySet()) }.single { it.subjectId.toString() == id }
        return id to submission.id
    }

    /** A meeting of the committee on [day], scheduled unless said otherwise, its agenda started from the template. */
    private fun meeting(
        day: LocalDate,
        schedule: Boolean = true,
    ): MeetingRow {
        val created =
            asUser(secretary.account) {
                val created =
                    lifecycle.create(
                        brc,
                        MeetingInput(MeetingKind.ORDINARY, MeetingPlace(null, MeetingMode.IN_PERSON, "Board room", null, null), null),
                        TimesInput(day.atTime(LocalTime.of(10, 0)), day.atTime(LocalTime.of(13, 0)), ZONE.id),
                    )
                starts.start(created.id, AgendaOrigin.TEMPLATE, null)
                created
            }
        if (schedule) schedule(created)
        return created
    }

    private fun schedule(meeting: MeetingRow) {
        val current = asUser(secretary.account) { reads.get(meeting.id) }
        asUser(secretary.account) { lifecycle.schedule(meeting.id, current.version) }
    }

    private fun addItem(
        meeting: MeetingRow,
        title: String,
        minutes: Int,
    ): UUID =
        asUser(secretary.account) { items.add(meeting.id, version(meeting), ItemInput(ItemKind.ITEM, title = title, minutes = minutes)) }

    private fun uploadPaper(
        meeting: MeetingRow,
        name: String,
        bytes: ByteArray = Samples.pdf(),
    ): UUID {
        val uploaded = upload(secretary.session, bytes, "/api/v1/files?purpose=${MeetingPaperPurpose.KEY}&owner=${meeting.id}", name)
        assertThat(uploaded).hasStatus(HttpStatus.CREATED)
        return UUID.fromString(uploaded.json()["fileId"].asString())
    }

    private fun place(
        meeting: MeetingRow,
        submission: UUID,
    ) = asUser(secretary.account) { items.add(meeting.id, version(meeting), ItemInput(ItemKind.SUBMISSION, submissionId = submission)) }

    private fun publish(
        meeting: MeetingRow,
        note: String? = null,
        notify: Boolean = true,
    ): Int = asUser(secretary.account) { publisher.publish(meeting.id, version(meeting), note, notify) }

    private fun latest(meeting: MeetingRow): Snapshot =
        asUser(secretary.account) { revisions.snapshot(revisions.heads(meeting.id).last().id) }

    private fun draft(meeting: MeetingRow): AgendaDraftResponse = asUser(secretary.account) { views.draft(meeting.id) }

    private fun version(meeting: MeetingRow): Long = draft(meeting).version

    private fun AgendaDraftResponse.submission(id: UUID): AgendaItemResponse =
        sections.flatMap { it.items }.single { it.submissionId == id }

    private fun send(
        method: HttpMethod,
        uri: String,
        person: Person,
        body: String,
        version: Long?,
        key: String = UUID.randomUUID().toString(),
    ) = mvc.sendJson(
        method,
        tenant,
        uri,
        person.session,
        body,
        mapOf("Idempotency-Key" to key) + version?.let { mapOf("If-Match" to "\"$it\"") }.orEmpty(),
    )

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
        val rejected = runCatching { block() }.exceptionOrNull() as? MeetingRejectedException ?: error("expected a rejection")
        return rejected.problems.joinToString { "${it.field}:${it.code}" }
    }

    private companion object {
        const val KEY = "board-risk"
        const val DAYS_AHEAD = 40L
        val ZONE: ZoneId = ZoneId.of("Asia/Dhaka")
    }
}
