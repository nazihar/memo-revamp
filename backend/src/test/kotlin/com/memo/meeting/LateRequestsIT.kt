package com.memo.meeting

import com.memo.meeting.internal.ItemChange
import com.memo.meeting.internal.Lateness
import com.memo.meeting.internal.MeetingRow
import com.memo.meeting.internal.PaperInput
import com.memo.testsupport.Audit
import com.memo.testsupport.Person
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.test.web.servlet.assertj.MvcTestResult
import tools.jackson.databind.JsonNode
import java.util.UUID

/**
 * The decider's late requests (design E04 §1.7 as built): while a chair is in office the vice-chair is a member of the
 * meeting and cannot read the draft, yet decides the late papers of the items the chair is recused from. They list those
 * requests (`GET …/agenda/late-requests`, each as the paper rule lets them read it on the draft) and answer them
 * (`POST …/late-requests/decisions`, answered with the list); the draft's `decides` says the same for the chair. A recused
 * chair is 403 `RECUSED`, a vice-chair where the chair decides 403, and nobody else is listed anything.
 */
class LateRequestsIT : RecusalFixture() {
    @Test
    fun `the vice-chair lists and decides the late papers of an item the chair is recused from, and only those`() {
        // Tomorrow's meeting is past the paper deadline: a new paper waits for the chair.
        val meeting = meeting(days = 1)
        val item = addItem(meeting, TITLE)
        val second = addItem(meeting, "Liquidity report")
        publish(meeting)
        declare(chair, meeting, item)
        val late = latePaper(meeting, item, "Valuation report")
        val elsewhere = latePaper(meeting, second, "Liquidity stress results")

        // The vice-chair still cannot read the draft; their late requests are the recused chair's item only.
        assertThat(draftOf(vice, meeting)).hasStatus(HttpStatus.FORBIDDEN)
        val forVice = requests(vice, meeting)
        assertThat(forVice.list()).hasSize(1)
        val request = forVice.list().single()
        assertThat(request["itemId"].asString()).isEqualTo(item.toString())
        assertThat(request["paperId"].asString()).isEqualTo(late.toString())
        assertThat(request["itemTitle"].asString()).isEqualTo(TITLE)
        assertThat(request["paperTitle"].asString()).isEqualTo("Valuation report")
        assertThat(request["number"].asString()).isNotBlank()

        // The chair decides the other item, in the list and in the draft; the recused item is a line without lateness.
        assertThat(requests(chair, meeting).list().map { it["paperId"].asString() }).containsExactly(elsewhere.toString())
        val chairDraft = draftOf(chair, meeting).json()
        assertThat(chairDraft.draftItem(second)["decides"].asBoolean()).isTrue()
        assertThat(chairDraft.draftItem(item)["decides"].asBoolean()).isFalse()
        assertThat(chairDraft.draftItem(item)["recused"].asBoolean()).isTrue()
        // Secretaries and members decide nothing; someone who does not see the meeting is 404.
        val secretaryDraft = draftOf(secretary, meeting).json()
        assertThat(listOf(item, second).map { secretaryDraft.draftItem(it)["decides"].asBoolean() }).containsOnly(false)
        listOf(secretary, d1, guest, auditor).forEach { assertThat(requests(it, meeting).list()).isEmpty() }
        assertThat(read(uri(meeting), outsider.session)).hasStatus(HttpStatus.NOT_FOUND)

        // The vice-chair cannot decide where the chair can; the recused chair cannot decide the item (403 RECUSED).
        assertThat(decide(vice, meeting, second, elsewhere)).hasStatus(HttpStatus.FORBIDDEN)
        val recused = decide(chair, meeting, item, late)
        assertThat(recused).hasStatus(HttpStatus.FORBIDDEN)
        assertThat(recused.json()["reason"].asString()).isEqualTo("RECUSED")
        // A paper named with another item is 404; the item itself is not waiting (409); a stale version is 412.
        assertThat(decide(vice, meeting, second, late)).hasStatus(HttpStatus.NOT_FOUND)
        assertThat(decide(vice, meeting, item, null).json()["reason"].asString()).isEqualTo("WRONG_STATE")
        assertThat(decide(vice, meeting, item, late, version(meeting) - 1)).hasStatus(HttpStatus.PRECONDITION_FAILED)

        // The vice-chair agrees: audited as theirs, answered with what is left for them, and the paper rides in.
        val agreed = decide(vice, meeting, item, late)
        assertThat(agreed).hasStatusOk()
        assertThat(agreed.json()["requests"].list()).isEmpty()
        assertThat(agreed.response.getHeader("ETag")).isEqualTo("\"${version(meeting)}\"")
        Audit.assertAudited(tenant.id, "meeting.late.agreed", meeting.id.toString(), vice.id.toString())
        val paper =
            asUser(secretary.account) { views.draft(meeting.id) }
                .sections
                .flatMap { it.items }
                .single { it.id == item }
                .papers
        assertThat(paper.single { it.id == late }.late).isEqualTo(Lateness.CHAIR_AGREED)
        // The chair declines theirs: the paper leaves its item.
        assertThat(decide(chair, meeting, second, elsewhere, agree = false)).hasStatusOk()
        Audit.assertAudited(tenant.id, "meeting.late.declined", meeting.id.toString(), chair.id.toString())
        assertThat(requests(chair, meeting).list()).isEmpty()
    }

    @Test
    fun `the vice-chair reads each request by the paper rule, with no title they may not read and no paper they may not open`() {
        val meeting = meeting(days = 1)
        val restricted = addItem(meeting, "Credit facility for Acme")
        val hidden = addItem(meeting, CANARY_HIDDEN)
        change(meeting, restricted, ItemChange("Credit facility for Acme", CANARY_DESCRIPTION, restrictedTo = listOf(d1.id)))
        change(meeting, hidden, ItemChange(CANARY_HIDDEN, CANARY_DESCRIPTION, restrictedTo = listOf(d1.id), titleHidden = true))
        publish(meeting)
        declare(chair, meeting, restricted)
        declare(chair, meeting, hidden)
        val onRestricted = latePaper(meeting, restricted, CANARY_PAPER)
        val onHidden = latePaper(meeting, hidden, CANARY_PAPER)

        val result = read(uri(meeting), vice.session)
        assertThat(result).hasStatusOk()
        listOf(CANARY_HIDDEN, CANARY_DESCRIPTION, CANARY_PAPER).forEach { assertThat(result.response.contentAsString).doesNotContain(it) }
        val byPaper = result.json()["requests"].list().associateBy { UUID.fromString(it["paperId"].asString()) }
        assertThat(byPaper.keys).containsExactlyInAnyOrder(onRestricted, onHidden)
        // Not named on the restricted item: its line has the title, not the item; the hidden one reads "Restricted item".
        assertThat(byPaper.getValue(onRestricted)["itemTitle"].asString()).isEqualTo("Credit facility for Acme")
        assertThat(byPaper.getValue(onRestricted).absent("purpose")).isTrue()
        assertThat(byPaper.getValue(onHidden).absent("itemTitle")).isTrue()
        assertThat(byPaper.values.map { it.absent("paperTitle") }).containsOnly(true)
        // They still decide them: the rule decides what they read, the recusal who decides.
        assertThat(decide(vice, meeting, hidden, onHidden, agree = false)).hasStatusOk()
    }

    @Test
    fun `once the vice-chair is recused too, nobody is listed the request and the secretary records the agreement`() {
        val meeting = meeting(days = 1)
        val item = addItem(meeting, TITLE)
        publish(meeting)
        declare(chair, meeting, item)
        declare(vice, meeting, item)
        val late = latePaper(meeting, item, "Valuation report")

        listOf(chair, vice, secretary).forEach { assertThat(requests(it, meeting).list()).isEmpty() }
        assertThat(decide(vice, meeting, item, late)).hasStatus(HttpStatus.FORBIDDEN)
        val onBehalf =
            change(
                secretary2,
                HttpMethod.POST,
                "/api/v1/meetings/${meeting.id}/agenda/papers/$late/late-decision",
                meeting,
                """{"agree":true,"onBehalf":true,"note":"Agreed by the committee by email on 4 Dec"}""",
            )
        assertThat(onBehalf).hasStatusOk()
    }

    /** A late paper on [item] (the deadline has passed): waiting for the chair. */
    private fun latePaper(
        meeting: MeetingRow,
        item: UUID,
        title: String,
    ): UUID {
        val file = upload(meeting, "${UUID.randomUUID()}.pdf")
        val id = asUser(secretary.account) { papers.add(meeting.id, item, version(meeting), PaperInput(file, title)) }
        val draft = asUser(secretary.account) { views.draft(meeting.id) }
        assertThat(
            draft.sections
                .flatMap { it.items }
                .flatMap { it.papers }
                .single { it.id == id }
                .late,
        ).isEqualTo(Lateness.AWAITING_CHAIR)
        return id
    }

    private fun uri(meeting: MeetingRow) = "/api/v1/meetings/${meeting.id}/agenda/late-requests"

    /** [person]'s late requests on [meeting]. */
    private fun requests(
        person: Person,
        meeting: MeetingRow,
    ): JsonNode {
        val result = read(uri(meeting), person.session)
        assertThat(result).hasStatusOk()
        return result.json()["requests"]
    }

    /** [person] decides the late [paper] of [item] (or the item itself), at [version] (the agenda's current by default). */
    private fun decide(
        person: Person,
        meeting: MeetingRow,
        item: UUID,
        paper: UUID?,
        version: Long = version(meeting),
        agree: Boolean = true,
    ): MvcTestResult {
        val body = """{"itemId":"$item",${paper?.let { "\"paperId\":\"$it\"," } ?: ""}"agree":$agree}"""
        return send(HttpMethod.POST, "${uri(meeting)}/decisions", person, body, mapOf("If-Match" to "\"$version\""))
    }

    private fun JsonNode.draftItem(id: UUID): JsonNode =
        get("sections")
            .list()
            .flatMap { it["items"].list() }
            .flatMap { listOf(it) + it["subItems"].list() }
            .single { it["id"].asString() == id.toString() }

    private companion object {
        const val CANARY_HIDDEN = "CANARY hidden title for the vice-chair"
        const val CANARY_DESCRIPTION = "CANARY description for the vice-chair"
        const val CANARY_PAPER = "CANARY late paper title"
    }
}
