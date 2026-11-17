package com.memo.memo

import com.memo.memo.internal.MemoNoteAttachmentPurpose
import com.memo.memo.internal.MemoNotes
import com.memo.memo.internal.MemoStore
import com.memo.memo.internal.ParticipantKind
import com.memo.testsupport.AppRoleJdbc
import com.memo.testsupport.Audit
import com.memo.testsupport.MemoIntegrationTest
import com.memo.testsupport.Person
import com.memo.testsupport.Samples
import com.memo.testsupport.sendJson
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * The note sheet (ADR-0049; test plan E03 T3): comments with mentions, refused when the person cannot read the memo;
 * corrections of one's own entries; decision remarks written with their actions; remarks before E03 merged in; the
 * record is append-only; private drafts; files on a note; copy recipients comment only when the tenant allows it.
 */
class MemoNotesIT : MemoIntegrationTest() {
    @Autowired lateinit var memoStore: MemoStore

    private fun text(value: String) = """{"type":"text","text":"$value"}"""

    private fun note(vararg inlines: String) =
        """{"type":"doc","content":[{"type":"paragraph","content":[${inlines.joinToString(",")}]}]}"""

    private fun mention(who: Person) = """{"type":"mention","attrs":{"id":"${who.id}","label":"Person ${who.id.toString().take(4)}"}}"""

    private fun post(
        memo: String,
        who: Person,
        body: String,
        extra: String = "",
    ) = post("/api/v1/memos/$memo/notes", who.session, """{"body":$body$extra}""")

    private fun sheet(
        memo: String,
        who: Person,
    ) = read("/api/v1/memos/$memo/notes", who.session).json()

    private fun kinds(user: Person): List<String> =
        AppRoleJdbc.inTransaction(tenant.id) { c ->
            c.prepareStatement("select kind from notification_item where user_id = ? order by created_at, id").use {
                it.setObject(1, user.id)
                it.executeQuery().use { rs -> generateSequence { if (rs.next()) rs.getString(1) else null }.toList() }
            }
        }

    private fun task(
        memo: String,
        who: Person,
    ) = get(memo, who.session).json()["myTask"]["id"].asString()

    @Test
    fun `a comment with a mention is refused until the person can read the memo, then notifies them`() {
        val alice = person()
        val bob = person()
        val memo = aDraft(alice)
        val refused = post(memo, alice, note(text("Please look at the rent, "), mention(bob)))
        assertThat(refused.errors()).containsExactly("mentions.${bob.id}:MENTION_NO_ACCESS")

        post("/api/v1/memos/$memo/shares", alice.session, """{"userId":"${bob.id}","reason":"For the rent"}""")
        val posted = post(memo, alice, note(text("Please look at the rent, "), mention(bob)))
        assertThat(posted).hasStatus(HttpStatus.CREATED)
        assertThat(posted.json()["kind"].asString()).isEqualTo("COMMENT")
        assertThat(posted.json()["text"].asString()).startsWith("Please look at the rent, @Person")
        assertThat(kinds(bob)).containsExactly("MEMO_MENTIONED")
        Audit.assertAudited(tenant.id, "memo.note.posted", memo)
        Audit.assertAudited(tenant.id, "memo.mentioned", memo)
        assertThat(sheet(memo, bob)["entries"][0]["id"].asString()).isEqualTo(posted.json()["id"].asString())
        // A note with a forbidden link never reaches the record.
        val link = """{"type":"text","text":"x","marks":[{"type":"link","attrs":{"href":"javascript:alert(1)"}}]}"""
        assertThat(post(memo, alice, note(link))).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
    }

    @Test
    fun `only the author corrects an entry, by a new entry that leaves the old one in place`() {
        val alice = person()
        val bob = person()
        val memo = aDraft(alice)
        post("/api/v1/memos/$memo/shares", alice.session, """{"userId":"${bob.id}","reason":"Review"}""")
        val first = post(memo, alice, note(text("Annex B attached."))).json()["id"].asString()
        val correction = post(memo, alice, note(text("Annex B and C attached.")), ""","correctsId":"$first"""")
        assertThat(correction).hasStatus(HttpStatus.CREATED)
        assertThat(correction.json()["kind"].asString()).isEqualTo("CORRECTION")
        Audit.assertAudited(tenant.id, "memo.note.corrected", memo)
        val entries = sheet(memo, alice)["entries"]
        val original = (0 until entries.size()).map { entries[it] }.single { it["id"].asString() == first }
        assertThat(original["text"].asString()).isEqualTo("Annex B attached.")
        assertThat(original["correctedBy"][0].asString()).isEqualTo(correction.json()["id"].asString())
        assertThat(original["canCorrect"].asBoolean()).isTrue()

        val stolen = post(memo, bob, note(text("No.")), ""","correctsId":"$first"""")
        assertThat(stolen.json()["reason"].asString()).isEqualTo("NOT_YOURS")
    }

    @Test
    fun `decision remarks are written with their actions, and remarks without an entry are merged in`() {
        val alice = person()
        val bob = person()
        val memo = aDraft(alice)
        uploadDocument(memo, alice.session)
        route(memo, alice, bob)
        val remark = note(text("For your approval, "), """{"type":"text","text":"urgent","marks":[{"type":"bold"}]}""")
        assertThat(post("/api/v1/memos/$memo/submit", alice.session, """{"remarkBody":$remark}""")).hasStatusOk()
        assertThat(post("/api/v1/tasks/${task(memo, bob)}/return", bob.session, """{"remark":"Attach annex B."}""")).hasStatusOk()
        assertThat(post("/api/v1/memos/$memo/resubmit", alice.session)).hasStatusOk()
        assertThat(post("/api/v1/memos/$memo/recall", alice.session, """{"remark":"Wrong annex"}""")).hasStatusOk()

        val entries = sheet(memo, alice)["entries"].let { all -> (0 until all.size()).map { all[it] } }
        assertThat(entries.map { it["actionKind"].asString() }).contains("SUBMIT", "RETURN")
        val submitted = entries.first { it["actionKind"].asString() == "SUBMIT" }
        assertThat(submitted["text"].asString()).isEqualTo("For your approval, urgent")
        assertThat(submitted["body"]["content"][0]["content"][1]["marks"][0]["type"].asString()).isEqualTo("bold")
        // The workflow keeps the same text as the action's remark.
        val history = read("/api/v1/memos/$memo/history", alice.session).json().toString()
        assertThat(history).contains("For your approval, urgent").contains("Attach annex B.")
        // The recall's remark has no entry of its own: it is merged in, and cannot be corrected.
        val recall = entries.single { it["text"].asString() == "Wrong annex" }
        assertThat(recall["id"].asString()).startsWith("action-")
        assertThat(recall["canCorrect"].asBoolean()).isFalse()
    }

    @Test
    fun `entries, mentions and file bindings cannot be changed or removed`() {
        val alice = person()
        val memo = aDraft(alice)
        post(memo, alice, note(text("Kept for ever.")))
        listOf(
            "update memo_note set body_text = 'x'",
            "delete from memo_note",
            "delete from memo_note_mention",
            "delete from memo_note_attachment",
        ).forEach { sql ->
            assertThatThrownBy { AppRoleJdbc.inTransaction(tenant.id) { c -> c.prepareStatement(sql).use { it.executeUpdate() } } }
                .describedAs(sql)
                .hasMessageContaining("permission denied")
        }
    }

    @Test
    fun `a draft is private, guarded by its version, and removed when the note is posted`() {
        val alice = person()
        val memo = aDraft(alice)
        assertThat(read("/api/v1/memos/$memo/notes/draft", alice.session)).hasStatus(HttpStatus.NO_CONTENT)
        val body = """{"body":${note(text("Half a thou"))}}"""
        val saved = mvc.sendJson(HttpMethod.PUT, tenant, "/api/v1/memos/$memo/notes/draft", alice.session, body, emptyMap())
        assertThat(saved.json()["version"].asLong()).isZero()
        val again =
            mvc.sendJson(
                HttpMethod.PUT,
                tenant,
                "/api/v1/memos/$memo/notes/draft",
                alice.session,
                body,
                mapOf("If-Match" to "\"0\""),
            )
        assertThat(again.json()["version"].asLong()).isEqualTo(1)
        val stale =
            mvc.sendJson(
                HttpMethod.PUT,
                tenant,
                "/api/v1/memos/$memo/notes/draft",
                alice.session,
                body,
                mapOf("If-Match" to "\"0\""),
            )
        assertThat(stale).hasStatus(HttpStatus.PRECONDITION_FAILED)
        assertThat(read("/api/v1/memos/$memo/notes/draft", alice.session).json()["body"]["content"][0]["content"][0]["text"].asString())
            .isEqualTo("Half a thou")

        post(memo, alice, note(text("Half a thought, finished.")))
        assertThat(read("/api/v1/memos/$memo/notes/draft", alice.session)).hasStatus(HttpStatus.NO_CONTENT)
    }

    @Test
    fun `files uploaded for a note are bound to it once`() {
        val alice = person()
        val memo = aDraft(alice)
        val upload =
            upload(alice.session, Samples.pdf(), "/api/v1/files?purpose=${MemoNoteAttachmentPurpose.KEY}&owner=$memo", "valuation.pdf")
        assertThat(upload).hasStatus(HttpStatus.CREATED)
        val fileId = upload.json()["fileId"].asString()
        val posted = post(memo, alice, note(text("Valuation attached.")), ""","attachmentIds":["$fileId"]""")
        assertThat(posted.json()["attachments"][0]["name"].asString()).isEqualTo("valuation.pdf")
        assertThat(
            post(memo, alice, note(text("Again.")), ""","attachmentIds":["$fileId"]""").errors(),
        ).containsExactly("attachmentIds[0]:UNKNOWN")
    }

    @Test
    fun `copy recipients comment only when the tenant allows it, and unit readers never`() {
        val alice = person()
        val carol = person()
        val memo = aDraft(alice)
        asUser(alice.account) {
            memoStore.addParticipant(UUID.fromString(memo), carol.id, ParticipantKind.COPY, OffsetDateTime.now(ZoneOffset.UTC))
        }
        assertThat(sheet(memo, carol)["canWrite"].asBoolean()).isFalse()
        assertThat(post(memo, carol, note(text("Noted.")))).hasStatus(HttpStatus.FORBIDDEN)

        transactions.inTenant(tenant.id) { settings.put(MemoNotes.COPY_COMMENTS_SETTING, "true") }
        assertThat(post(memo, carol, note(text("Noted.")))).hasStatus(HttpStatus.CREATED)
    }
}
