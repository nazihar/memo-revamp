package com.memo.meeting

import com.memo.common.crypto.DataKeys
import com.memo.forum.ForumRole
import com.memo.meeting.internal.DeclarationHow
import com.memo.meeting.internal.DeclarationIntent
import com.memo.meeting.internal.RulingOutcome
import com.memo.testsupport.AppRoleJdbc
import com.memo.testsupport.AppRoleJdbc.long
import com.memo.testsupport.MailOutbox
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/**
 * The notifications of declarations (test plan E04, T1; design E04 §2): a declaration to the officers not recused from
 * the item; a pending ruling to the chair, or to the vice-chair when the chair declared; the ruling to the declarant; one
 * recorded or withdrawn by someone else to the declarant. Nobody is told of their own change; subjects take no
 * parameters (MailTemplatesTest); no email or in-app item names the item's title or the nature.
 */
class DeclarationNotificationsIT : DeclarationFixture() {
    @Autowired lateinit var keys: DataKeys

    @Test
    fun `each kind reaches the right people, and none names the item or the nature`() {
        val meeting = meeting()
        val item = addItem(meeting, TITLE)
        val second = addItem(meeting, "Capital plan")
        publish(meeting)

        // A declaration to leave: the secretary and the chair are told; the declarant and the vice-chair are not.
        declare(director, meeting, item)
        assertThat(notified(secretary, MADE) to notified(chair, MADE)).isEqualTo(1L to 1L)
        assertThat(notified(director, MADE) to notified(vice, MADE)).isEqualTo(0L to 0L)

        // Asking the chair: the chair is asked to rule (instead of being told of a declaration); the secretary is told.
        val asked = declare(colleague, meeting, second, "My spouse works for the borrower", DeclarationIntent.ASK_CHAIR)
        assertThat(notified(chair, RULING) to notified(chair, MADE)).isEqualTo(1L to 1L)
        assertThat(notified(secretary, MADE)).isEqualTo(2)
        rule(chair, meeting, asked, RulingOutcome.TAKE_PART)
        assertThat(notified(colleague, RULED)).isEqualTo(1)
        assertThat(notified(chair, RULED)).isZero()

        // The chair asks for a ruling on their own interest: the vice-chair is asked.
        declare(chair, meeting, second, "I sit on the borrower's board", DeclarationIntent.ASK_CHAIR)
        assertThat(notified(vice, RULING)).isEqualTo(1)
        assertThat(notified(chair, RULING)).isEqualTo(1)

        // Recorded for someone: they are told; withdrawn by an officer: they are told again, and the officers.
        val recorded = record(secretary, meeting, item, guest, DeclarationHow.EMAIL)
        assertThat(notified(guest, RECORDED)).isEqualTo(1)
        assertThat(notified(secretary, MADE)).isEqualTo(3)
        // The chair is recused from the second item only: still told about this one.
        assertThat(notified(chair, MADE)).isEqualTo(2)
        withdraw(secretary, meeting, recorded)
        assertThat(notified(guest, RECORDED)).isEqualTo(2)
        assertThat(notified(chair, MADE)).isEqualTo(3)

        // A recused officer is not told about the item: the chair (recused from the second item) hears nothing of it.
        declare(guest, meeting, second, "I advise the borrower")
        assertThat(notified(chair, MADE)).isEqualTo(3)
        assertThat(notified(secretary, MADE)).isEqualTo(4)

        // A member asking again after choosing to leave: the chair is asked once more.
        val leaving = declare(director, meeting, second, "My cousin owns the borrower")
        asUser(director.account) { declarations.changeIntent(meeting.id, leaving.id, leaving.version, DeclarationIntent.ASK_CHAIR) }
        // The chair is recused from the second item, so the vice-chair is asked.
        assertThat(notified(vice, RULING)).isEqualTo(2)

        // The in-app items name the item's number; nothing names its title or the nature, in the app or by email.
        AppRoleJdbc.inTransaction(tenant.id) { c ->
            assertThat(
                c.long(
                    "select count(*) from notification_item where kind like 'MEETING_DECLARATION%' or kind like 'MEETING_RULING%'",
                ),
            ).isGreaterThan(0)
            assertThat(
                c.long(
                    "select count(*) from notification_item where (kind like 'MEETING_DECLARATION%' or kind like 'MEETING_RULING%') " +
                        "and (params::text ilike '%delta%' or params::text ilike '%borrower%' or params::text ilike '%capital plan%')",
                ),
            ).isZero()
            assertThat(
                c.long("select count(*) from notification_item where kind = 'MEETING_DECLARATION_MADE' and params->>'item' <> ''"),
            ).isGreaterThan(0)
        }
        val mails = MailOutbox(keys, json).all(tenant).filter { it.template.startsWith("MEETING_DECLARATION") || it.template == RULING }
        assertThat(mails.map { it.template }.toSet()).contains(MADE, RECORDED, RULED, RULING)
        assertThat(mails.flatMap { it.params.values }).noneMatch {
            it.contains("Delta", ignoreCase = true) || it.contains("borrower", ignoreCase = true) || it.contains("Capital plan")
        }
    }

    @Test
    fun `a second secretary is told too, and an officer recording at the meeting tells the declarant`() {
        val meeting = meeting()
        val item = addItem(meeting, TITLE)
        publish(meeting)
        val other = person()
        seat(other, ForumRole.SECRETARY)

        declare(director, meeting, item, intent = DeclarationIntent.ASK_CHAIR)
        assertThat(notified(chair, RULING)).isEqualTo(1)
        assertThat(notified(other, MADE) to notified(secretary, MADE)).isEqualTo(1L to 1L)
        startMeeting(meeting)
        record(chair, meeting, item, colleague, DeclarationHow.AT_MEETING)
        assertThat(notified(colleague, RECORDED)).isEqualTo(1)
        assertThat(notified(other, MADE) to notified(chair, MADE)).isEqualTo(2L to 0L)
    }

    private companion object {
        const val MADE = "MEETING_DECLARATION_MADE"
        const val RECORDED = "MEETING_DECLARATION_RECORDED"
        const val RULING = "MEETING_RULING_NEEDED"
        const val RULED = "MEETING_DECLARATION_RULED"
    }
}
