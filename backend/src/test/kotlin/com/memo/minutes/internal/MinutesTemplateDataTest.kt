package com.memo.minutes.internal

import com.memo.forum.ForumRole
import com.memo.forum.MinutesLanguage
import com.memo.forum.MinutesTemplateTags
import com.memo.forum.MinutesTemplates
import com.memo.forum.OutcomeKind
import com.memo.forum.SubmissionPurpose
import com.memo.meeting.AgendaItemDetails
import com.memo.meeting.AgendaItemKind
import com.memo.meeting.AgendaItemRef
import com.memo.meeting.AttendanceCapacity
import com.memo.meeting.AttendanceMode
import com.memo.meeting.AttendanceState
import com.memo.meeting.AttendeeRecord
import com.memo.meeting.DeclarationHow
import com.memo.meeting.DeclarationIntent
import com.memo.meeting.DeclarationRef
import com.memo.meeting.DeclarationSource
import com.memo.meeting.DeclarationState
import com.memo.meeting.ItemLateness
import com.memo.meeting.ItemPaper
import com.memo.meeting.ItemPresenter
import com.memo.meeting.MeetingAttendanceRecord
import com.memo.meeting.MeetingKind
import com.memo.meeting.MeetingMode
import com.memo.meeting.MeetingRef
import com.memo.meeting.MeetingStatus
import com.memo.meeting.QuorumChangeKind
import com.memo.meeting.QuorumChangeRecord
import com.memo.minutes.MinutesActionLine
import com.memo.templating.internal.PoiDocxControls
import com.memo.templating.internal.PoiTlFiller
import com.memo.testsupport.Docx
import net.jqwik.api.Arbitraries
import net.jqwik.api.Arbitrary
import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.Provide
import org.apache.poi.xwpf.usermodel.XWPFDocument
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/**
 * The draft's data (design E06 §1.7; test plan T5): the meeting's values in its zone, the attendance tables, one block per
 * minuted item in agenda order with its control, the declaration and "took no part" sentences (with the alternate standing
 * in for the recused member), the decision's vote, dissent, conditions and number, the notes, and E07's actions. The
 * property (the test plan's fuzz target, run as jqwik here) fills the built-in template with names, titles, notes and text
 * carrying XML metacharacters, control characters and 10,000-character notes: the document parses, has one control per
 * item and nothing injected.
 */
class MinutesTemplateDataTest {
    private val zone = ZoneId.of("Asia/Dhaka")
    private val chair = UUID.randomUUID()
    private val member = UUID.randomUUID()
    private val recused = UUID.randomUUID()
    private val alternate = UUID.randomUUID()
    private val absent = UUID.randomUUID()
    private val loan = item("1", "Term loan, Delta Textiles", AgendaItemKind.SUBMISSION)
    private val report = item("2", "Quarterly risk report", AgendaItemKind.ITEM)
    private val waiting = item(null, "Waiting for the chair", AgendaItemKind.ITEM)

    @Test
    fun `the meeting, the attendance, each item in its control and the decision's sentences`() {
        val data = MinutesTemplateData.of(source())
        assertThat(data.values["meeting.label"]).isEqualTo("BRC 3/2026")
        assertThat(data.values["meeting.date"]).isEqualTo("12 December 2026")
        assertThat(data.values["meeting.start"] to data.values["meeting.closedAt"]).isEqualTo("10:00" to "12:30")
        assertThat(data.values["meeting.chair"]).isEqualTo("Dr A. Rahman")
        assertThat(data.values["minutes.status"]).isEqualTo("DRAFT")
        assertThat(data.values["quorum.opening"]).isEqualTo("The meeting was quorate (4 present, 3 needed).")
        assertThat(data.values.keys).containsExactlyInAnyOrderElementsOf(MinutesTemplateTags.MEETING.keys)
        assertThat(data.rows.keys).containsExactlyInAnyOrderElementsOf(MinutesTemplateTags.LOOPS.keys)
        assertThat(data.rows.getValue("present").map { it["name"] to it["role"] })
            .containsExactly("Dr A. Rahman" to "Chair", "Mr T. Hossain" to "Member", "Ms S. Karim" to "Member")
        assertThat(data.rows.getValue("apologies").single()).isEqualTo(mapOf("name" to "Mr K. Islam", "alternate" to "Mr J. Ahmed"))
        assertThat(data.rows.getValue("alternates").single()["forMember"]).isEqualTo("Mr K. Islam")
        assertThat(data.rows.getValue("resolutions").single()["number"]).isEqualTo("BRC/2026/001")
        // The meeting's table lists its own actions only; the loan's is in the loan's block (E06 T7: copies redact it with the item).
        assertThat(data.rows.getValue("actions").map { it["reference"] to it["item"] }).containsExactly("ACT/2026/002" to "")
        assertThat(data.values["actions.count"]).isEqualTo("1")

        // The item waiting for the chair was never taken: no block.
        val items = data.blocks.getValue(MinutesTemplateTags.ITEMS)
        assertThat(items.map { it.control?.tag }).containsExactly(MinutesStructure.tag(loan.id), MinutesStructure.tag(report.id))
        assertThat(items.map { it.control?.title }).containsExactly("Item 1", "Item 2")
        val first = items.first().values
        assertThat(first.keys).containsExactlyInAnyOrderElementsOf(MinutesTemplateTags.ITEM.keys)
        assertThat(first["item.papers"]).isEqualTo("Credit memorandum — circulated in advance\nValuation — tabled at the meeting")
        assertThat(first["item.declarations"]).isEqualTo(
            "Mr T. Hossain declared an interest (A family member is a director) and left the meeting for this item.\n" +
                "Mr K. Islam declared an interest (Shareholder) and left the meeting for this item.",
        )
        assertThat(first["item.tookNoPart"]).isEqualTo("Mr T. Hossain and Mr J. Ahmed took no part in this item.")
        assertThat(first["item.outcome"]).isEqualTo("APPROVED WITH CONDITIONS")
        assertThat(first["item.conditions"]).isEqualTo("1. Security perfected.\n2. Quarterly report.")
        assertThat(first["item.vote"]).isEqualTo("For 2, against 1, abstaining 0")
        assertThat(first["item.dissent"]).isEqualTo("Ms S. Karim asked that their dissent be recorded: the tenor is too long.")
        assertThat(first["item.notes"]).isEqualTo("Members challenged the outlook.")
        assertThat(first["item.actionCount"]).isEqualTo("1")
        assertThat(
            items
                .first()
                .rows
                .getValue(MinutesTemplateTags.ITEM_ACTIONS)
                .single()["reference"],
        ).isEqualTo("ACT/2026/001")
        // An undecided item: its decision's tags are blank, so their sections disappear.
        val second = items.last().values
        assertThat(listOf("item.outcome", "item.vote", "item.tookNoPart", "item.actionCount").map { second[it] }).containsOnly("")
        assertThat(second["item.purpose"]).isEqualTo("For noting")
    }

    @Test
    fun `characters XML cannot carry are dropped, markup stays text`() {
        assertThat(MinutesTemplateData.clean("a\u0000b\u0007c\td\r\ne￾f")).isEqualTo("abc\td\nef")
        assertThat(MinutesTemplateData.clean("<w:p>{{item.notes}} & 😀")).isEqualTo("<w:p>{{item.notes}} & 😀")
    }

    @Property(tries = 25)
    fun `any names, titles, notes and text fill the built-in template into one control per item`(
        @ForAll("hostile") title: String,
        @ForAll("hostile") name: String,
        @ForAll("long") notes: String,
    ) {
        val data = MinutesTemplateData.of(source(title = title, name = name, notes = notes))
        val filled = filler.fill(template.inputStream(), data)
        XWPFDocument(filled.inputStream()).use { assertThat(it.paragraphs).isNotEmpty() }
        val found = controls.scan(filled.inputStream())
        assertThat(found.map { it.tag }).containsExactly(MinutesStructure.tag(loan.id), MinutesStructure.tag(report.id))
        assertThat(found).allSatisfy { assertThat(it.bodyLevel && it.lock == "sdtLocked").isTrue() }
        assertThat(Docx.text(filled)).contains(MinutesTemplateData.clean(title).trim().take(PROBE))
    }

    @Provide
    fun hostile(): Arbitrary<String> =
        Arbitraries
            .strings()
            .withChars("<>&\"'{}[]?/=\u0000\u0001\u0008\u000B\u001F￾\uD800")
            .withCharRange('a', 'z')
            .ofMinLength(1)
            .ofMaxLength(80)
            .map { "T$it" }

    @Provide
    fun long(): Arbitrary<String> =
        Arbitraries
            .strings()
            .withCharRange(' ', '~')
            .ofMinLength(0)
            .ofMaxLength(10_000)

    private fun item(
        number: String?,
        title: String,
        kind: AgendaItemKind,
    ) = AgendaItemRef(
        id = UUID.randomUUID(),
        parentId = null,
        sectionId = SECTION,
        number = number,
        title = title,
        titleHidden = false,
        kind = kind,
        standingKind = null,
        purpose = if (kind == AgendaItemKind.SUBMISSION) SubmissionPurpose.DECISION else SubmissionPurpose.INFORMATION,
        resolutionSought = null,
        submissionId = null,
        memoId = null,
        restrictedTo = null,
        consent = false,
        lifted = false,
        published = true,
        tabled = false,
    )

    private fun person(
        id: UUID,
        state: AttendanceState,
        capacity: AttendanceCapacity = AttendanceCapacity.MEMBER,
        forUser: UUID? = null,
    ) = AttendeeRecord(
        userId = id,
        capacity = capacity,
        forMemberId = null,
        forUserId = forUser,
        state = state,
        mode = if (state == AttendanceState.PRESENT) AttendanceMode.IN_PERSON else null,
        firstArrivedAt = if (state == AttendanceState.PRESENT) START else null,
        lastLeftAt = null,
        arrivedDuringItem = null,
        leftDuringItem = null,
        joinedLate = false,
        leftEarly = false,
        steppedOutOf = emptyList(),
    )

    private val decisionId = UUID.randomUUID()

    private fun source(
        title: String = loan.title,
        name: String = "Ms S. Karim",
        notes: String = "Members challenged the outlook.",
    ) = DraftSource(
        meeting = meeting(),
        committee = "Board Risk Committee",
        heldAt = START.plusSeconds(TWO_AND_A_HALF_HOURS),
        items = listOf(loan.copy(title = title), report, waiting),
        details = mapOf(loan.id to details()),
        attendance = attendance(),
        roles = mapOf(chair to ForumRole.CHAIR, member to ForumRole.MEMBER, recused to ForumRole.MEMBER, absent to ForumRole.MEMBER),
        // Mr K. Islam sent apologies and declared an interest too: his alternate stands in and takes no part.
        declarations = listOf(declaration(recused, "A family member is a director."), declaration(absent, "Shareholder.")),
        decisions =
            mapOf(
                loan.id to CurrentDecision(DecisionRow(decisionId, MEETING, loan.id, FORUM, null, null, 1, null, null, null, 0), entry()),
            ),
        resolutions = mapOf(decisionId to "BRC/2026/001"),
        snapshots = emptyMap(),
        ratified = emptyMap(),
        notes = mapOf(loan.id to notes),
        actions =
            listOf(
                MinutesActionLine(loan.id, "ACT/2026/001", "Perfect the security", "Head of Credit", "31 January 2027"),
                MinutesActionLine(null, "ACT/2026/002", "Review the terms of reference", "Company Secretary", "28 February 2027"),
            ),
        names =
            mapOf(chair to "Dr A. Rahman", member to name, recused to "Mr T. Hossain", absent to "Mr K. Islam", alternate to "Mr J. Ahmed"),
    )

    private fun meeting() =
        MeetingRef(
            MEETING,
            FORUM,
            1,
            MeetingKind.ORDINARY,
            null,
            START,
            START.plusSeconds(THREE_HOURS),
            zone,
            MeetingMode.IN_PERSON,
            UUID.randomUUID(),
            "BRC 3/2026",
            MeetingStatus.HELD,
            3,
            "Board room",
        )

    private fun details() =
        AgendaItemDetails(
            loan.id,
            "Matters for decision",
            listOf(ItemPresenter(null, "Head of Credit")),
            listOf(ItemPaper("Credit memorandum", ItemLateness.NONE), ItemPaper("Valuation", ItemLateness.TABLED)),
            emptySet(),
            ItemLateness.NONE,
        )

    private fun attendance() =
        MeetingAttendanceRecord(
            MEETING,
            listOf(
                person(chair, AttendanceState.PRESENT),
                person(member, AttendanceState.PRESENT),
                person(recused, AttendanceState.PRESENT),
                person(absent, AttendanceState.APOLOGIES),
                person(alternate, AttendanceState.PRESENT, AttendanceCapacity.ALTERNATE, forUser = absent),
            ),
            emptyList(),
            emptyList(),
            listOf(QuorumChangeRecord(QuorumChangeKind.REACHED, START, null, 3, 4)),
            null,
        )

    private fun declaration(
        user: UUID,
        nature: String,
    ) = DeclarationRef(
        UUID.randomUUID(),
        MEETING,
        loan.id,
        user,
        nature,
        DeclarationIntent.LEAVE,
        DeclarationState.RECUSED,
        DeclarationSource.SELF,
        DeclarationHow.IN_APP,
        START,
        true,
        null,
    )

    private fun entry() =
        EntryRow(
            decisionId = decisionId,
            seq = 1,
            outcomeKind = OutcomeKind.APPROVED_WITH_CONDITIONS,
            outcomeLabel = "Approved with conditions",
            resolution = true,
            text = "It was RESOLVED that the loan be approved.",
            conditions = listOf("Security perfected.", "Quarterly report."),
            validUntil = null,
            deferTo = null,
            referToForumId = null,
            referReason = null,
            voteMode = VoteMode.VOTE,
            votesFor = 2,
            votesAgainst = 1,
            votesAbstain = 0,
            dissent = listOf(Dissent(member, "the tenor is too long.")),
            abstentions = emptyList(),
            tookNoPart = listOf(recused),
            snapshotId = UUID.randomUUID(),
            ratificationRequired = false,
            ratifiesDecisionId = null,
            decidedAt = START,
            consentBatchId = null,
            correction = false,
            reason = null,
            recordedBy = chair,
            recordedAt = START,
        )

    private companion object {
        val MEETING: UUID = UUID.randomUUID()
        val FORUM: UUID = UUID.randomUUID()
        val SECTION: UUID = UUID.randomUUID()
        val START: Instant = Instant.parse("2026-12-12T04:00:00Z")
        const val THREE_HOURS = 3 * 3600L
        const val TWO_AND_A_HALF_HOURS = 9000L
        const val PROBE = 20
        val filler = PoiTlFiller()
        val controls = PoiDocxControls()
        val template: ByteArray = MinutesTemplates().builtIn(MinutesLanguage.EN)
    }
}
