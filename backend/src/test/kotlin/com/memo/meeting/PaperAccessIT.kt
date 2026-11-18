package com.memo.meeting

import com.memo.meeting.internal.ItemChange
import com.memo.meeting.internal.MeetingRow
import com.memo.testsupport.AppRoleJdbc
import com.memo.testsupport.Person
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import java.util.UUID

/**
 * The paper rule's matrix (test plan E03, T4; ADR-0061, design E03 §1.9): who sees which line of a published agenda and
 * which paper, by how they relate to the meeting (secretary, chair, member, member not cleared, observer, guest, item
 * presenter, `forum.read`, outsider) and by the item's restriction, hidden title, members-only section and level; then
 * by the meeting's state (draft revision, cancelled, minutes approved) and a former member. Each case is checked through
 * the members' agenda, paper opens, the revision list, the preview "as a member" and the generic files endpoint.
 */
class PaperAccessIT : PaperFixture() {
    /** One published agenda with an item of each kind the rule distinguishes. */
    private inner class Agenda {
        val meeting: MeetingRow = meeting()

        /** Restricted to d1 and d2: others see "A. {title} — papers restricted". */
        val restricted = addItem(meeting, "Credit facility for Acme")

        /** Restricted to d1, title hidden: others see "B. Restricted item". */
        val hidden = addItem(meeting, "Dismissal of the head of treasury")

        /** Open, with one open paper and one for d1 only. */
        val open = addItem(meeting, "Liquidity report", section = "For noting")

        /** In a members-only section: absent for guests and observers. */
        val camera = addItem(meeting, "Executive succession", sectionId = inCamera(meeting))

        /** The presenter attends it. */
        val attended = addItem(meeting, "Treasury update", section = "For noting")

        /** At the confidential level: d3 is not cleared. */
        val confidential = addItem(meeting, "Capital stress test")

        val restrictedPaper: UUID
        val hiddenPaper: UUID
        val openPaper: UUID
        val d1Paper: UUID
        val cameraPaper: UUID
        val attendedPaper: UUID
        val confidentialPaper: UUID
        val openFile: UUID

        init {
            change(meeting, restricted, ItemChange("Credit facility for Acme", restrictedTo = listOf(d1.id, d2.id)))
            change(meeting, hidden, ItemChange("Dismissal of the head of treasury", restrictedTo = listOf(d1.id), titleHidden = true))
            change(meeting, attended, ItemChange("Treasury update", attendees = listOf(presenter.id)))
            change(meeting, confidential, ItemChange("Capital stress test", classificationId = levels()[1].id))
            restrictedPaper = paper(meeting, restricted, upload(meeting, "acme.pdf"))
            hiddenPaper = paper(meeting, hidden, upload(meeting, "dismissal.pdf"))
            openFile = upload(meeting, "liquidity.pdf")
            openPaper = paper(meeting, open, openFile)
            d1Paper = paper(meeting, open, upload(meeting, "liquidity-annex.pdf"), restrictedTo = listOf(d1.id))
            cameraPaper = paper(meeting, camera, upload(meeting, "succession.pdf"))
            attendedPaper = paper(meeting, attended, upload(meeting, "treasury.pdf"))
            confidentialPaper = paper(meeting, confidential, upload(meeting, "stress.pdf"))
            publish(meeting)
        }

        val items get() = arrayOf(restricted, hidden, open, camera, attended, confidential)
        val allPapers get() = listOf(restrictedPaper, hiddenPaper, openPaper, d1Paper, cameraPaper, attendedPaper, confidentialPaper)
    }

    @Test
    fun `each viewer sees the lines and papers the rule gives them, through the agenda, paper opens and the preview`() {
        val a = Agenda()
        val full = List(a.items.size) { "FULL" }

        // Officers and the named member see everything (officers also the paper restricted to d1).
        assertSees(secretary, a, full, a.allPapers)
        assertSees(chair, a, full, a.allPapers)
        assertSees(d1, a, full, a.allPapers)
        // d2: not named on the hidden item, nor on the d1 paper.
        assertSees(d2, a, listOf("FULL", "HIDDEN", "FULL", "FULL", "FULL", "FULL"), a.allPapers - a.hiddenPaper - a.d1Paper)
        // d3: not named, and not cleared for the confidential item, whose title they still see.
        assertSees(
            d3,
            a,
            listOf("TITLE", "HIDDEN", "FULL", "FULL", "FULL", "TITLE"),
            listOf(a.openPaper, a.cameraPaper, a.attendedPaper),
        )
        // The observer and the guest: nothing of the members-only section, not even its heading.
        val noCamera = listOf("TITLE", "HIDDEN", "FULL", null, "FULL", "FULL")
        assertSees(observer, a, noCamera, listOf(a.openPaper, a.attendedPaper, a.confidentialPaper))
        assertSees(guest, a, noCamera, listOf(a.openPaper, a.attendedPaper, a.confidentialPaper))
        assertThat(agenda(guest, a.meeting).sectionTitles()).doesNotContain("In camera")
        assertThat(agenda(d3, a.meeting).sectionTitles()).contains("In camera")
        // The presenter: their item in full, the other lines by title.
        assertSees(presenter, a, listOf("TITLE", "HIDDEN", "TITLE", null, "FULL", "TITLE"), listOf(a.attendedPaper))
        // forum.read: the unrestricted, unhidden titles (members-only and confidential ones too), and no paper.
        assertSees(auditor, a, listOf(null, null, "TITLE", "TITLE", "TITLE", "TITLE"), emptyList())
        // Outside the meeting: 404 everywhere.
        assertThat(agendaOf(outsider, a.meeting)).hasStatus(HttpStatus.NOT_FOUND)
        assertThat(read("/api/v1/meetings/${a.meeting.id}/agenda/revisions", outsider.session)).hasStatus(HttpStatus.NOT_FOUND)
        a.allPapers.forEach { assertThat(open(outsider, a.meeting, it)).hasStatus(HttpStatus.NOT_FOUND) }

        // A line the viewer sees only by title carries no description, presenters or papers; a hidden one no title.
        val ofD3 = agenda(d3, a.meeting).lines()
        assertThat(ofD3.getValue(a.restricted)["title"].asString()).isEqualTo("Credit facility for Acme")
        assertThat(ofD3.getValue(a.hidden).absent("title")).isTrue()
        assertThat(ofD3.getValue(a.hidden)["number"].asString()).isNotBlank()

        // The preview "as a member" runs the same rule on the draft: the secretary sees d3's view; a member may not preview.
        val preview = read("/api/v1/meetings/${a.meeting.id}/agenda/preview?as=${d3.id}", secretary.session)
        assertThat(preview).hasStatusOk()
        assertThat(preview.json()["preview"].asBoolean()).isTrue()
        assertThat(preview.json().access(*a.items)).containsExactly("TITLE", "HIDDEN", "FULL", "FULL", "FULL", "TITLE")
        assertThat(preview.json().papersOf(a.open)).containsExactly(a.openPaper)
        val asGuest = read("/api/v1/meetings/${a.meeting.id}/agenda/preview?as=${guest.id}", secretary.session).json()
        assertThat(asGuest.sectionTitles()).doesNotContain("In camera")
        assertThat(read("/api/v1/meetings/${a.meeting.id}/agenda/preview?as=${d3.id}", d1.session)).hasStatus(HttpStatus.FORBIDDEN)
        assertThat(read("/api/v1/meetings/${a.meeting.id}/agenda/preview?as=${outsider.id}", secretary.session))
            .hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)

        // The generic files endpoints serve a meeting paper to its officers only: members read it through the meeting.
        assertThat(read("/api/v1/files/${a.openFile}/content", secretary.session)).hasStatusOk()
        assertThat(read("/api/v1/files/${a.openFile}/content", d1.session)).hasStatus(HttpStatus.FORBIDDEN)
        assertThat(read("/api/v1/files/${a.openFile}/content", outsider.session)).hasStatus(HttpStatus.FORBIDDEN)

        // The revision list: the audience sees numbers, dates and notes; officers also who published it.
        val listed = read("/api/v1/meetings/${a.meeting.id}/agenda/revisions", d3.session).json()
        assertThat(listed.list().map { it["number"].asInt() }).containsExactly(1)
        assertThat(listed[0].absent("publishedBy")).isTrue()
        val officers = read("/api/v1/meetings/${a.meeting.id}/agenda/revisions", secretary.session).json()
        assertThat(officers[0]["publishedBy"]["id"].asString()).isEqualTo(secretary.id.toString())
    }

    @Test
    fun `members read the published revision only, never the draft, and a cancelled meeting's papers are for officers`() {
        val meeting = meeting()
        val item = addItem(meeting, "Strategy")
        val draftPaper = paper(meeting, item, upload(meeting, "strategy.pdf"))
        // Nothing published: an empty agenda, and the draft's paper is not there to open.
        val before = agenda(d1, meeting)
        assertThat(before.absent("revision")).isTrue()
        assertThat(before["sections"].size()).isZero()
        assertThat(open(d1, meeting, draftPaper)).hasStatus(HttpStatus.NOT_FOUND)
        assertThat(open(secretary, meeting, draftPaper)).hasStatus(HttpStatus.NOT_FOUND)

        publish(meeting)
        assertThat(open(d1, meeting, draftPaper)).hasStatusOk()
        // A later draft change is invisible until it is published.
        val later = addItem(meeting, "Late addition")
        assertThat(agenda(d1, meeting).lines()).doesNotContainKey(later)
        assertThat(agendaOf(d1, meeting, revision = 2)).hasStatus(HttpStatus.NOT_FOUND)

        // Cancelled: members keep the lines, lose the items and the papers; officers keep everything.
        val current = asUser(secretary.account) { reads.get(meeting.id) }
        asUser(secretary.account) { lifecycle.cancel(meeting.id, current.version, "Postponed to January") }
        assertThat(agenda(d1, meeting).access(item)).containsExactly("TITLE")
        assertThat(open(d1, meeting, draftPaper)).hasStatus(HttpStatus.NOT_FOUND)
        assertThat(open(guest, meeting, draftPaper)).hasStatus(HttpStatus.NOT_FOUND)
        assertThat(open(secretary, meeting, draftPaper)).hasStatusOk()
    }

    @Test
    fun `a former member and, once the minutes are approved, a guest lose the papers but keep the meeting`() {
        val meeting = meeting()
        val item = addItem(meeting, "Annual risk report")
        val paper = paper(meeting, item, upload(meeting, "risk.pdf"))
        publish(meeting)
        assertThat(open(d2, meeting, paper)).hasStatusOk()

        // d2's term ended yesterday: still on the meeting's audience (it is their history), out of office today.
        commit("update meeting_audience set until = current_date - 1 where meeting_id = ? and user_id = ?", meeting.id, d2.id)
        assertThat(read("/api/v1/meetings/${meeting.id}", d2.session)).hasStatusOk()
        assertThat(agenda(d2, meeting).lines()).isEmpty()
        assertThat(open(d2, meeting, paper)).hasStatus(HttpStatus.NOT_FOUND)

        // Minutes approved (E06's state): guests lose the papers, members keep them.
        assertThat(open(guest, meeting, paper)).hasStatusOk()
        commit("update meeting set status = 'MINUTES_APPROVED' where id = ?", meeting.id)
        assertThat(open(guest, meeting, paper)).hasStatus(HttpStatus.NOT_FOUND)
        assertThat(agenda(guest, meeting).lines()).isEmpty()
        assertThat(open(d1, meeting, paper)).hasStatusOk()
    }

    private fun assertSees(
        person: Person,
        a: Agenda,
        lines: List<String?>,
        readable: List<UUID>,
    ) {
        val agenda = agenda(person, a.meeting)
        assertThat(agenda.access(*a.items)).describedAs("lines of ${who(person)}").containsExactlyElementsOf(lines)
        val listed = a.items.flatMap { agenda.papersOf(it) }
        assertThat(listed).describedAs("papers listed to ${who(person)}").containsExactlyInAnyOrderElementsOf(readable)
        a.allPapers.forEach { paper ->
            val status = open(person, a.meeting, paper).response.status
            assertThat(status).describedAs("${who(person)} opening $paper").isEqualTo(if (paper in readable) OK else NOT_FOUND)
        }
    }

    private fun who(person: Person): String =
        mapOf(
            secretary to "secretary",
            chair to "chair",
            d1 to "d1",
            d2 to "d2",
            d3 to "d3",
            observer to "observer",
            guest to "guest",
            presenter to "presenter",
            auditor to "auditor",
        )[person] ?: "someone"

    private companion object {
        const val OK = 200
        const val NOT_FOUND = 404
    }
}
