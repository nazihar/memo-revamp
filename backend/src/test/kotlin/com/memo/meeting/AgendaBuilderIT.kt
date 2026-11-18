package com.memo.meeting

import com.memo.common.authz.AccessAdministration
import com.memo.common.authz.Clearances
import com.memo.common.authz.ForbiddenException
import com.memo.common.concurrency.ConcurrentModificationException
import com.memo.common.context.Actor
import com.memo.common.context.ActorContext
import com.memo.common.context.ActorScope
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantTransactions
import com.memo.common.web.RefusedException
import com.memo.forum.AgendaSectionKind
import com.memo.forum.ForumRole
import com.memo.forum.StandingKind
import com.memo.forum.SubmissionPurpose
import com.memo.forum.internal.ForumInput
import com.memo.forum.internal.ForumMembers
import com.memo.forum.internal.ForumTypePreset
import com.memo.forum.internal.ForumTypes
import com.memo.forum.internal.Forums
import com.memo.forum.internal.MemberInput
import com.memo.identity.UserAccount
import com.memo.identity.UserAccounts
import com.memo.meeting.internal.AgendaChanges
import com.memo.meeting.internal.AgendaDraftResponse
import com.memo.meeting.internal.AgendaDraftViews
import com.memo.meeting.internal.AgendaItemResponse
import com.memo.meeting.internal.AgendaItems
import com.memo.meeting.internal.AgendaMoves
import com.memo.meeting.internal.AgendaOrigin
import com.memo.meeting.internal.AgendaSections
import com.memo.meeting.internal.AgendaStart
import com.memo.meeting.internal.ItemChange
import com.memo.meeting.internal.ItemInput
import com.memo.meeting.internal.ItemKind
import com.memo.meeting.internal.Lateness
import com.memo.meeting.internal.MeetingGuests
import com.memo.meeting.internal.MeetingInput
import com.memo.meeting.internal.MeetingLifecycle
import com.memo.meeting.internal.MeetingNotFoundException
import com.memo.meeting.internal.MeetingPlace
import com.memo.meeting.internal.MeetingReads
import com.memo.meeting.internal.MeetingRejectedException
import com.memo.meeting.internal.MeetingRelation
import com.memo.meeting.internal.MeetingRow
import com.memo.meeting.internal.MoveInput
import com.memo.meeting.internal.Presenter
import com.memo.meeting.internal.SectionInput
import com.memo.meeting.internal.TimesInput
import com.memo.tenancy.TenantRegistry
import com.memo.tenancy.TenantSettings
import com.memo.testsupport.AppRoleJdbc
import com.memo.testsupport.AppRoleJdbc.long
import com.memo.testsupport.Audit
import com.memo.testsupport.IntegrationTest
import com.memo.testsupport.TestTenant
import com.memo.testsupport.aPassword
import com.memo.testsupport.aTestTenant
import com.memo.testsupport.anActiveUser
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assumptions.assumeThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * The agenda draft (test plan E03, T2; ADR-0060): started from the committee type's standard agenda or from an earlier
 * meeting; sections, items, sub-items and breaks built and moved by command under the agenda's version; people,
 * restrictions, hidden titles and levels; frozen once the meeting is held or cancelled. Every change is audited.
 */
class AgendaBuilderIT : IntegrationTest() {
    @Autowired lateinit var registry: TenantRegistry

    @Autowired lateinit var settings: TenantSettings

    @Autowired lateinit var transactions: TenantTransactions

    @Autowired lateinit var accounts: UserAccounts

    @Autowired lateinit var types: ForumTypes

    @Autowired lateinit var forums: Forums

    @Autowired lateinit var members: ForumMembers

    @Autowired lateinit var lifecycle: MeetingLifecycle

    @Autowired lateinit var guests: MeetingGuests

    @Autowired lateinit var reads: MeetingReads

    @Autowired lateinit var starts: AgendaStart

    @Autowired lateinit var sections: AgendaSections

    @Autowired lateinit var items: AgendaItems

    @Autowired lateinit var moves: AgendaMoves

    @Autowired lateinit var views: AgendaDraftViews

    @Autowired lateinit var access: AccessAdministration

    @Autowired lateinit var clearances: Clearances

    private lateinit var tenant: TestTenant
    private lateinit var admin: UserAccount
    private lateinit var secretary: UserAccount
    private lateinit var chair: UserAccount
    private lateinit var director: UserAccount
    private lateinit var second: UserAccount
    private lateinit var guest: UserAccount
    private lateinit var user: UserAccount
    private lateinit var brc: UUID
    private val today: LocalDate = LocalDate.now(ZoneOffset.UTC)

    @BeforeEach
    fun setUp() {
        tenant = registry.aTestTenant(transactions, settings, "agendas", mfaRequired = false)
        admin = accounts.anActiveUser(transactions, tenant, aPassword(), tenantAdmin = true)
        secretary = accounts.anActiveUser(transactions, tenant, aPassword())
        chair = accounts.anActiveUser(transactions, tenant, aPassword())
        director = accounts.anActiveUser(transactions, tenant, aPassword())
        second = accounts.anActiveUser(transactions, tenant, aPassword())
        guest = accounts.anActiveUser(transactions, tenant, aPassword())
        user = accounts.anActiveUser(transactions, tenant, aPassword())
        val type = asUser(admin) { types.create("BC", "Board committee", ForumTypePreset.BOARD_COMMITTEE, publish = true) }.type.id
        brc = asUser(admin) { forums.create(ForumInput("board-risk", "BRC", "Board Risk Committee", "Risk", type)) }.id
        add(secretary, ForumRole.SECRETARY)
        add(chair, ForumRole.CHAIR)
        add(director, ForumRole.MEMBER)
        add(second, ForumRole.MEMBER)
    }

    @Test
    fun `an agenda starts once from the committee type's standard agenda, and only officers read the draft`() {
        val meeting = asUser(secretary) { create(today.plusDays(30)) }
        val before = draft(meeting.id)
        assertThat(before.started).isFalse()
        assertThat(before.template?.sections?.map { it.title })
            .containsExactly("Opening", "For decision", "Consent items", "For noting", "Any other business")

        val started = start(meeting.id)
        assertThat(started.started).isTrue()
        assertThat(started.origin).isEqualTo(AgendaOrigin.TEMPLATE)
        assertThat(
            started.sections.map {
                it.title
            },
        ).containsExactly("Opening", "For decision", "Consent items", "For noting", "Any other business")
        assertThat(started.sections[2].kind).isEqualTo(AgendaSectionKind.CONSENT)
        assertThat(started.sections[1].receivesSubmissions).isTrue()
        val opening = started.sections[0].items
        assertThat(opening.map { it.number }).containsExactly("1", "2", "3", "4")
        assertThat(opening.map { it.standingKind }).containsExactly(
            StandingKind.APOLOGIES,
            StandingKind.DECLARATIONS,
            StandingKind.PREVIOUS_MINUTES,
            StandingKind.MATTERS_ARISING,
        )
        assertThat(opening[1].startsAt).isEqualTo(meeting.startsAt.plusSeconds(MINUTES_5))
        assertThat(started.sections[0].minutes).isEqualTo(25)
        assertThat(
            started.sections[4]
                .items
                .single()
                .number,
        ).isEqualTo("5")
        assertThat(started.totalMinutes).isEqualTo(30)
        assertThat(started.overrun).isFalse()
        assertThat(started.paperDeadline).isNotNull()
        Audit.assertAudited(tenant.id, "meeting.agenda.started", meeting.id.toString(), secretary.id.value.toString())
        refused("AGENDA_STARTED") { asUser(secretary) { starts.start(meeting.id, AgendaOrigin.TEMPLATE, null) } }

        // The chair reads it and changes nothing; members and others do not see a draft meeting at all.
        val read = asUser(chair) { views.draft(meeting.id) }
        assertThat(read.canEdit).isFalse()
        assertThatThrownBy { asUser(chair) { sections.add(meeting.id, read.version, SectionInput("Strategy"), null) } }
            .isInstanceOf(ForbiddenException::class.java)
        for (outsider in listOf(director, user)) {
            assertThatThrownBy { asUser(outsider) { views.draft(meeting.id) } }.isInstanceOf(MeetingNotFoundException::class.java)
        }
        val scheduled = asUser(secretary) { lifecycle.schedule(meeting.id, meeting.version) }
        assertThatThrownBy { asUser(director) { views.draft(scheduled.id) } }.isInstanceOf(ForbiddenException::class.java)
        assertThatThrownBy { asUser(director) { starts.start(scheduled.id, AgendaOrigin.TEMPLATE, null) } }
            .isInstanceOf(ForbiddenException::class.java)
    }

    /** The structure the builder tests share, as built through the services. */
    private class Built(
        val meeting: MeetingRow,
        val decision: UUID,
        val strategy: UUID,
        val credit: AgendaItemResponse,
        val sub: AgendaItemResponse,
        val liquidity: AgendaItemResponse,
        val draft: AgendaDraftResponse,
    )

    @Test
    fun `sections, items, sub-items and breaks are built under the agenda's version, one level deep`() {
        val built = build()
        val meeting = built.meeting
        val credit = built.credit
        val sub = built.sub
        val decision = built.decision
        refused("ONE_LEVEL") { change { items.add(meeting.id, it, ItemInput(ItemKind.ITEM, parentId = sub.id, title = "Too deep")) } }
        refused(
            "ONE_LEVEL",
        ) { change { moves.move(meeting.id, it, MoveInput(itemId = built.liquidity.id, toParentId = sub.id, toPosition = 0)) } }
        refused(
            "ONE_LEVEL",
        ) { change { moves.move(meeting.id, it, MoveInput(itemId = credit.id, toParentId = built.liquidity.id, toPosition = 0)) } }
        refused("ONE_LEVEL") { change { items.add(meeting.id, it, ItemInput(ItemKind.BREAK, parentId = credit.id)) } }
        assertThat(
            problem {
                change { items.add(meeting.id, it, ItemInput(ItemKind.STANDING, decision, title = "AOB", standingKind = StandingKind.AOB)) }
            },
        ).isEqualTo("standingKind:duplicate")
        assertThat(
            problem { change { items.add(meeting.id, it, ItemInput(ItemKind.ITEM, parentId = credit.id, title = "Timed", minutes = 5)) } },
        ).isEqualTo("minutes:ONE_LEVEL")

        // A stale version is refused (412) and changes nothing.
        assertThatThrownBy {
            asUser(secretary) { moves.move(meeting.id, built.draft.version - 1, MoveInput(itemId = credit.id, toPosition = 2)) }
        }.isInstanceOf(ConcurrentModificationException::class.java)
        assertThat(draft(meeting.id).version).isEqualTo(built.draft.version)
    }

    @Test
    fun `items and sections are moved by command, and removing an item lifts its sub-items into its place`() {
        val built = build()
        val meeting = built.meeting
        val credit = built.credit
        val sub = built.sub
        val decision = built.decision
        val strategy = built.strategy
        var draft: AgendaDraftResponse
        // Moves: an item to another section (with its sub-items), a sub-item to the top, a section to another place.
        draft = change { moves.move(meeting.id, it, MoveInput(itemId = credit.id, toSectionId = strategy, toPosition = 0)) }
        val moved =
            draft.sections
                .single { it.id == strategy }
                .items
                .single()
        assertThat(moved.title to moved.subItems.size).isEqualTo("Credit risk appetite" to 2)
        Audit.assertAudited(tenant.id, "meeting.agenda.item_moved", meeting.id.toString(), secretary.id.value.toString())
        draft = change { moves.move(meeting.id, it, MoveInput(itemId = sub.id, toSectionId = decision, toPosition = 1)) }
        assertThat(
            draft.sections
                .single { it.id == decision }
                .items
                .map { it.title },
        ).containsExactly("Break", "Corporate limits", "Liquidity")
        draft = change { moves.move(meeting.id, it, MoveInput(itemId = sub.id, toParentId = credit.id, toPosition = 5)) }
        assertThat(draft.item("Credit risk appetite").subItems.map { it.title }).containsExactly("Retail limits", "Corporate limits")
        draft = change { moves.move(meeting.id, it, MoveInput(sectionId = strategy, toPosition = 99)) }
        assertThat(draft.sections.last().title).isEqualTo("Strategy day")
        Audit.assertAudited(tenant.id, "meeting.agenda.section_moved", meeting.id.toString(), secretary.id.value.toString())

        // Removing an item lifts its sub-items into its place; a section goes only when empty.
        refused("SECTION_NOT_EMPTY") { change { sections.remove(meeting.id, strategy, it) } }
        draft = change { items.remove(meeting.id, credit.id, it) }
        assertThat(
            draft.sections
                .last()
                .items
                .map { it.title to it.subItems.size },
        ).containsExactly(
            "Retail limits" to 0,
            "Corporate limits" to 0,
        )
        Audit.assertAudited(tenant.id, "meeting.agenda.item_removed", meeting.id.toString(), secretary.id.value.toString())
        draft.sections
            .last()
            .items
            .forEach { row -> draft = change { items.remove(meeting.id, row.id, it) } }
        draft = change { sections.remove(meeting.id, strategy, it) }
        assertThat(draft.sections.map { it.title }).doesNotContain("Strategy day")
        Audit.assertAudited(tenant.id, "meeting.agenda.section_removed", meeting.id.toString(), secretary.id.value.toString())
        // Nothing is deleted: removed rows stay with their time and reason.
        AppRoleJdbc.inTransaction(tenant.id) { c ->
            assertThat(
                c.long("select count(*) from meeting_item where meeting_id = ? and removed_reason = 'REMOVED'", meeting.id),
            ).isEqualTo(3)
            assertThat(
                c.long("select count(*) from meeting_section where meeting_id = ? and removed_at is not null", meeting.id),
            ).isEqualTo(1)
        }
    }

    @Test
    fun `presenters, attendees, restrictions to the audience, hidden titles and levels`() {
        val meeting = asUser(secretary) { create(today.plusDays(30)) }
        asUser(secretary) { guests.add(meeting.id, guest.id.value, "Chief Financial Officer") }
        var draft = start(meeting.id)
        draft = change { items.add(meeting.id, it, ItemInput(ItemKind.ITEM, title = "Dismissal of a senior officer", minutes = 20)) }
        val item = draft.item("Dismissal of a senior officer")
        val presenter = accounts.anActiveUser(transactions, tenant, aPassword())

        fun edit(change: ItemChange) = change { items.update(meeting.id, item.id, it, change) }

        val full =
            ItemChange(
                title = "Dismissal of a senior officer",
                description = "Report of the investigation",
                purpose = SubmissionPurpose.DECISION,
                resolutionSought = "To approve the dismissal",
                minutes = 25,
                presenters = listOf(Presenter(userId = presenter.id.value), Presenter(name = "External counsel")),
                attendees = listOf(user.id.value),
                restrictedTo = listOf(director.id.value, guest.id.value),
                titleHidden = true,
            )
        draft = edit(full)
        val edited = draft.item("Dismissal of a senior officer")
        assertThat(edited.presenters.map { it.name }).contains("External counsel")
        assertThat(edited.attendees.map { it.id }).containsExactlyInAnyOrder(user.id.value, presenter.id.value)
        assertThat(edited.restrictedTo?.map { it.id }).containsExactlyInAnyOrder(director.id.value, guest.id.value)
        assertThat(edited.titleHidden).isTrue()
        assertThat(edited.minutes).isEqualTo(25)
        Audit.assertAudited(tenant.id, "meeting.agenda.item_updated", meeting.id.toString(), secretary.id.value.toString())

        // Item attendees join the audience for the meeting once it is scheduled.
        val version = asUser(secretary) { reads.get(meeting.id) }.version
        val scheduled = asUser(secretary) { lifecycle.schedule(meeting.id, version) }
        assertThat(asUser(user) { reads.get(scheduled.id) }.relation).isEqualTo(MeetingRelation.ATTENDEE)

        // Restrictions name people the meeting is for, never nobody; presenters and attendees within the caps.
        val outsider = accounts.anActiveUser(transactions, tenant, aPassword())
        assertThat(problem { edit(full.copy(restrictedTo = listOf(outsider.id.value))) }).isEqualTo("restrictedTo:RESTRICTION_AUDIENCE")
        assertThat(problem { edit(full.copy(restrictedTo = emptyList())) }).isEqualTo("restrictedTo:REQUIRED")
        assertThat(problem { edit(full.copy(presenters = List(6) { Presenter(name = "P$it") })) }).isEqualTo("presenters:TOO_MANY")
        assertThat(problem { edit(full.copy(presenters = listOf(Presenter(name = " ")))) }).isEqualTo("presenters:FORMAT")
        assertThat(problem { edit(full.copy(attendees = listOf(UUID.randomUUID()))) }).isEqualTo("attendees:unknown")
        // Removing the attendee takes them out of the audience again.
        edit(full.copy(attendees = emptyList(), presenters = emptyList()))
        assertThatThrownBy { asUser(user) { reads.get(meeting.id) } }.isInstanceOf(MeetingNotFoundException::class.java)

        // Levels: never below the meeting's, never above the actor's own clearance; the people named must be cleared.
        val levels = asUser(admin) { clearances.levels() }
        assumeThat(levels.size).isGreaterThan(1)
        val top = levels.last()
        assertThat(problem { edit(full.copy(classificationId = top.id)) }).contains("classificationId:CLASSIFICATION")
        asUser(admin) { access.ensureClearance(secretary.id.value, top.rank) }
        asUser(admin) { access.ensureClearance(director.id.value, top.rank) }
        val alone = full.copy(classificationId = top.id, attendees = emptyList(), presenters = emptyList())
        assertThat(problem { edit(alone) }).isEqualTo("restrictedTo:CLASSIFICATION")
        draft = edit(alone.copy(restrictedTo = listOf(director.id.value)))
        assertThat(draft.item("Dismissal of a senior officer").classificationId).isEqualTo(top.id)
    }

    @Test
    fun `a copy of an earlier meeting keeps the structure and narrows restrictions to the new audience`() {
        val earlier = asUser(secretary) { create(today.plusDays(10)) }
        asUser(secretary) { guests.add(earlier.id, guest.id.value, null) }
        var draft = start(earlier.id)
        draft = change { items.add(earlier.id, it, ItemInput(ItemKind.ITEM, title = "Treasury update", minutes = 15)) }
        draft = change { items.add(earlier.id, it, ItemInput(ItemKind.ITEM, title = "Guest briefing", minutes = 10)) }
        val treasury = draft.item("Treasury update")
        val briefing = draft.item("Guest briefing")
        draft =
            change {
                items.update(
                    earlier.id,
                    treasury.id,
                    it,
                    ItemChange(
                        "Treasury update",
                        minutes = 15,
                        attendees = listOf(user.id.value),
                        restrictedTo = listOf(director.id.value, guest.id.value),
                    ),
                )
            }
        change {
            items.update(
                earlier.id,
                briefing.id,
                it,
                ItemChange("Guest briefing", minutes = 10, restrictedTo = listOf(guest.id.value)),
            )
        }

        val later = asUser(secretary) { create(today.plusDays(40)) }
        val copied =
            asUser(secretary) {
                val report = starts.start(later.id, AgendaOrigin.MEETING, earlier.id)
                views.draft(later.id, report)
            }
        assertThat(copied.origin).isEqualTo(AgendaOrigin.MEETING)
        assertThat(copied.sourceMeetingId).isEqualTo(earlier.id)
        assertThat(copied.sections.map { it.title }).containsExactlyElementsOf(draft.sections.map { it.title })
        val kept = copied.item("Treasury update")
        assertThat(kept.minutes).isEqualTo(15)
        assertThat(kept.restrictedTo?.map { it.id }).containsExactly(director.id.value)
        assertThat(kept.attendees.map { it.id }).containsExactly(user.id.value)
        assertThat(kept.late).isEqualTo(Lateness.NONE)
        // The guest is not on the later meeting: dropped and listed; an item restricted to them alone is left out.
        assertThat(copied.copied?.dropped?.map { it.id }).containsExactly(guest.id.value)
        assertThat(copied.copied?.skipped).containsExactly("Guest briefing")
        assertThat(copied.sections.flatMap { it.items }.map { it.title }).doesNotContain("Guest briefing")
        Audit.assertAudited(tenant.id, "meeting.agenda.started", later.id.toString(), secretary.id.value.toString())

        val third = asUser(secretary) { create(today.plusDays(50)) }
        assertThat(
            problem { asUser(secretary) { starts.start(third.id, AgendaOrigin.MEETING, third.id) } },
        ).isEqualTo("meetingId:SAME_MEETING")
        val empty = asUser(secretary) { create(today.plusDays(51)) }
        assertThat(
            problem { asUser(secretary) { starts.start(third.id, AgendaOrigin.MEETING, empty.id) } },
        ).isEqualTo("meetingId:NO_AGENDA")
        assertThat(
            problem { asUser(secretary) { starts.start(third.id, AgendaOrigin.MEETING, UUID.randomUUID()) } },
        ).isEqualTo("meetingId:unknown")
    }

    @Test
    fun `while the meeting is in progress only tabled additions are taken, and once it is held nothing changes`() {
        val meeting = asUser(secretary) { create(today.plusDays(1)) }
        start(meeting.id)
        val scheduled = asUser(secretary) { lifecycle.schedule(meeting.id, meeting.version) }
        val started = asUser(secretary) { lifecycle.start(scheduled.id, scheduled.version) }
        var draft = draft(meeting.id)
        assertThat(draft.changes).isEqualTo(AgendaChanges.TABLED_ONLY)
        draft = change { items.add(meeting.id, it, ItemInput(ItemKind.ITEM, title = "Urgent liquidity matter")) }
        assertThat(draft.item("Urgent liquidity matter").late).isEqualTo(Lateness.TABLED)
        val tabled = draft.item("Urgent liquidity matter")
        refused("FROZEN") { change { items.update(meeting.id, tabled.id, it, ItemChange("Renamed")) } }
        refused("FROZEN") { change { moves.move(meeting.id, it, MoveInput(itemId = tabled.id, toPosition = 0)) } }
        refused("FROZEN") { change { sections.add(meeting.id, it, SectionInput("More"), null) } }

        asUser(secretary) { lifecycle.end(started.id, started.version) }
        assertThat(draft(meeting.id).changes).isEqualTo(AgendaChanges.NONE)
        refused("FROZEN") { change { items.add(meeting.id, it, ItemInput(ItemKind.ITEM, title = "After the meeting")) } }
        refused("FROZEN") { change { items.remove(meeting.id, tabled.id, it) } }
        // A held or cancelled meeting's agenda cannot be started either.
        val cancelled = asUser(secretary) { create(today.plusDays(5)).let { lifecycle.cancel(it.id, it.version, "Not needed") } }
        refused("FROZEN") { asUser(secretary) { starts.start(cancelled.id, AgendaOrigin.TEMPLATE, null) } }
    }

    /** A meeting with "Strategy" added (consent, members only, receiving), and "Credit risk appetite" with two sub-items. */
    private fun build(): Built {
        val meeting = asUser(secretary) { create(today.plusDays(30)) }
        var draft = start(meeting.id)
        val decision = draft.sections[1].id

        draft = change { sections.add(meeting.id, it, SectionInput("Strategy"), 1) }
        assertThat(draft.sections.map { it.title }.take(3)).containsExactly("Opening", "Strategy", "For decision")
        Audit.assertAudited(tenant.id, "meeting.agenda.section_added", meeting.id.toString(), secretary.id.value.toString())
        val strategy = draft.sections[1].id
        draft = change { sections.update(meeting.id, strategy, it, SectionInput("Strategy day", AgendaSectionKind.CONSENT, true, true)) }
        assertThat(draft.sections[1].membersOnly).isTrue()
        // New items land in one section only: choosing Strategy took the mark from "For decision".
        assertThat(draft.sections.filter { it.receivesSubmissions }.map { it.title }).containsExactly("Strategy day")
        Audit.assertAudited(tenant.id, "meeting.agenda.section_updated", meeting.id.toString(), secretary.id.value.toString())

        draft = change { items.add(meeting.id, it, ItemInput(ItemKind.ITEM, decision, title = "Credit risk appetite", minutes = 40)) }
        val credit = draft.item("Credit risk appetite")
        draft = change { items.add(meeting.id, it, ItemInput(ItemKind.ITEM, parentId = credit.id, title = "Retail limits")) }
        draft =
            change { items.add(meeting.id, it, ItemInput(ItemKind.ITEM, parentId = credit.id, title = "Corporate limits", position = 0)) }
        draft = change { items.add(meeting.id, it, ItemInput(ItemKind.BREAK, decision, minutes = 15)) }
        draft = change { items.add(meeting.id, it, ItemInput(ItemKind.ITEM, decision, title = "Liquidity", minutes = 20)) }
        Audit.assertAudited(tenant.id, "meeting.agenda.item_added", meeting.id.toString(), secretary.id.value.toString())
        val business = draft.sections.single { it.id == decision }.items
        assertThat(business.map { it.title }).containsExactly("Credit risk appetite", "Break", "Liquidity")
        assertThat(business.map { it.number }).containsExactly("5", null, "6")
        assertThat(
            business[0].subItems.map { it.title to it.number },
        ).containsExactly("Corporate limits" to "5.1", "Retail limits" to "5.2")
        assertThat(business[2].startsAt).isEqualTo(meeting.startsAt.plusSeconds(MINUTES_5 * 16))
        assertThat(business[0].subItems[0].startsAt).isNull()
        return Built(meeting, decision, strategy, credit, business[0].subItems[0], business[2], draft)
    }

    private fun AgendaDraftResponse.item(title: String): AgendaItemResponse =
        sections.flatMap { s -> s.items.flatMap { listOf(it) + it.subItems } }.single { it.title == title }

    private fun start(meetingId: UUID): AgendaDraftResponse =
        asUser(secretary) {
            starts.start(meetingId, AgendaOrigin.TEMPLATE, null)
            views.draft(meetingId)
        }

    private fun draft(meetingId: UUID): AgendaDraftResponse = asUser(secretary) { views.draft(meetingId) }

    /** Runs [block] as the secretary with the agenda's current version, and answers the draft after it. */
    private var current: UUID? = null

    private fun change(block: (Long) -> Unit): AgendaDraftResponse {
        val meetingId = checkNotNull(current)
        return asUser(secretary) {
            block(views.draft(meetingId).version)
            views.draft(meetingId)
        }
    }

    private fun add(
        account: UserAccount,
        role: ForumRole,
    ) = asUser(admin) { members.add(brc, MemberInput(userId = account.id.value, role = role)) }

    private fun create(day: LocalDate): MeetingRow {
        val at = day.atTime(LocalTime.of(10, 0))
        return lifecycle
            .create(
                brc,
                MeetingInput(MeetingKind.ORDINARY, MeetingPlace(null, MeetingMode.ONLINE, null, "https://meet.example/abc", null), null),
                TimesInput(at, at.plusHours(3), "Asia/Dhaka"),
            ).also { current = it.id }
    }

    private fun refused(
        reason: String,
        block: () -> Any?,
    ) = assertThatThrownBy { block() }.isInstanceOfSatisfying(RefusedException::class.java) { assertThat(it.reason).isEqualTo(reason) }

    private fun problem(block: () -> Any?): String {
        val rejected = runCatching { block() }.exceptionOrNull() as? MeetingRejectedException ?: error("expected a rejection")
        return rejected.problems.joinToString { "${it.field}:${it.code}" }
    }

    private fun <T> asUser(
        account: UserAccount,
        block: () -> T,
    ): T =
        ActorContext.call(ActorScope(Actor.User(account.id.value), null)) {
            TenantContext.call(tenant.id) { transactions.inTenant(tenant.id, block) }
        }

    private companion object {
        const val MINUTES_5 = 300L
    }
}
