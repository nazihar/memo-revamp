package com.memo.meeting

import com.memo.common.authz.ForbiddenException
import com.memo.common.concurrency.ConcurrentModificationException
import com.memo.meeting.internal.DeclarationRow
import com.memo.meeting.internal.DeclarationRules
import com.memo.meeting.internal.DeclareInput
import com.memo.meeting.internal.ItemChange
import com.memo.meeting.internal.ItemInput
import com.memo.meeting.internal.ItemKind
import com.memo.meeting.internal.MeetingNotFoundException
import com.memo.meeting.internal.MeetingRow
import com.memo.meeting.internal.OneOpenException
import com.memo.meeting.internal.RecusedException
import com.memo.meeting.internal.RulingInput
import com.memo.testsupport.AppRoleJdbc
import com.memo.testsupport.AppRoleJdbc.long
import com.memo.testsupport.Audit
import com.memo.testsupport.Person
import com.memo.testsupport.sendJson
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import java.util.UUID

/**
 * Declarations of interest (test plan E04, T1; ADR-0062, design E04 §1.1–§1.3, §3, §5): declaring for oneself (leave the
 * room, or ask the chair) and on someone's behalf (each `how`); intent changes; rulings by the chair, by the vice-chair
 * when the chair declared or is recused, and recorded by a secretary on the chair's behalf; withdrawals with a reason;
 * the windows by the meeting's status and the secretary's corrections once it is held; the audit with hashes and never
 * the text; `/declarations/mine` and the draft's new fields over HTTP.
 */
class DeclarationsIT : DeclarationFixture() {
    @Test
    fun `a member declares on a published item and is recused, once only, and a draft item is 404 to them but not to the secretary`() {
        val meeting = meeting()
        val item = addItem(meeting, TITLE)
        publish(meeting)
        val draftOnly = addItem(meeting, "Liquidity contingency plan")

        val declared = declare(director, meeting, item)
        assertThat(declared.state).isEqualTo(DeclarationState.RECUSED)
        assertThat(declared.source to declared.how).isEqualTo(DeclarationSource.SELF to DeclarationHow.IN_APP)
        assertThat(declared.recordedBy).isEqualTo(director.id)
        assertThat(declared.revision).isEqualTo(1)
        assertThat(declared.atMeeting).isFalse()
        assertThat(declared.nature).isEqualTo(NATURE)

        assertThatThrownBy { declare(director, meeting, item, "Another interest") }
            .isInstanceOfSatisfying(OneOpenException::class.java) { assertThat(it.declarationId).isEqualTo(declared.id) }
        assertThatThrownBy { declare(director, meeting, draftOnly) }.isInstanceOf(MeetingNotFoundException::class.java)
        // Nobody declares for someone without being an officer; the reader and outsiders do not see the meeting's items.
        assertThatThrownBy { record(colleague, meeting, item, director) }.isInstanceOf(ForbiddenException::class.java)
        assertThatThrownBy { declare(reader, meeting, item) }.isInstanceOf(MeetingNotFoundException::class.java)
        assertThatThrownBy { declare(outsider, meeting, item) }.isInstanceOf(MeetingNotFoundException::class.java)

        // The secretary records one on the director's behalf on the draft-only item, saying how it was given.
        assertThat(problem { record(secretary, meeting, draftOnly, director, how = null) }).isEqualTo("how:HOW_REQUIRED")
        assertThat(problem { record(secretary, meeting, draftOnly, director, how = DeclarationHow.IN_APP) }).isEqualTo("how:HOW_REQUIRED")
        assertThat(problem { record(secretary, meeting, draftOnly, outsider) }).isEqualTo("onBehalfOf:NOT_IN_AUDIENCE")
        val recorded = record(secretary, meeting, draftOnly, director)
        assertThat(recorded.source to recorded.how).isEqualTo(DeclarationSource.ON_BEHALF to DeclarationHow.EMAIL)
        assertThat(recorded.recordedBy to recorded.userId).isEqualTo(secretary.id to director.id)
        assertThat(recorded.howNote).isEqualTo("email of 4 Dec")
        // Each way it can be given; the guest too, being in the audience.
        listOf(DeclarationHow.AT_MEETING, DeclarationHow.IN_PERSON, DeclarationHow.PHONE).forEach { how ->
            val other = record(secretary, meeting, addItem(meeting, "Item by $how"), colleague, how)
            assertThat(other.how).isEqualTo(how)
        }
        assertThat(record(chair, meeting, item, guest).source).isEqualTo(DeclarationSource.ON_BEHALF)

        // Audited with ids and the hash of the nature, never the nature itself.
        val audited = Audit.assertAudited(tenant.id, "meeting.declaration.made", meeting.id.toString(), chair.id.toString())
        assertThat(audited.canonical).contains(declarationHash()).contains("ON_BEHALF").doesNotContain("brother-in-law")
        assertThat(
            Audit.entries(tenant.id).map { it.canonical },
        ).noneMatch { it.contains("brother-in-law") || it.contains("email of 4 Dec") }
    }

    @Test
    fun `natures, reasons and notes are checked, and a break takes no declaration`() {
        val meeting = meeting()
        val item = addItem(meeting, TITLE)
        publish(meeting)

        assertThat(problem { declare(director, meeting, item, "   ") }).isEqualTo("nature:NATURE_REQUIRED")
        assertThat(problem { declare(director, meeting, item, "x".repeat(1001)) }).isEqualTo("nature:LENGTH")
        assertThat(declare(director, meeting, item, "  ${"x".repeat(1000)}  ").nature).hasSize(1000)
        assertThat(
            problem {
                asUser(secretary.account) {
                    declarations.declare(
                        meeting.id,
                        item,
                        DeclareInput(NATURE, onBehalfOf = colleague.id, how = DeclarationHow.EMAIL, howNote = "y".repeat(201)),
                    )
                }
            },
        ).isEqualTo("howNote:LENGTH")
        val breakItem =
            asUser(secretary.account) {
                items.add(
                    meeting.id,
                    version(meeting),
                    ItemInput(ItemKind.BREAK, minutes = 10),
                )
            }
        assertThatThrownBy { record(secretary, meeting, breakItem, colleague) }.isInstanceOf(MeetingNotFoundException::class.java)
    }

    @Test
    fun `asking the chair waits for the chair, or the vice-chair when the chair declared or is recused, or a ruling a secretary records`() {
        val meeting = meeting()
        val item = addItem(meeting, TITLE)
        val second = addItem(meeting, "Capital plan")
        publish(meeting)

        val asked = declare(director, meeting, item, intent = DeclarationIntent.ASK_CHAIR)
        assertThat(asked.state).isEqualTo(DeclarationState.AWAITING_RULING)
        // The vice-chair does not rule while the chair can; a member sees nobody else's declaration.
        assertThatThrownBy { rule(vice, meeting, asked, RulingOutcome.TAKE_PART) }.isInstanceOf(ForbiddenException::class.java)
        assertThatThrownBy { rule(colleague, meeting, asked, RulingOutcome.TAKE_PART) }.isInstanceOf(MeetingNotFoundException::class.java)
        val ruled = rule(chair, meeting, asked, RulingOutcome.TAKE_PART)
        assertThat(ruled.state).isEqualTo(DeclarationState.TAKING_PART)
        assertThat(ruled.ruling?.by to ruled.ruling?.onBehalfBy).isEqualTo(chair.id to null)
        assertThat(ruled.ruling?.how).isEqualTo(DeclarationHow.IN_APP)
        refused("WRONG_STATE") { rule(chair, meeting, ruled, RulingOutcome.TAKE_PART) }
        assertThatThrownBy { rule(chair, meeting, asked, RulingOutcome.LEAVE) }.isInstanceOf(ConcurrentModificationException::class.java)
        assertThat(rule(chair, meeting, ruled, RulingOutcome.LEAVE).state).isEqualTo(DeclarationState.RECUSED)
        Audit.assertAudited(tenant.id, "meeting.declaration.ruled", meeting.id.toString(), chair.id.toString())

        // The chair declares: their own ruling is SELF_RULING, and the vice-chair rules.
        val chairs = declare(chair, meeting, second, "I sit on the borrower's board", DeclarationIntent.ASK_CHAIR)
        refused("SELF_RULING") { rule(chair, meeting, chairs, RulingOutcome.TAKE_PART) }
        assertThat(rule(vice, meeting, chairs, RulingOutcome.LEAVE).ruling?.by).isEqualTo(vice.id)

        // The chair is recused from the item (their declaration above): on a colleague's declaration there, the vice-chair rules.
        val colleagues = declare(colleague, meeting, second, "My spouse works for the borrower", DeclarationIntent.ASK_CHAIR)
        assertThatThrownBy { rule(chair, meeting, colleagues, RulingOutcome.TAKE_PART) }.isInstanceOf(RecusedException::class.java)
        assertThat(rule(vice, meeting, colleagues, RulingOutcome.TAKE_PART).state).isEqualTo(DeclarationState.TAKING_PART)

        // A secretary records the chair's ruling given by phone; how is required; members cannot.
        val guests = declare(guest, meeting, item, "I advise the borrower", DeclarationIntent.ASK_CHAIR)
        assertThat(problem { rule(secretary, meeting, guests, RulingOutcome.TAKE_PART, onBehalf = true) }).isEqualTo("how:HOW_REQUIRED")
        val byPhone = rule(secretary, meeting, guests, RulingOutcome.TAKE_PART, onBehalf = true, how = DeclarationHow.PHONE)
        assertThat(byPhone.ruling?.by to byPhone.ruling?.onBehalfBy).isEqualTo(chair.id to secretary.id)
        assertThat(byPhone.ruling?.how).isEqualTo(DeclarationHow.PHONE)
        assertThat(
            problem {
                asUser(chair.account) {
                    rulings.rule(meeting.id, byPhone.id, byPhone.version, RulingInput(RulingOutcome.LEAVE, "n".repeat(501)))
                }
            },
        ).isEqualTo("note:LENGTH")
        AppRoleJdbc.inTransaction(tenant.id) { c ->
            assertThat(
                c.long("select count(*) from meeting_declaration_event where declaration_id = ? and kind = 'RULED'", byPhone.id),
            ).isEqualTo(1)
            assertThat(
                c.long(
                    "select count(*) from meeting_declaration_event where declaration_id = ? and on_behalf_by = ?",
                    byPhone.id,
                    secretary.id,
                ),
            ).isEqualTo(1)
        }
    }

    @Test
    fun `intent changes and withdrawals follow the states, and a withdrawn declaration changes no more`() {
        val meeting = meeting()
        val item = addItem(meeting, TITLE)
        publish(meeting)
        val declared = declare(director, meeting, item)

        refused("WRONG_STATE") { intent(director, meeting, declared, DeclarationIntent.LEAVE) }
        val asking = intent(director, meeting, declared, DeclarationIntent.ASK_CHAIR)
        assertThat(asking.state to asking.intent).isEqualTo(DeclarationState.AWAITING_RULING to DeclarationIntent.ASK_CHAIR)
        refused("WRONG_STATE") { intent(director, meeting, asking, DeclarationIntent.ASK_CHAIR) }
        val leaving = intent(director, meeting, asking, DeclarationIntent.LEAVE)
        assertThat(leaving.state).isEqualTo(DeclarationState.RECUSED)
        Audit.assertAudited(tenant.id, "meeting.declaration.intent_changed", meeting.id.toString(), director.id.toString())
        // Only the declarant changes their intent: a member does not see it, an officer may not.
        assertThatThrownBy {
            intent(
                colleague,
                meeting,
                leaving,
                DeclarationIntent.ASK_CHAIR,
            )
        }.isInstanceOf(MeetingNotFoundException::class.java)
        assertThatThrownBy { intent(secretary, meeting, leaving, DeclarationIntent.ASK_CHAIR) }.isInstanceOf(ForbiddenException::class.java)
        assertThatThrownBy { intent(director, meeting, declared, DeclarationIntent.ASK_CHAIR) }
            .isInstanceOf(ConcurrentModificationException::class.java)

        assertThat(problem { withdraw(director, meeting, leaving, reason = " ") }).isEqualTo("reason:REASON_REQUIRED")
        assertThat(problem { withdraw(director, meeting, leaving, reason = "r".repeat(501)) }).isEqualTo("reason:LENGTH")
        assertThatThrownBy { withdraw(colleague, meeting, leaving) }.isInstanceOf(MeetingNotFoundException::class.java)
        val withdrawn = withdraw(director, meeting, leaving, "The loan is to another company of that name")
        assertThat(withdrawn.state).isEqualTo(DeclarationState.WITHDRAWN)
        val audited = Audit.assertAudited(tenant.id, "meeting.declaration.withdrawn", meeting.id.toString(), director.id.toString())
        assertThat(audited.canonical).contains("reasonSha256").doesNotContain("another company")
        refused("WRONG_STATE") { withdraw(director, meeting, withdrawn) }
        refused("WRONG_STATE") { rule(chair, meeting, withdrawn, RulingOutcome.LEAVE) }
        refused("WRONG_STATE") { intent(director, meeting, withdrawn, DeclarationIntent.ASK_CHAIR) }

        // A withdrawal is not an edit: the person declares again, and an officer withdraws someone else's.
        val again = declare(director, meeting, item, "My brother-in-law is a director of Delta Textiles Ltd")
        assertThat(again.id).isNotEqualTo(declared.id)
        assertThat(withdraw(secretary, meeting, again).state).isEqualTo(DeclarationState.WITHDRAWN)
        // An officer recused from the item withdraws no one else's.
        val colleagues = declare(colleague, meeting, item, "My spouse works for the borrower")
        declare(secretary, meeting, item, "I hold shares in the borrower")
        assertThatThrownBy { withdraw(secretary, meeting, colleagues) }.isInstanceOf(RecusedException::class.java)
        assertThatThrownBy { record(secretary, meeting, item, guest) }.isInstanceOf(RecusedException::class.java)
        AppRoleJdbc.inTransaction(tenant.id) { c ->
            assertThat(c.long("select count(*) from meeting_declaration_event where declaration_id = ?", declared.id)).isEqualTo(4)
        }
    }

    @Test
    fun `once the meeting is held only a secretary corrects, and from minutes approved, or cancelled, nobody can`() {
        val meeting = meeting()
        val item = addItem(meeting, TITLE)
        val second = addItem(meeting, "Capital plan")
        publish(meeting)
        val before = declare(director, meeting, item)
        startMeeting(meeting)
        val inRoom = declare(colleague, meeting, item, "My spouse works for the borrower")
        assertThat(inRoom.atMeeting).isTrue()
        endMeeting(meeting)

        refused("FROZEN") { declare(director, meeting, second) }
        refused("FROZEN") { withdraw(director, meeting, before) }
        refused("FROZEN") { intent(director, meeting, before, DeclarationIntent.ASK_CHAIR) }
        refused("FROZEN") { rule(chair, meeting, inRoom, RulingOutcome.TAKE_PART) }
        refused("FROZEN") { record(chair, meeting, second, director, DeclarationHow.AT_MEETING, "Made in the room") }

        // The secretary records a declaration made in the room and not entered, with a reason, as a correction.
        assertThat(problem { record(secretary, meeting, second, director, DeclarationHow.AT_MEETING) }).isEqualTo("reason:REASON_REQUIRED")
        val corrected = record(secretary, meeting, second, director, DeclarationHow.AT_MEETING, "Declared at the meeting, not entered")
        assertThat(corrected.correction to corrected.atMeeting).isEqualTo(true to true)
        val made = Audit.assertAudited(tenant.id, "meeting.declaration.made", meeting.id.toString(), secretary.id.toString())
        assertThat(made.canonical).contains("\"correction\":true").contains("reasonSha256")
        val withdrawn = withdraw(secretary, meeting, before, "Entered against the wrong item")
        assertThat(withdrawn.state).isEqualTo(DeclarationState.WITHDRAWN)
        AppRoleJdbc.inTransaction(tenant.id) { c ->
            assertThat(
                c.long("select count(*) from meeting_declaration_event where declaration_id = ? and correction", before.id),
            ).isEqualTo(1)
        }

        status(meeting, MeetingStatus.MINUTES_APPROVED)
        refused("FROZEN") { record(secretary, meeting, item, guest, DeclarationHow.AT_MEETING, "Late") }
        refused("FROZEN") { withdraw(secretary, meeting, inRoom) }

        val cancelled = meeting(DAYS_AHEAD + 1)
        val onCancelled = addItem(cancelled, "Strategy")
        publish(cancelled)
        asUser(secretary.account) { lifecycle.cancel(cancelled.id, reads.get(cancelled.id).version, "Postponed") }
        refused("FROZEN") { declare(director, cancelled, onCancelled) }
    }

    @Test
    fun `over HTTP declaring needs an Idempotency-Key and changes need If-Match`() {
        val meeting = meeting()
        val item = addItem(meeting, TITLE)
        val hidden = hiddenItem(meeting)
        publish(meeting)
        val base = "/api/v1/meetings/${meeting.id}"
        val body = """{"nature":"$NATURE","intent":"ASK_CHAIR"}"""
        val key = UUID.randomUUID().toString()

        val first = declareOverHttp(meeting, item, director, body, key)
        assertThat(first).hasStatus(HttpStatus.CREATED)
        val id = first.json()["id"].asString()
        assertThat(first.json()["state"].asString()).isEqualTo("AWAITING_RULING")
        assertThat(first.json()["itemTitle"].asString()).isEqualTo(TITLE)
        assertThat(first.response.getHeader("ETag")).isEqualTo("\"0\"")
        assertThat(declareOverHttp(meeting, item, director, body, key).json()["id"].asString()).isEqualTo(id)
        AppRoleJdbc.inTransaction(tenant.id) { c ->
            assertThat(c.long("select count(*) from meeting_declaration where user_id = ?", director.id)).isEqualTo(1)
        }
        val again = declareOverHttp(meeting, item, director, body)
        assertThat(again).hasStatus(HttpStatus.CONFLICT)
        assertThat(again.json()["reason"].asString()).isEqualTo("ONE_OPEN")
        assertThat(again.json()["declarationId"].asString()).isEqualTo(id)
        // A hidden-title item the director is not among the people for: 404.
        assertThat(declareOverHttp(meeting, hidden, director, body)).hasStatus(HttpStatus.NOT_FOUND)

        val intent = """{"intent":"LEAVE"}"""
        val uri = "$base/declarations/$id/intent"
        assertThat(send(HttpMethod.POST, uri, director, intent, emptyMap())).hasStatus(HttpStatus.PRECONDITION_REQUIRED)
        assertThat(send(HttpMethod.POST, uri, director, intent, mapOf("If-Match" to "\"7\""))).hasStatus(HttpStatus.PRECONDITION_FAILED)
        val changed = send(HttpMethod.POST, uri, director, intent, mapOf("If-Match" to "\"0\""))
        assertThat(changed).hasStatusOk()
        assertThat(changed.json()["state"].asString()).isEqualTo("RECUSED")
        assertThat(changed.response.getHeader("ETag")).isEqualTo("\"1\"")
    }

    @Test
    fun `over HTTP mine lists the actor's declarations, and the draft shows the officer's own and the others' lines`() {
        val meeting = meeting()
        val item = addItem(meeting, TITLE)
        val hidden = hiddenItem(meeting)
        publish(meeting)
        val base = "/api/v1/meetings/${meeting.id}"
        val id = declare(director, meeting, item).id.toString()

        val mine = get("$base/declarations/mine", director)
        assertThat(mine).hasStatusOk()
        assertThat(mine.json()["latestRevision"].asInt()).isEqualTo(1)
        assertThat(mine.json()["declarations"].toList().map { it["id"].asString() }).containsExactly(id)
        assertThat(mine.json()["declarations"][0]["nature"].asString()).isEqualTo(NATURE)
        assertThat(get("$base/declarations/mine", reader)).hasStatus(HttpStatus.FORBIDDEN)
        assertThat(get("$base/declarations/mine", outsider)).hasStatus(HttpStatus.NOT_FOUND)

        // The members' agenda: the director's own declaration on the item; the colleague sees it as a line without the nature.
        val ownLine = memberItem(get("$base/agenda", director).json(), item)
        assertThat(ownLine["myDeclaration"]["nature"].asString()).isEqualTo(NATURE)
        assertThat(ownLine["declarations"].size()).isZero()
        val colleagueLine = memberItem(get("$base/agenda", colleague).json(), item)
        assertThat(colleagueLine.absent("myDeclaration")).isTrue()
        assertThat(colleagueLine["declarations"].toList().map { it["user"]["id"].asString() }).containsExactly(director.id.toString())
        assertThat(colleagueLine["declarations"][0].has("nature")).isFalse()

        // The draft: the secretary's own declaration as theirs, the director's as a line with no nature.
        record(secretary, meeting, hidden, colleague)
        val own = declareOverHttp(meeting, item, secretary, """{"nature":"I hold shares in the borrower"}""")
        assertThat(own).hasStatus(HttpStatus.CREATED)
        val draft = get("$base/agenda/draft", secretary).json()
        val row = draft["sections"].flatMap { it["items"] }.single { it["id"].asString() == item.toString() }
        assertThat(row["myDeclaration"]["nature"].asString()).isEqualTo("I hold shares in the borrower")
        // Recused now (T2), the secretary sees the item's line and their own declaration only; the chair sees both lines.
        assertThat(row["recused"].asBoolean()).isTrue()
        assertThat(row["declarations"].size()).isZero()
        val chairDraft = get("$base/agenda/draft", chair).json()
        val chairRow = chairDraft["sections"].flatMap { it["items"] }.single { it["id"].asString() == item.toString() }
        val lines = chairRow["declarations"].toList().map { it["user"]["id"].asString() to it["state"].asString() }
        assertThat(lines).containsExactly(director.id.toString() to "RECUSED", secretary.id.toString() to "RECUSED")
        assertThat(chairRow["declarations"][0].has("nature")).isFalse()

        // Recused from the item, the secretary records nobody else's declaration on it: 403 RECUSED.
        val blocked = declareOverHttp(meeting, item, secretary, """{"nature":"x","onBehalfOf":"${guest.id}","how":"EMAIL"}""")
        assertThat(blocked).hasStatus(HttpStatus.FORBIDDEN)
        assertThat(blocked.json()["reason"].asString()).isEqualTo("RECUSED")
    }

    private fun memberItem(
        agenda: tools.jackson.databind.JsonNode,
        item: UUID,
    ): tools.jackson.databind.JsonNode = agenda["sections"].flatMap { it["items"] }.single { it["id"].asString() == item.toString() }

    /** An item only the colleague may see, with its title hidden. */
    private fun hiddenItem(meeting: MeetingRow): UUID {
        val hidden = addItem(meeting, "Acquisition of Beta Bank")
        val restricted = ItemChange("Acquisition of Beta Bank", minutes = 20, restrictedTo = listOf(colleague.id), titleHidden = true)
        change(meeting, hidden, restricted)
        return hidden
    }

    private fun declareOverHttp(
        meeting: MeetingRow,
        item: UUID,
        person: Person,
        body: String,
        key: String = UUID.randomUUID().toString(),
    ) = send(HttpMethod.POST, "/api/v1/meetings/${meeting.id}/items/$item/declarations", person, body, mapOf("Idempotency-Key" to key))

    private fun intent(
        person: Person,
        meeting: MeetingRow,
        declaration: DeclarationRow,
        intent: DeclarationIntent,
    ) = asUser(person.account) { declarations.changeIntent(meeting.id, declaration.id, declaration.version, intent) }

    private fun send(
        method: HttpMethod,
        uri: String,
        person: Person,
        body: String,
        headers: Map<String, String>,
    ) = mvc.sendJson(method, tenant, uri, person.session, body, headers)

    private fun get(
        uri: String,
        person: Person,
    ) = read(uri, person.session)

    private fun declarationHash(): String = DeclarationRules.sha256(NATURE)
}
