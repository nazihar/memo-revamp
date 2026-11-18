package com.memo.meeting

import com.memo.common.authz.AccessAdministration
import com.memo.common.authz.Permission
import com.memo.common.tenant.TenantDsl
import com.memo.common.web.RefusedException
import com.memo.forum.ForumRole
import com.memo.forum.internal.ForumInput
import com.memo.forum.internal.ForumMembers
import com.memo.forum.internal.ForumTypePreset
import com.memo.forum.internal.ForumTypes
import com.memo.forum.internal.Forums
import com.memo.forum.internal.MemberInput
import com.memo.jooq.tables.references.MEETING
import com.memo.meeting.internal.AgendaChair
import com.memo.meeting.internal.AgendaDraftViews
import com.memo.meeting.internal.AgendaItems
import com.memo.meeting.internal.AgendaNoticeScheduler
import com.memo.meeting.internal.AgendaOrigin
import com.memo.meeting.internal.AgendaPublisher
import com.memo.meeting.internal.AgendaStart
import com.memo.meeting.internal.DeclarationHow
import com.memo.meeting.internal.DeclarationIntent
import com.memo.meeting.internal.DeclarationReviews
import com.memo.meeting.internal.DeclarationRow
import com.memo.meeting.internal.DeclarationRulings
import com.memo.meeting.internal.Declarations
import com.memo.meeting.internal.DeclareInput
import com.memo.meeting.internal.ItemChange
import com.memo.meeting.internal.ItemInput
import com.memo.meeting.internal.ItemKind
import com.memo.meeting.internal.MeetingGuests
import com.memo.meeting.internal.MeetingInput
import com.memo.meeting.internal.MeetingLifecycle
import com.memo.meeting.internal.MeetingPlace
import com.memo.meeting.internal.MeetingReads
import com.memo.meeting.internal.MeetingRejectedException
import com.memo.meeting.internal.MeetingRow
import com.memo.meeting.internal.RulingInput
import com.memo.meeting.internal.RulingOutcome
import com.memo.meeting.internal.TimesInput
import com.memo.testsupport.AppRoleJdbc
import com.memo.testsupport.AppRoleJdbc.long
import com.memo.testsupport.MemoIntegrationTest
import com.memo.testsupport.Person
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

/**
 * A Board Risk Committee for the declaration tests (test plan E04, T1): a secretary, a chair, a vice-chair, two
 * directors, a guest of each meeting, a `forum.read` holder and someone outside; meetings scheduled forty days ahead
 * with their agenda started from the template. The agenda notifications are not scheduled here (AgendaNotificationsIT).
 */
abstract class DeclarationFixture : MemoIntegrationTest() {
    @Autowired lateinit var forumTypes: ForumTypes

    @Autowired lateinit var forums: Forums

    @Autowired lateinit var members: ForumMembers

    @Autowired lateinit var grants: AccessAdministration

    @Autowired lateinit var lifecycle: MeetingLifecycle

    @Autowired lateinit var reads: MeetingReads

    @Autowired lateinit var guests: MeetingGuests

    @Autowired lateinit var starts: AgendaStart

    @Autowired lateinit var items: AgendaItems

    @Autowired lateinit var views: AgendaDraftViews

    @Autowired lateinit var chairing: AgendaChair

    @Autowired lateinit var publisher: AgendaPublisher

    @Autowired lateinit var notices: AgendaNoticeScheduler

    @Autowired lateinit var declarations: Declarations

    @Autowired lateinit var rulings: DeclarationRulings

    @Autowired lateinit var reviews: DeclarationReviews

    @Autowired lateinit var tenantDsl: TenantDsl

    lateinit var secretary: Person
    lateinit var chair: Person
    lateinit var vice: Person
    lateinit var director: Person
    lateinit var colleague: Person
    lateinit var guest: Person
    lateinit var reader: Person
    lateinit var outsider: Person
    lateinit var brc: UUID

    @BeforeEach
    fun committee() {
        notices.jobs = false
        secretary = person()
        chair = person()
        vice = person()
        director = person()
        colleague = person()
        guest = person()
        reader = person()
        outsider = person()
        val type = asUser(admin) { forumTypes.create("BC", "Board committee", ForumTypePreset.BOARD_COMMITTEE, publish = true) }.type.id
        brc = asUser(admin) { forums.create(ForumInput("board-risk", "BRC", "Board Risk Committee", "Risk", type)) }.id
        seat(secretary, ForumRole.SECRETARY)
        seat(chair, ForumRole.CHAIR)
        seat(vice, ForumRole.VICE_CHAIR)
        seat(director, ForumRole.MEMBER)
        seat(colleague, ForumRole.MEMBER)
        asUser(admin) { grants.grantCustom(reader.id, "Committee readers", setOf(Permission.FORUM_READ)) }
    }

    @AfterEach
    fun jobsBack() {
        notices.jobs = true
    }

    fun seat(
        person: Person,
        role: ForumRole,
    ) {
        asUser(admin) { members.add(brc, MemberInput(userId = person.id, role = role)) }
    }

    /** A scheduled meeting of the committee with [guest] as its guest, its agenda started. */
    fun meeting(days: Long = DAYS_AHEAD): MeetingRow {
        val day = LocalDate.now().plusDays(days)
        return asUser(secretary.account) {
            val created =
                lifecycle.create(
                    brc,
                    MeetingInput(MeetingKind.ORDINARY, MeetingPlace(null, MeetingMode.IN_PERSON, "Board room", null, null), null),
                    TimesInput(day.atTime(LocalTime.of(10, 0)), day.atTime(LocalTime.of(13, 0)), "Asia/Dhaka"),
                )
            starts.start(created.id, AgendaOrigin.TEMPLATE, null)
            guests.add(created.id, guest.id, "Chief Risk Officer")
            lifecycle.schedule(created.id, reads.get(created.id).version)
        }
    }

    fun addItem(
        meeting: MeetingRow,
        title: String,
    ): UUID = asUser(secretary.account) { items.add(meeting.id, version(meeting), ItemInput(ItemKind.ITEM, title = title, minutes = 20)) }

    fun change(
        meeting: MeetingRow,
        item: UUID,
        change: ItemChange,
    ) {
        asUser(secretary.account) { items.update(meeting.id, item, version(meeting), change) }
    }

    /** Publishes the next revision (the chair agrees the first). */
    fun publish(
        meeting: MeetingRow,
        note: String? = null,
    ): Int {
        if (asUser(secretary.account) { views.draft(meeting.id) }.currentRevision == 0) {
            asUser(chair.account) { chairing.agree(meeting.id, version(meeting), false, null) }
        }
        return asUser(secretary.account) { publisher.publish(meeting.id, version(meeting), note, true) }
    }

    fun version(meeting: MeetingRow): Long = asUser(secretary.account) { views.draft(meeting.id) }.version

    fun declare(
        person: Person,
        meeting: MeetingRow,
        item: UUID,
        nature: String = NATURE,
        intent: DeclarationIntent = DeclarationIntent.LEAVE,
    ): DeclarationRow = asUser(person.account) { declarations.declare(meeting.id, item, DeclareInput(nature, intent)) }

    fun record(
        officer: Person,
        meeting: MeetingRow,
        item: UUID,
        forPerson: Person,
        how: DeclarationHow? = DeclarationHow.EMAIL,
        reason: String? = null,
    ): DeclarationRow =
        asUser(officer.account) {
            declarations.declare(
                meeting.id,
                item,
                DeclareInput(NATURE, DeclarationIntent.LEAVE, forPerson.id, how, "email of 4 Dec", reason),
            )
        }

    fun rule(
        person: Person,
        meeting: MeetingRow,
        declaration: DeclarationRow,
        outcome: RulingOutcome,
        onBehalf: Boolean = false,
        how: DeclarationHow? = null,
    ): DeclarationRow =
        asUser(person.account) {
            rulings.rule(meeting.id, declaration.id, declaration.version, RulingInput(outcome, "Discussed with the chair", onBehalf, how))
        }

    fun withdraw(
        person: Person,
        meeting: MeetingRow,
        declaration: DeclarationRow,
        reason: String? = "Entered in error",
    ): DeclarationRow = asUser(person.account) { declarations.withdraw(meeting.id, declaration.id, declaration.version, reason) }

    /** Moves [meeting] along: started, then held. */
    fun startMeeting(meeting: MeetingRow) {
        asUser(secretary.account) { lifecycle.start(meeting.id, reads.get(meeting.id).version) }
    }

    fun endMeeting(meeting: MeetingRow) {
        asUser(secretary.account) { lifecycle.end(meeting.id, reads.get(meeting.id).version) }
    }

    /** Sets [meeting]'s status directly (E06 approves minutes; E03 cannot reach MINUTES_APPROVED). */
    fun status(
        meeting: MeetingRow,
        status: MeetingStatus,
    ) {
        asUser(admin) {
            tenantDsl
                .current()
                .update(MEETING)
                .set(MEETING.STATUS, status.name)
                .where(MEETING.ID.eq(meeting.id))
                .execute()
        }
    }

    fun notified(
        person: Person,
        kind: String,
    ): Long =
        AppRoleJdbc.inTransaction(tenant.id) { c ->
            c.long("select count(*) from notification_item where user_id = ? and kind = ?", person.id, kind)
        }

    fun refused(
        reason: String,
        block: () -> Any?,
    ) = assertThatThrownBy { block() }.isInstanceOfSatisfying(RefusedException::class.java) { assertThat(it.reason).isEqualTo(reason) }

    fun problem(block: () -> Any?): String {
        val rejected = runCatching { block() }.exceptionOrNull() as? MeetingRejectedException ?: error("expected a rejection")
        return rejected.problems.joinToString { "${it.field}:${it.code}" }
    }

    companion object {
        const val DAYS_AHEAD = 40L
        const val NATURE = "My brother-in-law is a director of Delta Textiles"
        const val TITLE = "Term loan, Delta Textiles"
    }
}
