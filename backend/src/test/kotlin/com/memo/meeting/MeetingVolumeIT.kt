package com.memo.meeting

import com.memo.authz.internal.ClassificationAdmin
import com.memo.common.authz.Clearances
import com.memo.common.context.Actor
import com.memo.common.context.ActorContext
import com.memo.common.context.ActorScope
import com.memo.common.tenant.TenantContext
import com.memo.common.tenant.TenantDsl
import com.memo.common.tenant.TenantTransactions
import com.memo.forum.ForumRole
import com.memo.forum.internal.ForumInput
import com.memo.forum.internal.ForumMembers
import com.memo.forum.internal.ForumSubmissions
import com.memo.forum.internal.ForumTypePreset
import com.memo.forum.internal.ForumTypes
import com.memo.forum.internal.Forums
import com.memo.forum.internal.MemberInput
import com.memo.forum.internal.SubmissionResponses
import com.memo.identity.UserAccounts
import com.memo.meeting.internal.AgendaDraftViews
import com.memo.meeting.internal.AgendaItems
import com.memo.meeting.internal.AgendaPublisher
import com.memo.meeting.internal.DeclarationRegister
import com.memo.meeting.internal.DeclarationRegisters
import com.memo.meeting.internal.DeclarationReviews
import com.memo.meeting.internal.DeclarationWorkbook
import com.memo.meeting.internal.ItemChange
import com.memo.meeting.internal.MeetingReads
import com.memo.meeting.internal.MemberAgendas
import com.memo.meeting.internal.PaperOpens
import com.memo.meeting.internal.RegisterFilter
import com.memo.tenancy.TenantRegistry
import com.memo.tenancy.TenantSettings
import com.memo.testsupport.IntegrationTest
import com.memo.testsupport.SqlRecorder
import com.memo.testsupport.TestTenant
import com.memo.testsupport.aPassword
import com.memo.testsupport.aTestTenant
import com.memo.testsupport.anActiveUser
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.beans.factory.annotation.Autowired
import java.io.OutputStream
import java.time.LocalDate
import java.util.UUID

/**
 * Meetings at the design's volume (test plan E03, T5; design E03 §8): [MeetingVolume] at `MEETING_VOLUME` meetings (the
 * design's 3,600 with 180,000 items and 600,000 papers; the weekly `load` stage sets it, and the test is skipped without
 * it: the volume takes minutes to write), measured through the services each endpoint calls. Budgets have 2x headroom
 * for shared CI runners; the p95 values are printed for the epic log. Every statement a read path runs is recorded
 * ([SqlRecorder]) and `EXPLAIN ANALYZE`d on the volume: none may scan one of the large tables sequentially. The writes
 * (a builder change, a publication) run last, since each publication adds a revision to the focal meeting.
 */
@EnabledIfEnvironmentVariable(named = "MEETING_VOLUME", matches = "[1-9][0-9]*")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class MeetingVolumeIT : MeetingVolumeSupport() {
    @Autowired lateinit var reads: MeetingReads

    @Autowired lateinit var drafts: AgendaDraftViews

    @Autowired lateinit var agendas: MemberAgendas

    @Autowired lateinit var opens: PaperOpens

    @Autowired lateinit var submissions: ForumSubmissions

    @Autowired lateinit var responses: SubmissionResponses

    @Autowired lateinit var items: AgendaItems

    @Autowired lateinit var publisher: AgendaPublisher

    @Autowired lateinit var meetingRegister: DeclarationRegister

    @Autowired lateinit var registers: DeclarationRegisters

    @Autowired lateinit var declarationReviews: DeclarationReviews

    @Autowired lateinit var workbook: DeclarationWorkbook

    override val large: List<String> = LARGE

    @BeforeAll
    fun generate() = generate("meetvol", e05 = false)

    @Test
    @Order(1)
    fun `a committee's meetings answer within budget, for its secretary and for a member`() {
        val page = asUser(people.secretary) { reads.ofForum(focalForum, null, null, emptySet(), null) }
        assertThat(page.items).hasSize(PAGE)
        measure("forum meetings (secretary)", LIST_BUDGET_MS, people.secretary) { reads.ofForum(focalForum, null, null, emptySet(), null) }
        measure("forum meetings (member)", LIST_BUDGET_MS, people.director) { reads.ofForum(focalForum, null, null, emptySet(), null) }
    }

    @Test
    @Order(2)
    fun `my meetings answer within budget, with the counts of what changed`() {
        val mine = asUser(people.director) { reads.mine(null) }
        assertThat(mine).isNotEmpty
        assertThat(mine.single { it.id == volume.focal }.changesSinceSeen).isPositive()
        measure("my meetings (director)", LIST_BUDGET_MS, people.director) { reads.mine(null) }
        assertThat(asUser(people.secretary) { reads.mine(null) }).hasSize(MeetingReads.MINE)
        measure("my meetings (secretary of $FORUMS)", LIST_BUDGET_MS, people.secretary) { reads.mine(null) }
    }

    @Test
    @Order(3)
    fun `the draft and the tray answer within budget`() {
        val draft = asUser(people.secretary) { drafts.draft(volume.focal) }
        assertThat(draft.sections.sumOf { s -> s.items.sumOf { 1 + it.subItems.size } }).isEqualTo(FOCAL_ITEMS)
        assertThat(draft.traySize).isEqualTo(MeetingVolume.TRAY)
        measure("agenda draft", AGENDA_BUDGET_MS, people.secretary) { drafts.draft(volume.focal) }
        val day = asUser(people.secretary) { drafts.draft(volume.focal) }.startsAt.atZone(DHAKA).toLocalDate()
        assertThat(asUser(people.secretary) { tray(day) }).hasSize(MeetingVolume.TRAY)
        measure("tray", TRAY_BUDGET_MS, people.secretary) { tray(day) }
    }

    @Test
    @Order(4)
    fun `the members' agenda, the revision list and a paper open answer within budget`() {
        val agenda = asUser(people.director) { agendas.agenda(volume.focal, null) }
        assertThat(agenda.revision).isEqualTo(MeetingVolume.FOCAL_REVISIONS)
        assertThat(checkNotNull(agenda.whatChanged).count).isPositive()
        measure("members' agenda with what changed (director)", AGENDA_BUDGET_MS, people.director) { agendas.agenda(volume.focal, null) }
        measure("members' agenda (chair)", AGENDA_BUDGET_MS, people.chair) { agendas.agenda(volume.focal, null) }
        assertThat(asUser(people.director) { agendas.revisions(volume.focal) }).hasSize(MeetingVolume.FOCAL_REVISIONS)
        measure("revision list", LIST_BUDGET_MS, people.director) { agendas.revisions(volume.focal) }
        val paper = agenda.sections.flatMap { s -> s.items.flatMap { it.papers } }.first()
        measure("paper open (the rule's query)", OPEN_BUDGET_MS, people.director) { opens.open(volume.focal, paper.id, null, false) }
    }

    @Test
    @Order(5)
    fun `the meeting register, my declarations, the register across committees and its export answer within budget`() {
        val register = asUser(people.secretary) { meetingRegister.meeting(volume.focal) }
        assertThat(register.items.sumOf { it.declarations.size }).isPositive()
        assertThat(register.reviews).isNotEmpty
        measure("meeting register (secretary)", AGENDA_BUDGET_MS, people.secretary) { meetingRegister.meeting(volume.focal) }
        assertThat(asUser(people.director) { declarationReviews.mine(volume.focal) }.declarations).isNotEmpty
        measure("my declarations (director)", LIST_BUDGET_MS, people.director) { declarationReviews.mine(volume.focal) }
        assertThat(asUser(people.admin) { registers.page(RegisterFilter(), null) }.items).hasSize(DeclarationRegisters.PAGE)
        measure("register across committees (forums.manage)", AGENDA_BUDGET_MS, people.admin) { registers.page(RegisterFilter(), null) }
        val ofFocal = RegisterFilter(forumId = focalForum)
        assertThat(asUser(people.secretary) { registers.page(ofFocal, null) }.items).hasSize(DeclarationRegisters.PAGE)
        measure("register of one committee (its secretary)", AGENDA_BUDGET_MS, people.secretary) { registers.page(ofFocal, null) }
        val exports =
            (1..EXPORT_RUNS).map {
                timed {
                    val rows = asUser(people.admin, write = true) { registers.export(RegisterFilter()) }
                    assertThat(rows.size).isGreaterThan(DeclarationRegisters.PAGE)
                    workbook.write(rows, OutputStream.nullOutputStream())
                }
            }
        report("export of the register (forums.manage)", exports, EXPORT_BUDGET_MS)
    }

    @Test
    @Order(6)
    fun `a builder change and a publication answer within budget`() {
        val item = asUser(people.secretary) { drafts.draft(volume.focal) }.sections[1].items.first()
        val changes = mutableListOf<Long>()
        val publications = mutableListOf<Long>()
        repeat(WARM_UP + RUNS) { run ->
            val version = asUser(people.secretary) { drafts.draft(volume.focal) }.version
            changes +=
                timed {
                    asUser(people.secretary, write = true) {
                        items.update(volume.focal, item.id, version, ItemChange("Credit proposal, revision $run", minutes = 15))
                    }
                }
            val next = asUser(people.secretary) { drafts.draft(volume.focal) }.version
            publications +=
                timed { asUser(people.secretary, write = true) { publisher.publish(volume.focal, next, "Retitled $run", false) } }
        }
        report("builder change (edit an item)", changes.drop(WARM_UP), CHANGE_BUDGET_MS)
        report("publish (60 items, 196 papers)", publications.drop(WARM_UP), PUBLISH_BUDGET_MS)
    }

    private fun tray(day: LocalDate) = responses.of(submissions.ready(focalForum, day))

    companion object {
        const val PAGE = 50
        const val FOCAL_ITEMS = 60
        val DHAKA: java.time.ZoneId = java.time.ZoneId.of("Asia/Dhaka")

        // Design E03 §8. The revision list and the tray have no row of their own: held to the list budget and to E02's
        // queue budget; a paper open's budget is to the first byte, of which this is the rule's query.
        const val LIST_BUDGET_MS = 100L
        const val AGENDA_BUDGET_MS = 200L
        const val TRAY_BUDGET_MS = 150L
        const val OPEN_BUDGET_MS = 300L
        const val CHANGE_BUDGET_MS = 150L
        const val PUBLISH_BUDGET_MS = 500L

        // Design E04 §8: the meeting register and a page of the register at 200 ms, my declarations at 100 ms, an export of
        // up to 20,000 rows within 5 s (measured three times: each run is audited).
        const val EXPORT_BUDGET_MS = 5_000L
        const val EXPORT_RUNS = 3

        /** The tables the volume makes large (also [InvitationVolumeIT]'s). */
        val LARGE =
            listOf(
                "meeting",
                "meeting_agenda",
                "meeting_section",
                "meeting_item",
                "meeting_item_attendee",
                "meeting_paper",
                "meeting_revision",
                "meeting_revision_section",
                "meeting_revision_item",
                "meeting_revision_paper",
                "meeting_audience",
                "meeting_reader_state",
                "meeting_paper_open",
                "forum_submission",
            )
    }
}
