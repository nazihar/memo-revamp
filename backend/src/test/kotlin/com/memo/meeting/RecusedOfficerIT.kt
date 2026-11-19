package com.memo.meeting

import com.memo.common.authz.ForbiddenException
import com.memo.forum.ForumDirectory
import com.memo.meeting.internal.AgendaMoves
import com.memo.meeting.internal.ItemChange
import com.memo.meeting.internal.LateDecision
import com.memo.meeting.internal.Lateness
import com.memo.meeting.internal.MeetingNotifier
import com.memo.meeting.internal.MeetingRow
import com.memo.meeting.internal.MeetingStore
import com.memo.meeting.internal.MoveInput
import com.memo.meeting.internal.PaperChange
import com.memo.meeting.internal.PaperInput
import com.memo.meeting.internal.RecusedException
import com.memo.testsupport.Person
import com.memo.testsupport.Samples
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import tools.jackson.databind.JsonNode
import java.util.UUID

/**
 * A recused officer (test plan E04, T2; ADR-0062, design E04 §1.7): the builder shows them the item's line only and
 * refuses every change of it (403 `RECUSED`) while another secretary handles it; the preview never shows them past
 * their own recusal; the publish preview withholds the item's papers; a late paper on the item goes to whoever can decide
 * (the vice-chair when the chair is recused, else the secretaries not recused); discussion requests and withdrawn items
 * are not told to a recused officer.
 */
class RecusedOfficerIT : RecusalFixture() {
    @Autowired lateinit var notifier: MeetingNotifier

    @Autowired lateinit var directory: ForumDirectory

    @Autowired lateinit var meetingStore: MeetingStore

    @Autowired lateinit var moves: AgendaMoves

    @Test
    fun `a recused secretary sees the item's line in the builder and changes nothing of it, while the other secretary does`() {
        val meeting = meeting()
        val item = addItem(meeting, TITLE)
        change(meeting, item, ItemChange(TITLE, DESCRIPTION, restrictedTo = listOf(d1.id, d2.id)))
        val file = upload(meeting, "delta.pdf")
        val paper = paper(meeting, item, file, title = "Credit memorandum")
        val other = addItem(meeting, "Liquidity report")
        publish(meeting)
        declare(secretary, meeting, item)

        val draft = draftOf(secretary, meeting)
        assertThat(draft).hasStatusOk()
        val line = draft.json().draftItem(item)
        assertThat(line["recused"].asBoolean()).isTrue()
        assertThat(line["title"].asString()).isEqualTo(TITLE)
        assertThat(line["papers"].list()).isEmpty()
        assertThat(line.absent("description") && line.absent("restrictedTo")).isTrue()
        assertThat(line["myDeclaration"]["nature"].asString()).isEqualTo(NATURE)
        assertThat(draft.response.contentAsString).doesNotContain(DESCRIPTION, "Credit memorandum", "delta.pdf")
        assertThat(draftOf(secretary2, meeting).json().draftItem(item)["papers"].list()).hasSize(1)

        // Every change of the item or its papers: 403 RECUSED, over HTTP and in the services.
        val base = "/api/v1/meetings/${meeting.id}/agenda"
        listOf(
            change(secretary, HttpMethod.PATCH, "$base/items/$item", meeting, """{"title":"Renamed"}"""),
            change(secretary, HttpMethod.POST, "$base/items/$item/remove", meeting),
            change(secretary, HttpMethod.POST, "$base/items/$item/papers", meeting, """{"fileId":"${upload(meeting, "x.pdf")}"}"""),
            change(secretary, HttpMethod.PATCH, "$base/papers/$paper", meeting, """{"title":"Renamed"}"""),
            change(secretary, HttpMethod.POST, "$base/papers/$paper/remove", meeting),
            change(secretary, HttpMethod.POST, "$base/moves", meeting, """{"itemId":"$item","toPosition":5}"""),
            change(secretary, HttpMethod.POST, "$base/items", meeting, """{"kind":"ITEM","parentId":"$item","title":"Sub"}"""),
            change(secretary, HttpMethod.POST, "$base/moves", meeting, """{"itemId":"$other","toParentId":"$item","toPosition":0}"""),
        ).forEach { result ->
            assertThat(result).hasStatus(HttpStatus.FORBIDDEN)
            assertThat(result.json()["reason"].asString()).isEqualTo("RECUSED")
        }
        assertThatThrownBy {
            asUser(secretary.account) { papers.update(meeting.id, paper, version(meeting), PaperChange("Renamed")) }
        }.isInstanceOf(RecusedException::class.java)
        // A new version of the paper's file: refused, which rolls the upload back.
        val replaced =
            upload(
                secretary.session,
                Samples.pdf(2),
                "/api/v1/files/$file/versions",
                "v2.pdf",
                mapOf("If-Match" to "\"${read("/api/v1/files/$file", secretary2.session).json()["version"]}\""),
            )
        assertThat(replaced).hasStatus(HttpStatus.FORBIDDEN)

        // The rest of the agenda is theirs as before, and the other secretary handles the item.
        asUser(secretary.account) { items.update(meeting.id, other, version(meeting), ItemChange("Liquidity report, Q3")) }
        asUser(secretary.account) { moves.move(meeting.id, version(meeting), MoveInput(itemId = other, toPosition = 0)) }
        asUser(secretary2.account) { items.update(meeting.id, item, version(meeting), ItemChange("$TITLE, revised", DESCRIPTION)) }
        asUser(secretary2.account) { papers.update(meeting.id, paper, version(meeting), PaperChange("Credit memorandum v2")) }
        replaceAs(secretary2, file)
        assertThat(draftOf(secretary, meeting).json().draftItem(item)["title"].asString()).isEqualTo("$TITLE, revised")
    }

    @Test
    fun `the preview applies the previewing officer's own recusal, and the target's`() {
        val meeting = meeting()
        val item = addItem(meeting, TITLE)
        change(meeting, item, ItemChange(TITLE, DESCRIPTION))
        val file = upload(meeting, "delta.pdf")
        val paper = paper(meeting, item, file, title = "Credit memorandum")
        val hidden = addItem(meeting, CANARY)
        change(meeting, hidden, ItemChange(CANARY, titleHidden = true))
        publish(meeting)
        declare(secretary, meeting, item)
        declare(secretary, meeting, hidden)

        // The recused secretary previews d1, who reads both items in full: they see their own recused lines.
        val asD1 = preview(secretary, d1, meeting)
        val line = asD1.lines().getValue(item)
        assertThat(line["access"].asString() to line["recused"].asBoolean()).isEqualTo("TITLE" to true)
        assertThat(asD1.papersOf(item)).isEmpty()
        val hiddenLine = asD1.lines().getValue(hidden)
        assertThat(hiddenLine["access"].asString() to hiddenLine.absent("title")).isEqualTo("HIDDEN" to true)
        assertThat(asD1.toString()).doesNotContain(DESCRIPTION, "Credit memorandum", CANARY)
        // The other secretary previews d1 in full.
        val bySecretary2 = preview(secretary2, d1, meeting)
        assertThat(bySecretary2.access(item, hidden)).containsExactly("FULL", "FULL")
        assertThat(bySecretary2.papersOf(item)).containsExactly(paper)

        // A target's own recusal shows as their recused line to an officer who is not recused.
        declare(d1, meeting, item)
        val ofRecused = preview(secretary2, d1, meeting).lines().getValue(item)
        assertThat(ofRecused["access"].asString() to ofRecused["recused"].asBoolean()).isEqualTo("TITLE" to true)
        assertThat(ofRecused["papers"].list()).isEmpty()

        // The publish preview: a change only to the papers of their item is not shown to the recused secretary.
        replaceAs(secretary2, file)
        val forSecretary = read("/api/v1/meetings/${meeting.id}/agenda/publish-preview", secretary.session).json()
        assertThat(forSecretary["changes"].list().map { it["itemId"].asString() }).doesNotContain(item.toString())
        assertThat(forSecretary.toString()).doesNotContain("Credit memorandum", CANARY)
        val forSecretary2 = read("/api/v1/meetings/${meeting.id}/agenda/publish-preview", secretary2.session).json()
        assertThat(forSecretary2["changes"].list().map { it["itemId"].asString() }).contains(item.toString())
    }

    @Test
    fun `a late paper goes to the vice-chair when the chair is recused, and to the secretaries when both are`() {
        // Tomorrow's meeting is past the paper deadline: a new upload waits for the chair.
        val meeting = meeting(days = 1)
        val item = addItem(meeting, TITLE)
        val second = addItem(meeting, "Liquidity report")
        // Published before the late papers: the vice-chair, a member here, declares on a published item.
        publish(meeting)
        declare(chair, meeting, item)
        val (lateFile, otherFile, thirdFile) = listOf("late.pdf", "other.pdf", "third.pdf").map { upload(meeting, it) }

        val late = asUser(secretary.account) { papers.add(meeting.id, item, version(meeting), PaperInput(lateFile)) }
        assertThat(
            asUser(secretary.account) { views.draft(meeting.id) }
                .sections
                .flatMap { it.items }
                .single { it.id == item }
                .papers
                .single { it.id == late }
                .late,
        ).isEqualTo(Lateness.AWAITING_CHAIR)
        assertThat(notified(vice, "MEETING_LATE_PAPER")).isEqualTo(1)
        assertThat(notified(chair, "MEETING_LATE_PAPER")).isZero()

        // The recused chair cannot decide it; the vice-chair does; a vice-chair cannot decide where the chair can.
        assertThatThrownBy { asUser(chair.account) { chairing.decidePaper(meeting.id, late, version(meeting), LateDecision(true)) } }
            .isInstanceOf(RecusedException::class.java)
        asUser(vice.account) { chairing.decidePaper(meeting.id, late, version(meeting), LateDecision(true)) }
        val elsewhere =
            asUser(secretary.account) { papers.add(meeting.id, second, version(meeting), PaperInput(otherFile)) }
        assertThat(notified(chair, "MEETING_LATE_PAPER")).isEqualTo(1)
        assertThatThrownBy { asUser(vice.account) { chairing.decidePaper(meeting.id, elsewhere, version(meeting), LateDecision(true)) } }
            .isInstanceOf(ForbiddenException::class.java)

        // Both recused from the item: the secretaries not recused are told, and one records the committee's agreement.
        declare(vice, meeting, item)
        asUser(secretary.account) { papers.add(meeting.id, item, version(meeting), PaperInput(thirdFile)) }
        assertThat(notified(secretary2, "MEETING_LATE_PAPER")).isEqualTo(1)
        assertThat(notified(vice, "MEETING_LATE_PAPER")).isEqualTo(1)
    }

    @Test
    fun `discussion requests and withdrawn items are not told to an officer recused from the item`() {
        val meeting = meeting()
        val item = addItem(meeting, TITLE, section = "Consent items")
        publish(meeting)
        declare(secretary, meeting, item)
        declare(chair, meeting, item)

        val asked = send(HttpMethod.POST, "/api/v1/meetings/${meeting.id}/agenda/items/$item/discussion-requests", d2)
        assertThat(asked).hasStatus(HttpStatus.CREATED)
        assertThat(notified(secretary2, "MEETING_DISCUSSION_REQUESTED")).isEqualTo(1)
        assertThat(notified(secretary, "MEETING_DISCUSSION_REQUESTED")).isZero()
        assertThat(notified(chair, "MEETING_DISCUSSION_REQUESTED")).isZero()

        // An item of the published agenda that left it (its submission was withdrawn): only the secretary not recused.
        transactions.inTenant(tenant.id) {
            val row = checkNotNull(meetingStore.find(meeting.id))
            notifier.itemWithdrawn(row, checkNotNull(directory.forum(brc)), listOf(item, UUID.randomUUID()))
        }
        assertThat(notified(secretary2, "MEETING_ITEM_WITHDRAWN")).isEqualTo(1)
        assertThat(notified(secretary, "MEETING_ITEM_WITHDRAWN")).isZero()
    }

    private fun preview(
        officer: Person,
        target: Person,
        meeting: MeetingRow,
    ) = read("/api/v1/meetings/${meeting.id}/agenda/preview?as=${target.id}", officer.session).also { assertThat(it).hasStatusOk() }.json()

    private fun JsonNode.draftItem(id: UUID): JsonNode =
        get("sections")
            .list()
            .flatMap { it["items"].list() }
            .flatMap { listOf(it) + it["subItems"].list() }
            .single { it["id"].asString() == id.toString() }

    private companion object {
        const val DESCRIPTION = "A facility of BDT 50 crore secured on the Narayanganj mill"
        const val CANARY = "CANARY hidden title for the officer"
    }
}
