package com.memo.meeting

import com.memo.forum.internal.ForumMemberStore
import com.memo.forum.internal.MemberEdit
import com.memo.meeting.internal.MeetingRow
import com.memo.testsupport.Person
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import java.util.UUID

/**
 * The alternate inherits the member's recusals (test plan E05 T3 `AlternateRecusalIT`, ADR-0064, design E05 §1.5): an
 * alternate standing in for a member recused from an item sees that item as the member would (its line only), gets 404
 * on its papers whichever way they are asked for, and is recused in `Recusals` too; the alternate's own declarations
 * apply as well; once released, nothing of the meeting is theirs. Answers go over HTTP here (the controller, its
 * Idempotency-Key, If-Match and ETag).
 */
class AlternateRecusalIT : RecusalFixture() {
    @Autowired lateinit var memberStore: ForumMemberStore

    @Autowired lateinit var recusals: Recusals

    lateinit var alternate: Person

    @BeforeEach
    fun alternateSeat() {
        alternate = person(clearance = 1)
        val seat = transactions.readInTenant(tenant.id) { memberStore.members(brc).single { it.userId == d1.id } }
        asUser(admin) {
            members.edit(brc, seat.id, seat.version, MemberEdit(seat.voting, seat.attributes, alternate.id, seat.termEndsOn, null))
        }
    }

    @Test
    fun `the alternate of a recused member gets the item's line only and 404 on its papers, and is recused in Recusals`() {
        val meeting = meeting()
        val item = addItem(meeting, TITLE)
        val other = addItem(meeting, "Liquidity report")
        val file = upload(meeting, "delta.pdf")
        val paper = paper(meeting, item, file, title = "Credit memorandum")
        val otherPaper = paper(meeting, other, upload(meeting, "liquidity.pdf"), title = "Liquidity pack")
        publish(meeting)
        declare(d1, meeting, item)

        // Before standing in, the alternate does not see the meeting at all.
        assertThat(agendaOf(alternate, meeting)).hasStatus(HttpStatus.NOT_FOUND)

        val asked = rsvp(d1, meeting, """{"answer":"APOLOGIES","alternateAttends":true}""")
        assertThat(asked).hasStatusOk()
        assertThat(asked.response.getHeader("ETag")).isEqualTo("\"0\"")
        assertThat(asked.json()["alternateStandingIn"].asBoolean()).isTrue()

        for (revision in listOf(null, 1)) {
            val agenda = agenda(alternate, meeting, revision)
            val line = agenda.lines().getValue(item)
            assertThat(line["access"].asString() to line["recused"].asBoolean()).isEqualTo("TITLE" to true)
            assertThat(line["papers"].list()).isEmpty()
            assertThat(agenda.toString()).doesNotContain("Credit memorandum")
            assertThat(agenda.access(other)).containsExactly("FULL")
        }
        listOf("", "?revision=1", "?kind=ORIGINAL").forEach { query ->
            assertThat(open(alternate, meeting, paper, query)).hasStatus(HttpStatus.NOT_FOUND)
        }
        assertThat(read("/api/v1/files/$file/content", alternate.session)).hasStatus(HttpStatus.FORBIDDEN)
        assertThat(open(alternate, meeting, otherPaper)).hasStatusOk()
        transactions.readInTenant(tenant.id) {
            assertThat(recusals.isRecused(alternate.id, item)).isTrue()
            assertThat(recusals.isRecused(alternate.id, other)).isFalse()
            assertThat(recusals.ofMeeting(meeting.id)[item]).containsExactlyInAnyOrder(d1.id, alternate.id)
        }

        // Their own declaration applies too.
        declare(alternate, meeting, other)
        assertThat(open(alternate, meeting, otherPaper)).hasStatus(HttpStatus.NOT_FOUND)
        assertThat(agenda(alternate, meeting).lines().getValue(other)["recused"].asBoolean()).isTrue()

        // d1 comes after all: the alternate is released and the meeting is no longer theirs.
        val back = rsvp(d1, meeting, """{"answer":"IN_PERSON"}""", version = 0)
        assertThat(back).hasStatusOk()
        assertThat(back.response.getHeader("ETag")).isEqualTo("\"1\"")
        assertThat(agendaOf(alternate, meeting)).hasStatus(HttpStatus.NOT_FOUND)
        transactions.readInTenant(tenant.id) { assertThat(recusals.isRecused(alternate.id, item)).isFalse() }
    }

    @Test
    fun `the replies endpoints answer the audience, refuse the rest, and need If-Match once answered`() {
        val meeting = meeting()
        assertThat(rsvp(d1, meeting, """{"answer":"REMOTE"}""").json()["errors"].toString()).contains("MODE_NOT_OFFERED")
        assertThat(rsvp(d1, meeting, """{"answer":"IN_PERSON","note":"Late"}""")).hasStatusOk()
        assertThat(rsvp(d1, meeting, """{"answer":"APOLOGIES"}""")).hasStatus(HttpStatus.PRECONDITION_REQUIRED)
        assertThat(rsvp(d1, meeting, """{"answer":"APOLOGIES"}""", version = 5)).hasStatus(HttpStatus.PRECONDITION_FAILED)
        assertThat(rsvp(outsider, meeting, """{"answer":"IN_PERSON"}""")).hasStatus(HttpStatus.NOT_FOUND)
        assertThat(rsvp(auditor, meeting, """{"answer":"IN_PERSON"}""")).hasStatus(HttpStatus.FORBIDDEN)

        // The same key twice is one answer.
        val key = UUID.randomUUID().toString()
        val headers = mapOf("Idempotency-Key" to key)
        assertThat(
            send(HttpMethod.PUT, "/api/v1/meetings/${meeting.id}/rsvp/mine", d2, """{"answer":"IN_PERSON"}""", headers),
        ).hasStatusOk()
        assertThat(
            send(HttpMethod.PUT, "/api/v1/meetings/${meeting.id}/rsvp/mine", d2, """{"answer":"IN_PERSON"}""", headers),
        ).hasStatusOk()

        val mine = read("/api/v1/meetings/${meeting.id}/rsvp/mine", d1.session)
        assertThat(mine).hasStatusOk()
        assertThat(mine.json()["note"].asString()).isEqualTo("Late")
        assertThat(read("/api/v1/meetings/${meeting.id}/rsvp/mine", auditor.session)).hasStatus(HttpStatus.FORBIDDEN)

        val ofMember = read("/api/v1/meetings/${meeting.id}/rsvps", d2.session).json()
        assertThat(ofMember.absent("people")).isTrue()
        assertThat(ofMember["members"]["inPerson"].asInt()).isEqualTo(2)
        val ofSecretary = read("/api/v1/meetings/${meeting.id}/rsvps", secretary.session).json()
        assertThat(ofSecretary["people"].list().first { it["userId"].asString() == d1.id.toString() }["note"].asString())
            .isEqualTo("Late")

        val recorded =
            send(
                HttpMethod.PUT,
                "/api/v1/meetings/${meeting.id}/rsvps/${d3.id}",
                secretary,
                """{"answer":"APOLOGIES","how":"EMAIL","howNote":"email of 4 Dec"}""",
            )
        assertThat(recorded).hasStatusOk()
        assertThat(recorded.json()["how"].asString()).isEqualTo("EMAIL")
        assertThat(
            send(HttpMethod.PUT, "/api/v1/meetings/${meeting.id}/rsvps/${d3.id}", chair, """{"answer":"APOLOGIES","how":"EMAIL"}"""),
        ).hasStatus(HttpStatus.FORBIDDEN)
    }

    private fun rsvp(
        person: Person,
        meeting: MeetingRow,
        body: String,
        version: Long? = null,
    ) = send(
        HttpMethod.PUT,
        "/api/v1/meetings/${meeting.id}/rsvp/mine",
        person,
        body,
        mapOf("Idempotency-Key" to UUID.randomUUID().toString()) + listOfNotNull(version?.let { "If-Match" to "\"$it\"" }),
    )
}
