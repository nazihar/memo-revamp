package com.memo.meeting

import com.memo.meeting.internal.ItemChange
import com.memo.meeting.internal.MeetingRow
import com.memo.testsupport.Person
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus

/**
 * The recusal rule on the members' surfaces (test plan E04, T2; ADR-0062, design E04 §1.6, §1.7): a member with an
 * effective declaration (RECUSED or AWAITING_RULING) on an item, or on its parent, sees its line and their own declaration
 * and nothing else of it, in every revision; its papers are 404 whichever way they are asked for; its changes are neither
 * counted nor told; it cannot be asked to be discussed. A ruling "may take part" gives it back.
 */
class RecusalRuleIT : RecusalFixture() {
    @Test
    fun `a recused member sees the item's line and their declaration, and nothing else of it, in every revision`() {
        val meeting = meeting()
        val item = addItem(meeting, TITLE)
        change(meeting, item, ItemChange(TITLE, DESCRIPTION, resolutionSought = "Approve the facility"))
        val sub = subItem(meeting, item, "Collateral valuation")
        val file = upload(meeting, "delta.pdf")
        val paper = paper(meeting, item, file, title = "Credit memorandum")
        val subPaper = paper(meeting, sub, upload(meeting, "valuation.pdf"), title = "Valuation report")
        publish(meeting)

        val declaration = declare(d1, meeting, item)
        assertThat(declaration.state).isEqualTo(DeclarationState.RECUSED)
        for (revision in listOf(null, 1)) {
            val agenda = agenda(d1, meeting, revision)
            val line = agenda.lines().getValue(item)
            assertThat(line["access"].asString()).isEqualTo("TITLE")
            assertThat(line["recused"].asBoolean()).isTrue()
            assertThat(line["title"].asString()).isEqualTo(TITLE)
            assertThat(line.absent("number")).isFalse()
            assertThat(line.absent("startsAt")).isFalse()
            assertThat(line.absent("description") && line.absent("resolutionSought")).isTrue()
            assertThat(line["papers"].list()).isEmpty()
            assertThat(line["presenters"].list()).isEmpty()
            assertThat(line["myDeclaration"]["state"].asString()).isEqualTo("RECUSED")
            assertThat(line["myDeclaration"]["nature"].asString()).isEqualTo(NATURE)
            // A declaration on the item covers its sub-items.
            val subLine = agenda.lines().getValue(sub)
            assertThat(subLine["access"].asString() to subLine["recused"].asBoolean()).isEqualTo("TITLE" to true)
            assertThat(agenda.toString()).doesNotContain(DESCRIPTION, "Credit memorandum", "Valuation report")
        }
        listOf(paper, subPaper).forEach { p ->
            listOf("", "?revision=1", "?kind=ORIGINAL").forEach { query ->
                assertThat(open(d1, meeting, p, query)).hasStatus(HttpStatus.NOT_FOUND)
            }
        }
        assertThat(read("/api/v1/files/$file/content", d1.session)).hasStatus(HttpStatus.FORBIDDEN)

        // Everyone else is unchanged: d2 reads it in full and sees d1's declaration (never its nature); so does the chair.
        val ofD2 = agenda(d2, meeting).lines().getValue(item)
        assertThat(ofD2["access"].asString() to ofD2["recused"].asBoolean()).isEqualTo("FULL" to false)
        assertThat(ofD2["declarations"].list().map { it["user"]["id"].asString() }).containsExactly(d1.id.toString())
        assertThat(ofD2.toString()).doesNotContain(NATURE)
        assertThat(agenda(chair, meeting).access(item)).containsExactly("FULL")
        assertThat(open(d2, meeting, paper)).hasStatusOk()
    }

    @Test
    fun `waiting for the chair's ruling withholds the item, and may-take-part gives it back`() {
        val meeting = meeting()
        val item = addItem(meeting, TITLE)
        val paper = paper(meeting, item, upload(meeting, "delta.pdf"), title = "Credit memorandum")
        publish(meeting)

        val asked = declare(d1, meeting, item, DeclarationIntent.ASK_CHAIR)
        assertThat(asked.state).isEqualTo(DeclarationState.AWAITING_RULING)
        val waiting = agenda(d1, meeting).lines().getValue(item)
        assertThat(waiting["access"].asString() to waiting["recused"].asBoolean()).isEqualTo("TITLE" to true)
        assertThat(waiting["myDeclaration"]["state"].asString()).isEqualTo("AWAITING_RULING")
        assertThat(open(d1, meeting, paper)).hasStatus(HttpStatus.NOT_FOUND)

        rule(chair, meeting, asked, RulingOutcome.TAKE_PART)
        val taking = agenda(d1, meeting).lines().getValue(item)
        assertThat(taking["access"].asString() to taking["recused"].asBoolean()).isEqualTo("FULL" to false)
        assertThat(taking["myDeclaration"]["state"].asString()).isEqualTo("TAKING_PART")
        assertThat(agenda(d1, meeting).papersOf(item)).containsExactly(paper)
        assertThat(open(d1, meeting, paper)).hasStatusOk()
    }

    @Test
    fun `with the title hidden the recused line reads Restricted item, and a declaration made later hides old revisions too`() {
        val meeting = meeting()
        val item = addItem(meeting, CANARY)
        change(meeting, item, ItemChange(CANARY, titleHidden = true))
        publish(meeting)
        // d1 sees the item in full (it is not restricted): its title too, until they declare.
        assertThat(agenda(d1, meeting).lines().getValue(item)["title"].asString()).isEqualTo(CANARY)

        declare(d1, meeting, item)
        listOf(null, 1).forEach { revision ->
            val line = agenda(d1, meeting, revision).lines().getValue(item)
            assertThat(line["access"].asString() to line["recused"].asBoolean()).isEqualTo("HIDDEN" to true)
            assertThat(line.absent("title")).isTrue()
            assertThat(agendaOf(d1, meeting, revision).response.contentAsString.replace(NATURE, "")).doesNotContain(CANARY)
        }
    }

    @Test
    fun `a change only to the recused item's papers is neither told nor counted, and it cannot be asked to be discussed`() {
        val meeting = meeting()
        val item = addItem(meeting, TITLE, section = "Consent items")
        val file = upload(meeting, "delta.pdf")
        paper(meeting, item, file, title = "Credit memorandum")
        val other = addItem(meeting, "Liquidity report")
        publish(meeting)
        listOf(d1, d2).forEach { person ->
            assertThat(send(HttpMethod.POST, "/api/v1/meetings/${meeting.id}/seen", person, """{"revision":1}""", emptyMap()))
                .hasStatus(HttpStatus.NO_CONTENT)
        }
        declare(d1, meeting, item)

        // Recused, d1 cannot ask to discuss the consent item (404, as for an item they do not see).
        val asked = send(HttpMethod.POST, "/api/v1/meetings/${meeting.id}/agenda/items/$item/discussion-requests", d1)
        assertThat(asked).hasStatus(HttpStatus.NOT_FOUND)

        // Revision 2 replaces the item's paper only.
        replace(file)
        publish(meeting, "The credit memorandum was updated")
        send(meeting, 2)
        assertThat(notified(d1, "MEETING_AGENDA_CHANGED")).isZero()
        assertThat(notified(d2, "MEETING_AGENDA_CHANGED")).isEqualTo(1)
        assertThat(agenda(d1, meeting)["whatChanged"]["count"].asInt()).isZero()
        assertThat(agenda(d2, meeting)["whatChanged"]["count"].asInt()).isEqualTo(1)
        assertThat(changesSinceSeen(d1, meeting)).isZero()
        assertThat(changesSinceSeen(d2, meeting)).isEqualTo(1)
        // The revision list carries nothing of the item.
        assertThat(read("/api/v1/meetings/${meeting.id}/agenda/revisions", d1.session).response.contentAsString)
            .doesNotContain("Credit memorandum")
        assertThat(agenda(d1, meeting).access(other)).containsExactly("FULL")
    }

    private fun changesSinceSeen(
        person: Person,
        meeting: MeetingRow,
    ): Int =
        read("/api/v1/me/meetings", person.session)
            .json()
            .list()
            .single { it["id"].asString() == meeting.id.toString() }["changesSinceSeen"]
            .asInt()

    private companion object {
        const val DESCRIPTION = "A facility of BDT 50 crore secured on the Narayanganj mill"
        const val CANARY = "CANARY hidden recused title"
    }
}
