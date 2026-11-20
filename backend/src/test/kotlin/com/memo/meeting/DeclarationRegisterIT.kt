package com.memo.meeting

import com.memo.forum.ForumRole
import com.memo.forum.QuorumRule
import com.memo.forum.UnmetKind
import com.memo.forum.internal.ForumInput
import com.memo.forum.internal.ForumTypeLifecycle
import com.memo.forum.internal.ForumTypePreset
import com.memo.forum.internal.MemberInput
import com.memo.meeting.internal.AgendaOrigin
import com.memo.meeting.internal.DeclarationRegister
import com.memo.meeting.internal.DeclarationRegisters
import com.memo.meeting.internal.ItemChange
import com.memo.meeting.internal.ItemInput
import com.memo.meeting.internal.ItemKind
import com.memo.meeting.internal.MeetingInput
import com.memo.meeting.internal.MeetingPlace
import com.memo.meeting.internal.MeetingRow
import com.memo.meeting.internal.TimesInput
import com.memo.testsupport.Audit
import com.memo.testsupport.Person
import com.memo.testsupport.aPassword
import com.memo.testsupport.anActiveUser
import com.memo.testsupport.sendJson
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.test.web.servlet.assertj.MvcTestResult
import tools.jackson.databind.JsonNode
import java.io.ByteArrayInputStream
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

/**
 * The registers of declarations and the reminder (test plan E04, T4; design E04 §1.5, §1.8, §3, §4, flows T1 and U1):
 *  - the meeting register: natures per item, the review per member, a recused officer's own row only, quorum warnings
 *    with `recusedCount` off and on, and who may read it;
 *  - "Remind them now": once per person and revision, to those with items to review, audited;
 *  - the register across committees: who reads which committees, titles by the title rule and natures only with them,
 *    a recused officer's own row only, keyset paging;
 *  - its export: the rows on screen, "Restricted item" in their place, audited with filter and count, rate-limited.
 */
class DeclarationRegisterIT : DeclarationFixture() {
    @Autowired lateinit var register: DeclarationRegister

    @Autowired lateinit var registers: DeclarationRegisters

    @Autowired lateinit var typeVersions: ForumTypeLifecycle

    @Test
    fun `the meeting register lists natures by item and the review per member, and a recused officer sees only their own row`() {
        val meeting = meeting()
        val loan = addItem(meeting, TITLE)
        val limits = addItem(meeting, "Market risk limits")
        publish(meeting)
        declare(director, meeting, loan)
        declare(colleague, meeting, limits, "Non-executive director of a counterparty", DeclarationIntent.ASK_CHAIR)
        declare(secretary, meeting, limits, "Spouse works for the counterparty")
        asUser(colleague.account) { reviews.review(meeting.id, 1) }

        val bySecretary = registerOf(secretary, meeting)
        assertThat(bySecretary["canRemind"].asBoolean()).isTrue()
        val loanRow = bySecretary.item(loan)
        assertThat(loanRow["title"].asString()).isEqualTo(TITLE)
        assertThat(loanRow["declarations"].list().map { it["nature"].asString() }).containsExactly(NATURE)
        // The secretary is recused from the limits item: only their own declaration, and no warning.
        val limitsRow = bySecretary.item(limits)
        assertThat(limitsRow["recused"].asBoolean()).isTrue()
        assertThat(limitsRow["declarations"].list().map { it["user"]["id"].asString() }).containsExactly(secretary.id.toString())
        assertThat(bySecretary.toString()).doesNotContain("Non-executive director of a counterparty")

        // The chair sees both declarations on it, with their natures, but cannot send the reminder.
        val byChair = registerOf(chair, meeting)
        assertThat(byChair["canRemind"].asBoolean()).isFalse()
        assertThat(byChair.item(limits)["declarations"].list().map { it["nature"].asString() })
            .containsExactlyInAnyOrder("Non-executive director of a counterparty", "Spouse works for the counterparty")

        // The review per member: the colleague confirmed revision 1; the director has both items to review.
        val reviews = byChair["reviews"].list().associateBy { UUID.fromString(it["user"]["id"].asString()) }
        assertThat(reviews.keys).contains(secretary.id, chair.id, vice.id, director.id, colleague.id).doesNotContain(guest.id)
        assertThat(reviews.getValue(colleague.id)["reviewedRevision"].asInt() to reviews.getValue(colleague.id)["toReview"].asInt())
            .isEqualTo(1 to 0)
        assertThat(reviews.getValue(director.id).absent("reviewedRevision")).isTrue()
        assertThat(reviews.getValue(director.id)["toReview"].asInt()).isEqualTo(2)
        assertThat(reviews.getValue(secretary.id)["officer"].asBoolean()).isTrue()

        // Members, guests and forum.read holders are 403; someone outside the meeting 404.
        listOf(director, guest, reader).forEach { assertThat(readRegister(it, meeting)).hasStatus(HttpStatus.FORBIDDEN) }
        assertThat(readRegister(outsider, meeting)).hasStatus(HttpStatus.NOT_FOUND)
    }

    @Test
    fun `an item that cannot be quorate even with every member present is warned of, unless members with an interest count`() {
        val quorumType = asUser(admin) { forumTypes.create("QT", "Quorum committee", ForumTypePreset.BOARD_COMMITTEE) }.type.id
        val checker = accounts.anActiveUser(transactions, tenant, aPassword(), tenantAdmin = true)

        fun publishRule(
            number: Int,
            recusedCount: Boolean,
        ) {
            val draft = asUser(admin) { if (number == 1) typeVersions.get(quorumType, 1) else typeVersions.newDraft(quorumType) }
            val rule = QuorumRule(minimum = 2, recusedCount = recusedCount)
            val saved = asUser(admin) { typeVersions.replace(quorumType, number, draft.revision, draft.settings.copy(quorum = rule)) }
            asUser(admin) { typeVersions.submit(quorumType, number, saved.revision) }
            asUser(checker) { typeVersions.approve(quorumType, number, null) }
        }
        publishRule(1, recusedCount = false)
        val qc = asUser(admin) { forums.create(ForumInput("quorum", "QC", "Quorum Committee", "QC", quorumType)) }.id
        val voting = listOf(chair, vice) + (1..5).map { person() }
        asUser(admin) {
            members.add(qc, MemberInput(userId = secretary.id, role = ForumRole.SECRETARY))
            members.add(qc, MemberInput(userId = chair.id, role = ForumRole.CHAIR))
            members.add(qc, MemberInput(userId = vice.id, role = ForumRole.VICE_CHAIR))
            voting.drop(2).forEach { members.add(qc, MemberInput(userId = it.id)) }
        }
        val strict = meetingOf(qc)
        publishRule(2, recusedCount = true)
        val lenient = meetingOf(qc)

        listOf(strict, lenient).forEach { meeting ->
            // 4 of the 7 voting members declared on item 5 (recorded by the secretary); 3 cannot make the 4 needed.
            val item = addItem(meeting, TITLE)
            addItem(meeting, "Another item")
            publish(meeting)
            voting.drop(3).forEach { record(secretary, meeting, item, it) }
        }
        val warning = registerOf(secretary, strict).item(registerItem(strict))["quorumWarning"]
        assertThat(listOf("declared", "basis", "needed", "available").map { warning[it].asInt() }).containsExactly(4, 7, 4, 3)
        assertThat(warning["recusedCount"].asBoolean()).isFalse()
        assertThat(warning["unmet"].list().map { it["kind"].asString() }).containsExactly(UnmetKind.COUNT.name)
        assertThat(registerOf(secretary, lenient).item(registerItem(lenient)).absent("quorumWarning")).isTrue()
        // The other item has no declaration: it is not in the register.
        assertThat(registerOf(secretary, strict)["items"].list()).hasSize(1)
    }

    @Test
    fun `remind them now tells those with items to review, once per person and revision, and only a secretary sends it`() {
        val unpublished = meeting()
        addItem(unpublished, "Capital plan")
        assertThat(remind(secretary, unpublished).problemReason()).isEqualTo("NOT_PUBLISHED")

        val meeting = meeting()
        val loan = addItem(meeting, TITLE)
        publish(meeting)
        asUser(colleague.account) { reviews.review(meeting.id, 1) }
        val first = remind(secretary, meeting)
        assertThat(first).hasStatusOk()
        // The members and officers who have not confirmed, never the secretary sending it, the colleague or the guest.
        assertThat(first.reminded()).containsExactlyInAnyOrder(chair.id, vice.id, director.id)
        assertThat(notified(director, DUE)).isEqualTo(1)
        assertThat(notified(colleague, DUE) + notified(guest, DUE) + notified(secretary, DUE)).isZero()
        val audited = Audit.assertAudited(tenant.id, "meeting.reminded.declarations_due", meeting.id.toString(), secretary.id.toString())
        assertThat(audited.canonical).contains("\"manual\":true").contains("\"count\":3")
        assertThat(remind(secretary, meeting).reminded()).isEmpty()

        // Revision 2 adds an item: those not yet reminded of it are reminded again, the colleague included.
        addItem(meeting, "Liquidity stress results")
        publish(meeting, "A new item")
        assertThat(remind(secretary, meeting).reminded()).containsExactlyInAnyOrder(chair.id, vice.id, director.id, colleague.id)
        assertThat(notified(director, DUE)).isEqualTo(2)
        val reminded = registerOf(secretary, meeting)["reviews"].list().single { it["user"]["id"].asString() == director.id.toString() }
        assertThat(reminded["remindedRevision"].asInt()).isEqualTo(2)
        declare(director, meeting, loan)

        // Only a secretary or forums.manage sends it; a held meeting is not reminded.
        listOf(chair, director).forEach { assertThat(remind(it, meeting)).hasStatus(HttpStatus.FORBIDDEN) }
        startMeeting(meeting)
        endMeeting(meeting)
        assertThat(remind(secretary, meeting).problemReason()).isEqualTo("WRONG_STATE")
    }

    @Test
    fun `the register across committees shows each reader the committees in scope, titles by the title rule and natures with them`() {
        val alcoSecretary = person()
        val alcoChair = person()
        val alcoMember = person()
        val alco = asUser(admin) { forums.create(ForumInput("alco", "ALCO", "Asset-Liability Committee", "ALCO", forumType())) }.id
        asUser(admin) {
            members.add(alco, MemberInput(userId = alcoSecretary.id, role = ForumRole.SECRETARY))
            members.add(alco, MemberInput(userId = alcoChair.id, role = ForumRole.CHAIR))
            members.add(alco, MemberInput(userId = alcoMember.id))
        }
        val brcMeeting = meeting()
        val loan = addItem(brcMeeting, TITLE)
        val hidden = addItem(brcMeeting, HIDDEN_TITLE)
        val restricted = addItem(brcMeeting, RESTRICTED_TITLE)
        change(brcMeeting, hidden, ItemChange(HIDDEN_TITLE, titleHidden = true))
        change(brcMeeting, restricted, ItemChange(RESTRICTED_TITLE, restrictedTo = listOf(director.id)))
        publish(brcMeeting)
        declare(director, brcMeeting, loan)
        declare(colleague, brcMeeting, hidden, HIDDEN_NATURE)
        declare(director, brcMeeting, restricted, RESTRICTED_NATURE)
        val alcoMeeting = meetingOf(alco, alcoSecretary, alcoChair)
        val rates = addItem(alcoMeeting, "Interest rate outlook", alcoSecretary)
        publish(alcoMeeting, secretaryPerson = alcoSecretary, chairPerson = alcoChair)
        declare(alcoMember, alcoMeeting, rates, "Holds bonds of the issuer")

        // forum.read: every committee; titles and natures only where the title rule lets them read them.
        val byReader = rowsOf(reader)
        assertThat(byReader.map { it.item }).containsExactlyInAnyOrder(loan, hidden, restricted, rates)
        assertThat(byReader.single { it.item == loan }.let { it.title to it.nature }).isEqualTo(TITLE to NATURE)
        assertThat(byReader.filter { it.item == hidden || it.item == restricted }.map { it.title to it.nature })
            .containsOnly(null to null)
        assertThat(byReader.toString()).doesNotContain(HIDDEN_TITLE, HIDDEN_NATURE, RESTRICTED_TITLE, RESTRICTED_NATURE)
        // The secretary of each committee: theirs only, every title and nature.
        val bySecretary = rowsOf(secretary)
        assertThat(bySecretary.map { it.item }).containsExactlyInAnyOrder(loan, hidden, restricted)
        assertThat(bySecretary.map { it.title }).containsExactlyInAnyOrder(TITLE, HIDDEN_TITLE, RESTRICTED_TITLE)
        assertThat(rowsOf(alcoSecretary).map { it.item to it.nature }).containsExactly(rates to "Holds bonds of the issuer")
        // Filters: a committee out of scope is 404; one in scope, a person and a state narrow the rows.
        assertThat(registerPage(secretary, "?forumId=$alco")).hasStatus(HttpStatus.NOT_FOUND)
        assertThat(rowsOf(reader, "?forumId=$alco").map { it.item }).containsExactly(rates)
        assertThat(rowsOf(reader, "?userId=${director.id}").map { it.item }).containsExactlyInAnyOrder(loan, restricted)
        assertThat(rowsOf(reader, "?state=AWAITING_RULING")).isEmpty()
        val today = LocalDate.now()
        assertThat(rowsOf(reader, "?from=${today.plusDays(2)}")).isEmpty()
        assertThat(rowsOf(reader, "?from=${today.minusDays(1)}&to=${today.plusDays(1)}")).hasSize(4)
        // A member without an officer's seat or forum.read is 403.
        listOf(director, alcoMember, outsider).forEach { assertThat(registerPage(it)).hasStatus(HttpStatus.FORBIDDEN) }

        // Once the secretary declares on the loan item, only their own declaration on it is listed to them.
        declare(secretary, brcMeeting, loan, "Related to the borrower")
        assertThat(rowsOf(secretary).filter { it.item == loan }.map { it.user }).containsExactly(secretary.id)
    }

    @Test
    fun `the register pages newest first with a cursor, without gaps or repeats`() {
        val meeting = meeting()
        val item = addItem(meeting, TITLE)
        publish(meeting)
        asUser(admin) {
            tenantDsl.current().execute(
                """
                insert into meeting_declaration (id, tenant_id, meeting_id, item_id, forum_id, user_id, nature, intent, source, how,
                                                 declared_at, at_meeting, state)
                select gen_random_uuid(), ?, ?, ?, ?, gen_random_uuid(), 'Interest ' || g, 'LEAVE', 'SELF', 'IN_APP',
                       now() - g * interval '1 minute', false, 'RECUSED'
                from generate_series(1, 57) g
                """,
                tenant.id.value,
                meeting.id,
                item,
                brc,
            )
        }
        val first = registerPage(reader).json()
        assertThat(first["items"].size()).isEqualTo(DeclarationRegisters.PAGE)
        val cursor = first["nextCursor"].asString()
        val second = registerPage(reader, "?cursor=$cursor").json()
        assertThat(second["items"].size()).isEqualTo(7)
        assertThat(second.absent("nextCursor")).isTrue()
        val all = (first["items"].list() + second["items"].list()).map { it["nature"].asString() }
        assertThat(all).doesNotHaveDuplicates().containsExactlyElementsOf((1..57).map { "Interest $it" })
        assertThat(registerPage(reader, "?cursor=nonsense")).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
    }

    @Test
    fun `the export holds the rows on screen, restricted ones as such, is audited with filter and count, and is rate-limited`() {
        val meeting = meeting()
        val loan = addItem(meeting, TITLE)
        val hidden = addItem(meeting, HIDDEN_TITLE)
        change(meeting, hidden, ItemChange(HIDDEN_TITLE, titleHidden = true))
        publish(meeting)
        declare(director, meeting, loan)
        declare(colleague, meeting, hidden, "=HYPERLINK(\"x\") $HIDDEN_NATURE")

        val exported = export(reader, "?forumId=$brc")
        assertThat(exported).hasStatusOk()
        assertThat(exported.response.contentType).isEqualTo("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
        assertThat(exported.response.getHeader("Content-Disposition")).contains("attachment").contains("declarations.xlsx")
        val sheet = cells(exported)
        assertThat(sheet.first()).startsWith("Committee", "Meeting", "Meeting date", "Item", "Title", "Member", "Interest")
        assertThat(sheet.drop(1).map { it[4] to it[6] })
            .containsExactlyInAnyOrder(TITLE to NATURE, "Restricted item" to "(restricted item)")
        assertThat(sheet.flatten().joinToString()).doesNotContain(HIDDEN_TITLE, HIDDEN_NATURE)
        val audited = Audit.assertAudited(tenant.id, "meeting.declarations.exported", brc.toString(), reader.id.toString())
        assertThat(audited.canonical).contains("\"count\":2")

        // The secretary's export reads the hidden row, with the formula-injection prefix on the nature.
        val bySecretary = cells(export(secretary))
        assertThat(bySecretary.drop(1).map { it[6] }).contains("'=HYPERLINK(\"x\") $HIDDEN_NATURE")
        // Five an hour for one person; a member is 403 before any count.
        assertThat(export(director)).hasStatus(HttpStatus.FORBIDDEN)
        repeat(4) { assertThat(export(reader)).hasStatusOk() }
        assertThat(export(reader)).hasStatus(HttpStatus.TOO_MANY_REQUESTS)
    }

    private data class Row(
        val item: UUID,
        val user: UUID,
        val title: String?,
        val nature: String?,
    )

    private fun rowsOf(
        person: Person,
        query: String = "",
    ): List<Row> {
        val page = registerPage(person, query)
        assertThat(page).hasStatusOk()
        return page.json()["items"].list().map {
            Row(
                UUID.fromString(it["itemId"].asString()),
                UUID.fromString(it["user"]["id"].asString()),
                it.get("itemTitle")?.takeUnless { t -> t.isNull }?.asString(),
                it.get("nature")?.takeUnless { n -> n.isNull }?.asString(),
            )
        }
    }

    private fun registerPage(
        person: Person,
        query: String = "",
    ): MvcTestResult = read("/api/v1/declarations$query", person.session)

    private fun export(
        person: Person,
        query: String = "",
    ): MvcTestResult = read("/api/v1/declarations/export$query", person.session)

    /** The export's cells as text, row by row. */
    private fun cells(result: MvcTestResult): List<List<String>> =
        XSSFWorkbook(ByteArrayInputStream(result.response.contentAsByteArray)).use { workbook ->
            val sheet = workbook.getSheetAt(0)
            (0..sheet.lastRowNum).map { r ->
                val row = sheet.getRow(r)
                (0 until row.lastCellNum).map { c -> row.getCell(c)?.stringCellValue.orEmpty() }
            }
        }

    private fun readRegister(
        person: Person,
        meeting: MeetingRow,
    ): MvcTestResult = read("/api/v1/meetings/${meeting.id}/declarations", person.session)

    private fun registerOf(
        person: Person,
        meeting: MeetingRow,
    ): JsonNode {
        val result = readRegister(person, meeting)
        assertThat(result).hasStatusOk()
        return result.json()
    }

    /** The only item of [meeting]'s register. */
    private fun registerItem(meeting: MeetingRow): UUID =
        UUID.fromString(registerOf(secretary, meeting)["items"].list().single()["itemId"].asString())

    private fun remind(
        person: Person,
        meeting: MeetingRow,
    ): MvcTestResult = mvc.sendJson(HttpMethod.POST, tenant, "/api/v1/meetings/${meeting.id}/declarations/remind", person.session, "{}")

    private fun MvcTestResult.reminded(): List<UUID> {
        assertThat(this).hasStatusOk()
        return json()["reminded"].list().map { UUID.fromString(it["id"].asString()) }
    }

    private fun MvcTestResult.problemReason(): String? = json()["reason"]?.asString()

    private fun JsonNode.item(id: UUID): JsonNode = get("items").list().single { it["itemId"].asString() == id.toString() }

    private fun JsonNode.list(): List<JsonNode> = (0 until size()).map { get(it) }

    /** The committee type of the fixture's committee. */
    private fun forumType(): UUID = asUser(admin) { forums.get(brc) }.typeId

    /** A scheduled meeting of [forum] forty days ahead, its agenda started, created by [secretaryPerson]. */
    private fun meetingOf(
        forum: UUID,
        secretaryPerson: Person = secretary,
        chairPerson: Person = chair,
    ): MeetingRow {
        val day = LocalDate.now().plusDays(DAYS_AHEAD)
        check(chairPerson.id != secretaryPerson.id)
        return asUser(secretaryPerson.account) {
            val created =
                lifecycle.create(
                    forum,
                    MeetingInput(MeetingKind.ORDINARY, MeetingPlace(null, MeetingMode.IN_PERSON, "Board room", null, null), null),
                    TimesInput(day.atTime(LocalTime.of(10, 0)), day.atTime(LocalTime.of(13, 0)), "Asia/Dhaka"),
                )
            starts.start(created.id, AgendaOrigin.TEMPLATE, null)
            lifecycle.schedule(created.id, reads.get(created.id).version)
        }
    }

    private fun addItem(
        meeting: MeetingRow,
        title: String,
        by: Person,
    ): UUID =
        asUser(by.account) {
            items.add(meeting.id, views.draft(meeting.id).version, ItemInput(ItemKind.ITEM, title = title, minutes = 20))
        }

    private fun publish(
        meeting: MeetingRow,
        note: String? = null,
        secretaryPerson: Person,
        chairPerson: Person,
    ) {
        asUser(chairPerson.account) { chairing.agree(meeting.id, views.draft(meeting.id).version, false, null) }
        asUser(secretaryPerson.account) { publisher.publish(meeting.id, views.draft(meeting.id).version, note, true) }
    }

    private companion object {
        const val DUE = "MEETING_DECLARATIONS_DUE"
        const val HIDDEN_TITLE = "CANARY dismissal of the treasurer"
        const val HIDDEN_NATURE = "CANARY related to the treasurer"
        const val RESTRICTED_TITLE = "CANARY facility for Acme"
        const val RESTRICTED_NATURE = "CANARY shares in Acme"
    }
}
