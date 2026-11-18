package com.memo.meeting

import com.memo.meeting.internal.ItemChange
import com.memo.meeting.internal.MeetingRow
import com.memo.testsupport.Person
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import tools.jackson.databind.JsonNode
import java.util.UUID

/**
 * "What changed since you last looked" (test plan E03, T4; design E03 §1.3, §1.9): per person, from the last revision
 * they saw, limited to what they see — a title line shows only what its line shows, a hidden one no retitling, and a
 * replaced paper only to those who may read it; the count on "My meetings"; the last revision seen moves forward only.
 */
class WhatChangedIT : PaperFixture() {
    @Test
    fun `each person reads what changed since they last looked, as far as they see it`() {
        val meeting = meeting()
        val restricted = addItem(meeting, "Credit facility for Acme")
        change(meeting, restricted, ItemChange("Credit facility for Acme", restrictedTo = listOf(d1.id, d2.id)))
        val hidden = addItem(meeting, "Dismissal of the head of treasury")
        change(meeting, hidden, ItemChange("Dismissal of the head of treasury", restrictedTo = listOf(d1.id), titleHidden = true))
        val report = addItem(meeting, "Liquidity report", section = "For noting")
        paper(meeting, report, upload(meeting, "liquidity.pdf"))
        val annex = upload(meeting, "liquidity-annex.pdf")
        paper(meeting, report, annex, restrictedTo = listOf(d1.id))
        val dropped = addItem(meeting, "Item to drop")
        publish(meeting)

        // Never looked: nothing to compare with.
        assertThat(agenda(d1, meeting).absent("whatChanged")).isTrue()
        assertThat(agenda(d1, meeting).absent("lastSeenRevision")).isTrue()
        listOf(d1, d3).forEach { assertThat(seen(it, meeting, 1)).hasStatus(HttpStatus.NO_CONTENT) }

        // Revision 2: the restricted and the hidden items retitled, the d1-only annex replaced, one item added, one removed.
        change(meeting, restricted, ItemChange("Credit facility for Acme Ltd", restrictedTo = listOf(d1.id, d2.id)))
        change(meeting, hidden, ItemChange("Dismissal of the treasurer", restrictedTo = listOf(d1.id), titleHidden = true))
        replace(annex)
        val added = addItem(meeting, "Cyber resilience")
        asUser(secretary.account) { items.remove(meeting.id, dropped, version(meeting)) }
        publish(meeting, "Updated papers")

        val ofD1 = agenda(d1, meeting)
        assertThat(ofD1["lastSeenRevision"].asInt()).isEqualTo(1)
        val changedForD1 = changed(ofD1)
        assertThat(changedForD1.keys).containsExactlyInAnyOrder(restricted, hidden, report, added, dropped)
        assertThat(flags(changedForD1.getValue(report))).containsExactly("PAPER_REPLACED")
        assertThat(changedForD1.getValue(report)["papers"].list().map { it["flags"][0].asString() }).containsExactly("REPLACED")
        assertThat(changedForD1.getValue(dropped)["removed"].asBoolean()).isTrue()
        assertThat(ofD1["whatChanged"]["count"].asInt()).isEqualTo(5)

        // d3: the restricted item as a title line (retitled), not the hidden one, and not the annex they may not read.
        val ofD3 = agenda(d3, meeting)
        val changedForD3 = changed(ofD3)
        assertThat(changedForD3.keys).containsExactlyInAnyOrder(restricted, added, dropped)
        assertThat(changedForD3.getValue(restricted)["title"].asString()).isEqualTo("Credit facility for Acme Ltd")
        assertThat(changedForD3.getValue(restricted)["access"].asString()).isEqualTo("TITLE")
        assertThat(ofD3.lines().getValue(report)["changes"].size()).isZero()
        assertThat(
            ofD1
                .lines()
                .getValue(report)["changes"]
                .list()
                .map { it.asString() },
        ).containsExactly("PAPER_REPLACED")

        // "My meetings" counts the same lines; someone who never looked gets no count.
        assertThat(mine(d1, meeting)["changesSinceSeen"].asInt()).isEqualTo(5)
        assertThat(mine(d3, meeting)["changesSinceSeen"].asInt()).isEqualTo(3)
        assertThat(mine(d3, meeting)["agendaRevision"].asInt()).isEqualTo(2)
        assertThat(mine(d2, meeting).absent("changesSinceSeen")).isTrue()
        assertThat(mine(d2, meeting).absent("lastSeenRevision")).isTrue()

        // An earlier revision shows no comparison; having seen the latest, nothing is new; the state never moves back.
        assertThat(agenda(d1, meeting, revision = 1).absent("whatChanged")).isTrue()
        assertThat(seen(d1, meeting, 2)).hasStatus(HttpStatus.NO_CONTENT)
        assertThat(agenda(d1, meeting).absent("whatChanged")).isTrue()
        assertThat(seen(d1, meeting, 1)).hasStatus(HttpStatus.NO_CONTENT)
        assertThat(agenda(d1, meeting)["lastSeenRevision"].asInt()).isEqualTo(2)
        assertThat(seen(d1, meeting, 3)).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
        assertThat(seen(outsider, meeting, 1)).hasStatus(HttpStatus.NOT_FOUND)
    }

    private fun changed(agenda: JsonNode): Map<UUID, JsonNode> =
        agenda["whatChanged"]["revisions"]
            .list()
            .flatMap { it["items"].list() }
            .associateBy { UUID.fromString(it["id"].asString()) }

    private fun flags(line: JsonNode): List<String> = line["flags"].list().map { it.asString() }

    private fun seen(
        person: Person,
        meeting: MeetingRow,
        revision: Int,
    ) = send(HttpMethod.POST, "/api/v1/meetings/${meeting.id}/seen", person, """{"revision":$revision}""", emptyMap())

    private fun mine(
        person: Person,
        meeting: MeetingRow,
    ): JsonNode {
        val mine = read("/api/v1/me/meetings", person.session)
        assertThat(mine).hasStatusOk()
        return mine.json().list().single { it["id"].asString() == meeting.id.toString() }
    }
}
