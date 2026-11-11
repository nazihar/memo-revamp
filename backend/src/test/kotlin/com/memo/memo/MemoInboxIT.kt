package com.memo.memo

import com.memo.testsupport.MemoIntegrationTest
import com.memo.testsupport.Person
import com.memo.testsupport.Samples
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus

/**
 * Inbox, boxes and history (test plan E01, T7): my open tasks newest first with keyset pages and a restricted placeholder
 * once I may no longer see the memo; the initiator's drafts, sent (with who holds each) and closed boxes; the history
 * grouped by round with earlier rounds marked.
 */
class MemoInboxIT : MemoIntegrationTest() {
    private fun submitted(
        initiator: Person,
        rank: Int = 0,
        vararg approvers: Person,
    ): String {
        val id = aDraft(initiator, rank)
        uploadDocument(id, initiator.session)
        route(id, initiator, *approvers)
        assertThat(post("/api/v1/memos/$id/submit", initiator.session)).hasStatusOk()
        return id
    }

    @Test
    fun `the inbox lists my open tasks newest first, in pages, and hides memos I may no longer see`() {
        val alice = person(clearance = 1)
        val bob = person(clearance = 1)
        val carol = person(clearance = 1)
        val older = submitted(alice, 0, bob, carol)
        val newer = submitted(alice, 1, bob)

        val all = read("/api/v1/inbox/tasks", bob.session).json()["items"]
        assertThat(all.size()).isEqualTo(2)
        assertThat(all[0]["memo"]["id"].asString()).isEqualTo(newer)
        assertThat(all[1]["memo"]["id"].asString()).isEqualTo(older)
        assertThat(all[1]["memo"]["subject"].asString()).isEqualTo("Memo")
        assertThat(read("/api/v1/inbox/tasks", carol.session).json()["items"].size()).isZero()

        val first = read("/api/v1/inbox/tasks?limit=1", bob.session).json()
        assertThat(first["items"][0]["memo"]["id"].asString()).isEqualTo(newer)
        val second = read("/api/v1/inbox/tasks?limit=1&cursor=${first["nextCursor"].asString()}", bob.session).json()
        assertThat(second["items"][0]["memo"]["id"].asString()).isEqualTo(older)
        assertThat(second.absent("nextCursor")).isTrue()
        assertThat(read("/api/v1/inbox/tasks?cursor=not-a-cursor", bob.session)).hasStatus(HttpStatus.BAD_REQUEST)

        // Bob's clearance drops below the newer memo's classification: its row stays, as a placeholder.
        clear(bob.id, 0)
        val restricted = read("/api/v1/inbox/tasks", bob.session).json()["items"][0]["memo"]
        assertThat(restricted["restricted"].asBoolean()).isTrue()
        assertThat(restricted.absent("subject")).isTrue()
        assertThat(get(newer, bob.session)).hasStatus(HttpStatus.NOT_FOUND)
    }

    @Test
    fun `drafts, sent with holders, and closed boxes list only my memos`() {
        val alice = person()
        val bob = person()
        val draft = aDraft(alice)
        val sent = submitted(alice, 0, bob)
        val discarded = aDraft(alice)
        post("/api/v1/memos/$discarded/discard", alice.session)
        aDraft(bob)

        fun box(name: String) = read("/api/v1/memos?box=$name", alice.session).json()["items"]
        assertThat(box("drafts").let { items -> (0 until items.size()).map { items[it]["id"].asString() } }).containsExactly(draft)
        val sentItems = box("sent")
        assertThat(sentItems.size()).isEqualTo(1)
        assertThat(sentItems[0]["id"].asString()).isEqualTo(sent)
        assertThat(sentItems[0]["holders"][0]["id"].asString()).isEqualTo(bob.id.toString())
        assertThat(box("closed")[0]["id"].asString()).isEqualTo(discarded)
        assertThat(read("/api/v1/memos?box=everything", alice.session)).hasStatus(HttpStatus.BAD_REQUEST)

        val firstDraft = read("/api/v1/memos?box=drafts&limit=1", bob.session).json()
        assertThat(firstDraft["items"].size()).isEqualTo(1)
        assertThat(firstDraft.absent("nextCursor")).isTrue()
    }

    @Test
    fun `the history groups actions by round, newest round first, with the document each was bound to`() {
        val alice = person()
        val bob = person()
        val stranger = person()
        val id = submitted(alice, 0, bob)
        val task = get(id, bob.session).json()["myTask"]["id"].asString()
        post("/api/v1/tasks/$task/return", bob.session, """{"remark":"Add the annex"}""")
        uploadDocument(id, alice.session, Samples.docx("<doc>v2</doc>"))
        post("/api/v1/memos/$id/resubmit", alice.session)

        val history = read("/api/v1/memos/$id/history", bob.session)
        assertThat(history).hasStatusOk()
        val rounds = history.json()
        assertThat(rounds.size()).isEqualTo(2)
        assertThat(rounds[0]["round"].asInt()).isEqualTo(2)
        assertThat(rounds[0]["current"].asBoolean()).isTrue()
        assertThat(rounds[0]["actions"][0]["kind"].asString()).isEqualTo("RESUBMIT")
        assertThat(rounds[0]["actions"][0]["documentNumber"].asInt()).isEqualTo(2)
        assertThat(rounds[1]["current"].asBoolean()).isFalse()
        val round1 = rounds[1]["actions"]
        assertThat(round1[0]["kind"].asString()).isEqualTo("SUBMIT")
        assertThat(round1[1]["kind"].asString()).isEqualTo("RETURN")
        assertThat(round1[1]["remark"].asString()).isEqualTo("Add the annex")
        assertThat(round1[1]["actor"]["id"].asString()).isEqualTo(bob.id.toString())
        assertThat(round1[1]["documentNumber"].asInt()).isEqualTo(1)
        assertThat(read("/api/v1/memos/$id/history", stranger.session)).hasStatus(HttpStatus.NOT_FOUND)
        assertThat(read("/api/v1/memos/${aDraft(alice)}/history", alice.session).json().size()).isZero()
    }
}
