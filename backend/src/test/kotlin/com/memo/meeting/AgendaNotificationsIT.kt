package com.memo.meeting

import com.memo.common.context.ActorContext
import com.memo.common.crypto.DataKeys
import com.memo.common.tenant.TenantContext
import com.memo.forum.AgendaSectionKind
import com.memo.forum.ForumRole
import com.memo.forum.internal.ForumInput
import com.memo.forum.internal.ForumMembers
import com.memo.forum.internal.ForumTypePreset
import com.memo.forum.internal.ForumTypes
import com.memo.forum.internal.Forums
import com.memo.forum.internal.MemberInput
import com.memo.meeting.internal.AgendaChair
import com.memo.meeting.internal.AgendaDraftViews
import com.memo.meeting.internal.AgendaItems
import com.memo.meeting.internal.AgendaNoticeScheduler
import com.memo.meeting.internal.AgendaNotices
import com.memo.meeting.internal.AgendaOrigin
import com.memo.meeting.internal.AgendaPublisher
import com.memo.meeting.internal.AgendaSections
import com.memo.meeting.internal.AgendaStart
import com.memo.meeting.internal.ItemChange
import com.memo.meeting.internal.ItemInput
import com.memo.meeting.internal.ItemKind
import com.memo.meeting.internal.MeetingGuests
import com.memo.meeting.internal.MeetingInput
import com.memo.meeting.internal.MeetingLifecycle
import com.memo.meeting.internal.MeetingPlace
import com.memo.meeting.internal.MeetingRow
import com.memo.meeting.internal.SectionInput
import com.memo.meeting.internal.TimesInput
import com.memo.testsupport.AppRoleJdbc
import com.memo.testsupport.AppRoleJdbc.long
import com.memo.testsupport.MailOutbox
import com.memo.testsupport.MemoIntegrationTest
import com.memo.testsupport.Person
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

/**
 * The notifications of a published revision (test plan E03, T3; design E03 §9): after the commit, each person of the
 * audience once per revision (the dedupe key), never the publisher nor someone not cleared for the meeting; a later
 * revision only those who see something it changed; notify off tells nobody; subjects without parameters and no item
 * title in any email or in-app item.
 */
class AgendaNotificationsIT : MemoIntegrationTest() {
    @Autowired lateinit var forumTypes: ForumTypes

    @Autowired lateinit var forums: Forums

    @Autowired lateinit var members: ForumMembers

    @Autowired lateinit var lifecycle: MeetingLifecycle

    @Autowired lateinit var guests: MeetingGuests

    @Autowired lateinit var starts: AgendaStart

    @Autowired lateinit var sections: AgendaSections

    @Autowired lateinit var items: AgendaItems

    @Autowired lateinit var views: AgendaDraftViews

    @Autowired lateinit var chairing: AgendaChair

    @Autowired lateinit var publisher: AgendaPublisher

    @Autowired lateinit var notices: AgendaNotices

    @Autowired lateinit var scheduler: AgendaNoticeScheduler

    @Autowired lateinit var keys: DataKeys

    private lateinit var secretary: Person
    private lateinit var chair: Person
    private lateinit var director: Person
    private lateinit var colleague: Person
    private lateinit var uncleared: Person
    private lateinit var guest: Person
    private lateinit var brc: UUID

    @BeforeEach
    fun committee() {
        scheduler.jobs = false
        secretary = person(clearance = 1)
        chair = person(clearance = 1)
        director = person(clearance = 1)
        colleague = person(clearance = 1)
        uncleared = person()
        guest = person(clearance = 1)
        val type = asUser(admin) { forumTypes.create("BC", "Board committee", ForumTypePreset.BOARD_COMMITTEE, publish = true) }.type.id
        brc = asUser(admin) { forums.create(ForumInput("board-risk", "BRC", "Board Risk Committee", "Risk", type)) }.id
        asUser(admin) { members.add(brc, MemberInput(userId = secretary.id, role = ForumRole.SECRETARY)) }
        asUser(admin) { members.add(brc, MemberInput(userId = chair.id, role = ForumRole.CHAIR)) }
        for (member in listOf(director, colleague, uncleared)) asUser(admin) { members.add(brc, MemberInput(userId = member.id)) }
    }

    @AfterEach
    fun jobsBack() {
        scheduler.jobs = true
    }

    @Test
    fun `each person is told once per revision, and of a later one only when it changes what they see`() {
        val meeting = meeting()
        val secret = addItem(meeting, TITLE)
        asUser(chair.account) { chairing.agree(meeting.id, version(meeting), false, null) }
        publish(meeting)

        // Revision 1: everyone still in the audience and cleared, but the publisher.
        assertThat(send(meeting, 1)).isEqualTo(4)
        val published = "MEETING_AGENDA_PUBLISHED"
        assertThat(listOf(chair, director, colleague, guest).map { notified(it, published) }).containsOnly(1)
        assertThat(notified(secretary, published)).isZero()
        assertThat(notified(uncleared, published)).isZero()
        // A retried job tells nobody twice.
        assertThat(send(meeting, 1)).isZero()
        assertThat(notified(director, published)).isEqualTo(1)
        AppRoleJdbc.inTransaction(tenant.id) { c ->
            assertThat(c.long("select count(*) from meeting_revision_notice where user_id = ?", director.id)).isEqualTo(1)
        }

        // Revision 2: the item is now for the director only, and retitled. Only those who see it are told.
        val draft = asUser(secretary.account) { views.draft(meeting.id) }
        asUser(secretary.account) {
            items.update(meeting.id, secret, draft.version, ItemChange("$TITLE, revised", minutes = 20, restrictedTo = listOf(director.id)))
        }
        publish(meeting, "The acquisition paper is for the director only")
        assertThat(send(meeting, 2)).isEqualTo(2)
        val changed = "MEETING_AGENDA_CHANGED"
        assertThat(notified(director, changed) to notified(chair, changed)).isEqualTo(1L to 1L)
        assertThat(notified(colleague, changed) to notified(guest, changed)).isEqualTo(0L to 0L)

        // Revision 3: an item in a members-only section; the guest does not see it.
        val noting = asUser(secretary.account) { views.draft(meeting.id) }.sections.single { it.title == "For noting" }
        asUser(secretary.account) {
            sections.update(
                meeting.id,
                noting.id,
                version(meeting),
                SectionInput("For noting", AgendaSectionKind.STANDARD, membersOnly = true),
            )
            items.add(meeting.id, version(meeting), ItemInput(ItemKind.ITEM, sectionId = noting.id, title = "Remuneration", minutes = 10))
        }
        publish(meeting, "Remuneration added")
        assertThat(send(meeting, 3)).isEqualTo(3)
        assertThat(notified(colleague, changed) to notified(guest, changed)).isEqualTo(1L to 0L)

        // Notify off: nobody is told.
        addItem(meeting, "Correction")
        publish(meeting, "A typo", notify = false)
        assertThat(send(meeting, 4)).isZero()

        // Neither the emails nor the in-app items name an item; email subjects take no parameters (MailTemplatesTest).
        val mails = MailOutbox(keys, json).all(tenant).filter { it.template.startsWith("MEETING_AGENDA_") }
        assertThat(mails).hasSize(9)
        assertThat(mails.flatMap { it.params.values }).noneMatch { it.contains("acquisition", ignoreCase = true) }
        AppRoleJdbc.inTransaction(tenant.id) { c ->
            assertThat(
                c.long(
                    "select count(*) from notification_item where kind like 'MEETING_AGENDA_%' and params::text ilike '%acquisition%'",
                ),
            ).isZero()
            assertThat(c.long("select count(*) from notification_item where kind like 'MEETING_AGENDA_%'")).isEqualTo(9)
        }
    }

    @Test
    fun `the notifications run as a job after the commit`() {
        scheduler.jobs = true
        val meeting = meeting()
        addItem(meeting, "Strategy")
        asUser(chair.account) { chairing.agree(meeting.id, version(meeting), false, null) }
        publish(meeting)

        val deadline = System.currentTimeMillis() + WAIT_MILLIS
        while (notified(director, "MEETING_AGENDA_PUBLISHED") == 0L && System.currentTimeMillis() < deadline) Thread.sleep(POLL_MILLIS)
        assertThat(notified(director, "MEETING_AGENDA_PUBLISHED")).isEqualTo(1)
        assertThat(notified(secretary, "MEETING_AGENDA_PUBLISHED")).isZero()
    }

    private fun meeting(): MeetingRow {
        val day = LocalDate.now().plusDays(DAYS_AHEAD)
        val level = levels()[1].id
        return asUser(secretary.account) {
            val created =
                lifecycle.create(
                    brc,
                    MeetingInput(MeetingKind.ORDINARY, MeetingPlace(null, MeetingMode.IN_PERSON, "Board room", null, null), level),
                    TimesInput(day.atTime(LocalTime.of(10, 0)), day.atTime(LocalTime.of(13, 0)), "Asia/Dhaka"),
                )
            starts.start(created.id, AgendaOrigin.TEMPLATE, null)
            guests.add(created.id, guest.id, "Chief Risk Officer")
            lifecycle.schedule(created.id, created.version)
        }
    }

    private fun addItem(
        meeting: MeetingRow,
        title: String,
    ): UUID = asUser(secretary.account) { items.add(meeting.id, version(meeting), ItemInput(ItemKind.ITEM, title = title, minutes = 20)) }

    private fun publish(
        meeting: MeetingRow,
        note: String? = null,
        notify: Boolean = true,
    ) = asUser(secretary.account) { publisher.publish(meeting.id, version(meeting), note, notify) }

    /** Runs revision [revision]'s notification job in place, as the job does: the system in a tenant transaction. */
    private fun send(
        meeting: MeetingRow,
        revision: Int,
    ): Int =
        TenantContext.call(tenant.id) {
            ActorContext.asSystem("test") { transactions.inTenant(tenant.id) { notices.send(meeting.id, revision) } }
        }

    private fun version(meeting: MeetingRow): Long = asUser(secretary.account) { views.draft(meeting.id) }.version

    private fun notified(
        person: Person,
        kind: String,
    ): Long =
        AppRoleJdbc.inTransaction(tenant.id) { c ->
            c.long("select count(*) from notification_item where user_id = ? and kind = ?", person.id, kind)
        }

    private companion object {
        const val TITLE = "Confidential acquisition of Beta Bank"
        const val DAYS_AHEAD = 40L
        const val WAIT_MILLIS = 30_000L
        const val POLL_MILLIS = 200L
    }
}
