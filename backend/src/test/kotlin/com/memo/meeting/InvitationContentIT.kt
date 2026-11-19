package com.memo.meeting

import com.memo.meeting.internal.AgendaDraftViews
import com.memo.meeting.internal.AgendaItems
import com.memo.meeting.internal.AgendaOrigin
import com.memo.meeting.internal.AgendaStart
import com.memo.meeting.internal.ItemInput
import com.memo.meeting.internal.ItemKind
import com.memo.testsupport.aPassword
import com.memo.testsupport.anActiveUser
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/**
 * What invitations carry (test plan E05 T2 `InvitationContentIT`, threat I1 and I2): for every kind of message (the
 * invitation, an update, an uninvitation, the cancellation), the calendar file, the email's parameters and the in-app
 * item hold no agenda item title, no cancellation reason and no other canary, and each file names only its recipient.
 */
class InvitationContentIT : InvitationTestSupport() {
    @Autowired lateinit var starts: AgendaStart

    @Autowired lateinit var items: AgendaItems

    @Autowired lateinit var views: AgendaDraftViews

    override fun label() = "invitetext"

    @Test
    fun `no message carries the agenda, an item, the reason or another person's address`() {
        val meeting = scheduled()
        val guest = accounts.anActiveUser(transactions, tenant, aPassword())
        asUser(secretary) {
            starts.start(meeting.id, AgendaOrigin.TEMPLATE, null)
            items.add(meeting.id, views.draft(meeting.id).version, ItemInput(ItemKind.ITEM, title = "$CANARY item title"))
            guests.add(meeting.id, guest.id.value, "$CANARY guest note")
        }
        reconcile(meeting.id)
        var current = meeting(meeting.id)
        current = asUser(secretary) { lifecycle.edit(current.id, current.version, place(location = "Annex", title = "Quarterly")) }
        asUser(secretary) { guests.remove(meeting.id, guest.id.value) }
        reconcile(meeting.id)
        asUser(secretary) { lifecycle.cancel(current.id, current.version, "$CANARY reason") }
        reconcile(meeting.id)

        val sent = messages(meeting.id)
        assertThat(sent.map { it.kind.name }.toSet()).containsExactlyInAnyOrder("INVITATION", "UPDATE", "UNINVITE", "CANCEL")
        val everyone = listOf(secretary, chair, director, director2, guest)
        sent.forEach { message ->
            val outbox = checkNotNull(message.outboxId)
            val (text, parsed) = file(outbox, kindOf(message))
            val recipient = everyone.single { it.id.value == message.userId }
            assertThat(parsed.attendees).containsExactly(recipient.email)
            (everyone - recipient).forEach { assertThat(text.replace("\r\n ", "")).doesNotContain(it.email) }
            assertThat(text.replace("\r\n ", "")).doesNotContain(CANARY)
            assertThat(params(outbox).values.joinToString()).doesNotContain(CANARY)
        }
        val updated = sent.first { it.kind.name == "UPDATE" }
        assertThat(params(updated.outboxId!!)["changes"]).isEqualTo("The place changed. The title changed.")
        assertThat(file(updated.outboxId, kindOf(updated)).second.summary).isEqualTo("Quarterly")
        (everyone).forEach { account ->
            listOf("MEETING_INVITATION", "MEETING_UPDATED", "MEETING_UNINVITED", "MEETING_CANCELLED").forEach { kind ->
                items(account, kind).forEach { assertThat(it).doesNotContain(CANARY) }
            }
        }
    }

    private companion object {
        const val CANARY = "CANARY-E05"
    }
}
