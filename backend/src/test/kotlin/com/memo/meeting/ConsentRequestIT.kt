package com.memo.meeting

import com.memo.meeting.internal.ItemChange
import com.memo.meeting.internal.MeetingRow
import com.memo.testsupport.AppRoleJdbc
import com.memo.testsupport.AppRoleJdbc.long
import com.memo.testsupport.AppRoleJdbc.update
import com.memo.testsupport.Audit
import com.memo.testsupport.MailOutbox
import com.memo.testsupport.Person
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.test.web.servlet.assertj.MvcTestResult
import java.sql.SQLException
import java.util.UUID

/**
 * Consent "Ask to discuss" (test plan E03, T4; design E03 §1.5): a member or guest who sees a consent item asks to lift it,
 * every member who sees it reads "Lifted for discussion at …'s request", and the secretaries and the chair are told;
 * the request is withdrawn and asked again; a secretary, someone who does not see the item and an item outside a
 * consent section are refused; requests close when the meeting starts; and a request is append-only.
 */
class ConsentRequestIT : PaperFixture() {
    @Autowired lateinit var keys: com.memo.common.crypto.DataKeys

    @Test
    fun `a member asks to discuss a consent item, everyone who sees it is told who asked, and the request is withdrawn`() {
        val meeting = meeting()
        val consent = addItem(meeting, "Renewal of the audit charter", section = "Consent items")
        val other = addItem(meeting, "Annual risk report")
        publish(meeting)

        // Before anyone asks: the item offers "Ask to discuss" to members and guests, not to the secretary.
        assertThat(discussion(d3, meeting, consent)["canAsk"].asBoolean()).isTrue()
        assertThat(discussion(secretary, meeting, consent)["canAsk"].asBoolean()).isFalse()
        assertThat(agenda(d3, meeting).lines().getValue(other).absent("discussion")).isTrue()

        val key = UUID.randomUUID().toString()
        val asked = ask(d3, meeting, consent, key)
        assertThat(asked).hasStatus(HttpStatus.CREATED)
        Audit.assertAudited(tenant.id, "meeting.discussion.requested", meeting.id.toString(), d3.id.toString())
        // The same key again is a replay; a new one is a second request and refused.
        assertThat(ask(d3, meeting, consent, key)).hasStatus(HttpStatus.CREATED)
        assertThat(reason(ask(d3, meeting, consent))).isEqualTo("ALREADY_ASKED")
        assertThat(openRequests(consent)).isEqualTo(1)

        // Every member who sees the item reads who asked; the requester sees it is theirs.
        listOf(d1, d2, chair, guest, secretary).forEach { person ->
            val seen = discussion(person, meeting, consent)
            assertThat(seen["requestedBy"].list().map { it["id"].asString() }).containsExactly(d3.id.toString())
            assertThat(seen["mine"].asBoolean()).isFalse()
        }
        assertThat(discussion(d3, meeting, consent)["mine"].asBoolean()).isTrue()
        assertThat(discussion(d3, meeting, consent)["canAsk"].asBoolean()).isFalse()

        // The secretaries and the chair are told, the requester is not; no email names the item.
        assertThat(listOf(secretary, chair, d3).map { notified(it, KIND) }).containsExactly(1L, 1L, 0L)
        val mails = MailOutbox(keys, json).all(tenant).filter { it.template == KIND }
        assertThat(mails).hasSize(2)
        assertThat(mails.flatMap { it.params.values }).noneMatch { it.contains("audit charter", ignoreCase = true) }

        // Withdrawn, then asked again: a second request on record.
        assertThat(withdraw(d3, meeting, consent)).hasStatusOk()
        Audit.assertAudited(tenant.id, "meeting.discussion.withdrawn", meeting.id.toString(), d3.id.toString())
        assertThat(discussion(d1, meeting, consent)["requestedBy"].size()).isZero()
        assertThat(reason(withdraw(d3, meeting, consent))).isEqualTo("NO_REQUEST")
        assertThat(ask(d3, meeting, consent)).hasStatus(HttpStatus.CREATED)
        // A guest who sees the item may ask too.
        assertThat(ask(guest, meeting, consent)).hasStatus(HttpStatus.CREATED)
        assertThat(discussion(d1, meeting, consent)["requestedBy"].list().map { it["id"].asString() })
            .containsExactly(d3.id.toString(), guest.id.toString())
        AppRoleJdbc.inTransaction(tenant.id) { c ->
            assertThat(c.long("select count(*) from meeting_consent_request where item_id = ?", consent)).isEqualTo(3)
        }
    }

    @Test
    fun `who may ask, which items, and until the meeting starts`() {
        val meeting = meeting()
        val consent = addItem(meeting, "Minutes of the subsidiaries", section = "Consent items")
        val restricted = addItem(meeting, "Settlement with a counterparty", section = "Consent items")
        change(meeting, restricted, ItemChange("Settlement with a counterparty", restrictedTo = listOf(d1.id)))
        val ordinary = addItem(meeting, "Annual risk report")
        publish(meeting)

        // A secretary moves the item themselves; an outsider and the presenter do not see the meeting or may not ask.
        assertThat(ask(secretary, meeting, consent)).hasStatus(HttpStatus.FORBIDDEN)
        assertThat(ask(outsider, meeting, consent)).hasStatus(HttpStatus.NOT_FOUND)
        assertThat(ask(auditor, meeting, consent)).hasStatus(HttpStatus.NOT_FOUND)
        // An item the person does not see in full is 404; an item outside a consent section is not lifted.
        assertThat(ask(d3, meeting, restricted)).hasStatus(HttpStatus.NOT_FOUND)
        assertThat(ask(d1, meeting, restricted)).hasStatus(HttpStatus.CREATED)
        assertThat(reason(ask(d1, meeting, ordinary))).isEqualTo("WRONG_STATE")
        assertThat(ask(d2, meeting, consent)).hasStatus(HttpStatus.CREATED)

        // Once the meeting starts, nobody asks or withdraws.
        val current = asUser(secretary.account) { reads.get(meeting.id) }
        asUser(secretary.account) { lifecycle.start(meeting.id, current.version) }
        assertThat(reason(ask(d1, meeting, consent))).isEqualTo("WRONG_STATE")
        assertThat(reason(withdraw(d2, meeting, consent))).isEqualTo("WRONG_STATE")
        assertThat(discussion(d2, meeting, consent)["canAsk"].asBoolean()).isFalse()
        assertThat(discussion(d2, meeting, consent)["mine"].asBoolean()).isTrue()

        // Append-only: only a withdrawal changes a request, once; nothing is deleted.
        val statements =
            listOf(
                "update meeting_consent_request set requested_at = requested_at - interval '1 day' where item_id = ?",
                "update meeting_consent_request set withdrawn_at = now() + interval '1 day' where item_id = ? and withdrawn_at is null",
                "delete from meeting_consent_request where item_id = ?",
            )
        statements.forEach { sql ->
            val changed = runCatching { AppRoleJdbc.inTransaction(tenant.id) { c -> c.update(sql, consent) } }
            if (sql.contains("withdrawn_at is null")) {
                assertThat(changed.getOrNull()).describedAs(sql).isEqualTo(1)
            } else {
                assertThat(changed.exceptionOrNull()).describedAs(sql).isInstanceOf(SQLException::class.java)
            }
        }
        val twice =
            runCatching {
                AppRoleJdbc.inTransaction(tenant.id) { c ->
                    c.update("update meeting_consent_request set withdrawn_at = now() where item_id = ?", consent)
                    c.update("update meeting_consent_request set withdrawn_at = now() + interval '1 hour' where item_id = ?", consent)
                }
            }
        assertThat(twice.exceptionOrNull()).isInstanceOf(SQLException::class.java)
    }

    private fun discussion(
        person: Person,
        meeting: MeetingRow,
        item: UUID,
    ) = agenda(person, meeting).lines().getValue(item)["discussion"]

    private fun ask(
        person: Person,
        meeting: MeetingRow,
        item: UUID,
        key: String = UUID.randomUUID().toString(),
    ): MvcTestResult =
        send(
            HttpMethod.POST,
            "/api/v1/meetings/${meeting.id}/agenda/items/$item/discussion-requests",
            person,
            headers = mapOf("Idempotency-Key" to key),
        )

    private fun withdraw(
        person: Person,
        meeting: MeetingRow,
        item: UUID,
    ): MvcTestResult = send(HttpMethod.POST, "/api/v1/meetings/${meeting.id}/agenda/items/$item/discussion-requests/mine/withdraw", person)

    private fun reason(result: MvcTestResult): String {
        assertThat(result).hasStatus(HttpStatus.CONFLICT)
        return result.json()["reason"].asString()
    }

    private fun openRequests(item: UUID): Long =
        AppRoleJdbc.inTransaction(tenant.id) { c ->
            c.long("select count(*) from meeting_consent_request where item_id = ? and withdrawn_at is null", item)
        }

    private companion object {
        const val KIND = "MEETING_DISCUSSION_REQUESTED"
    }
}
