package com.memo.meeting

import com.memo.common.authz.AccessAdministration
import com.memo.common.authz.Permission
import com.memo.common.context.ActorContext
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
import com.memo.meeting.internal.AgendaPapers
import com.memo.meeting.internal.AgendaPublisher
import com.memo.meeting.internal.AgendaSections
import com.memo.meeting.internal.AgendaStart
import com.memo.meeting.internal.ItemChange
import com.memo.meeting.internal.ItemInput
import com.memo.meeting.internal.ItemKind
import com.memo.meeting.internal.MeetingGuests
import com.memo.meeting.internal.MeetingInput
import com.memo.meeting.internal.MeetingLifecycle
import com.memo.meeting.internal.MeetingPaperPurpose
import com.memo.meeting.internal.MeetingPlace
import com.memo.meeting.internal.MeetingReads
import com.memo.meeting.internal.MeetingRow
import com.memo.meeting.internal.PaperInput
import com.memo.meeting.internal.SectionInput
import com.memo.meeting.internal.TimesInput
import com.memo.testsupport.AppRoleJdbc
import com.memo.testsupport.AppRoleJdbc.long
import com.memo.testsupport.MemoIntegrationTest
import com.memo.testsupport.Person
import com.memo.testsupport.Samples
import com.memo.testsupport.sendJson
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.test.web.servlet.assertj.MvcTestResult
import tools.jackson.databind.JsonNode
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

/**
 * The committee and people of the paper-rule tests (test plan E03, T4; ADR-0061): the BRC with a secretary, a chair, three
 * members (the third not cleared for the confidential level), an observer (not for members-only sections), and outside
 * the roster a guest, an item presenter, a `forum.read` holder and a user without a role. Every meeting is at the
 * committee's level, scheduled, its agenda started from the template, the guest added.
 */
abstract class PaperFixture : MemoIntegrationTest() {
    @Autowired lateinit var forumTypes: ForumTypes

    @Autowired lateinit var forums: Forums

    @Autowired lateinit var members: ForumMembers

    @Autowired lateinit var lifecycle: MeetingLifecycle

    @Autowired lateinit var reads: MeetingReads

    @Autowired lateinit var guests: MeetingGuests

    @Autowired lateinit var starts: AgendaStart

    @Autowired lateinit var sections: AgendaSections

    @Autowired lateinit var items: AgendaItems

    @Autowired lateinit var papers: AgendaPapers

    @Autowired lateinit var views: AgendaDraftViews

    @Autowired lateinit var chairing: AgendaChair

    @Autowired lateinit var publisher: AgendaPublisher

    @Autowired lateinit var notices: AgendaNotices

    @Autowired lateinit var scheduler: AgendaNoticeScheduler

    @Autowired lateinit var access: AccessAdministration

    @Autowired lateinit var tenantDsl: com.memo.common.tenant.TenantDsl

    lateinit var secretary: Person
    lateinit var chair: Person
    lateinit var d1: Person
    lateinit var d2: Person

    /** A member not cleared for the confidential level. */
    lateinit var d3: Person

    /** On the roster, but not for members-only sections. */
    lateinit var observer: Person
    lateinit var guest: Person

    /** Attends one item only. */
    lateinit var presenter: Person

    /** Holds `forum.read`: oversight, titles only. */
    lateinit var auditor: Person
    lateinit var outsider: Person
    lateinit var brc: UUID

    @BeforeEach
    fun committee() {
        scheduler.jobs = false
        secretary = person(clearance = 1)
        chair = person(clearance = 1)
        d1 = person(clearance = 1)
        d2 = person(clearance = 1)
        d3 = person()
        observer = person(clearance = 1)
        guest = person(clearance = 1)
        presenter = person(clearance = 1)
        auditor = person()
        outsider = person(clearance = 1)
        val type = asUser(admin) { forumTypes.create("BC", "Board committee", ForumTypePreset.BOARD_COMMITTEE, publish = true) }.type.id
        brc = asUser(admin) { forums.create(ForumInput("board-risk", "BRC", "Board Risk Committee", "Risk", type)) }.id
        seat(secretary, ForumRole.SECRETARY)
        seat(chair, ForumRole.CHAIR)
        listOf(d1, d2, d3).forEach { seat(it, ForumRole.MEMBER) }
        seat(observer, ForumRole.OBSERVER)
        asUser(admin) { access.grantCustom(auditor.id, "Committee readers", setOf(Permission.FORUM_READ)) }
    }

    @AfterEach
    fun jobsBack() {
        scheduler.jobs = true
    }

    private fun seat(
        person: Person,
        role: ForumRole,
    ) = asUser(admin) { members.add(brc, MemberInput(userId = person.id, role = role)) }

    /** A meeting of the BRC [days] ahead, its agenda started from the template and the guest added; scheduled by default. */
    fun meeting(
        schedule: Boolean = true,
        days: Long = DAYS_AHEAD,
    ): MeetingRow {
        val day = LocalDate.now().plusDays(days)
        return asUser(secretary.account) {
            val created =
                lifecycle.create(
                    brc,
                    MeetingInput(MeetingKind.ORDINARY, MeetingPlace(null, MeetingMode.IN_PERSON, "Board room", null, null), null),
                    TimesInput(day.atTime(LocalTime.of(10, 0)), day.atTime(LocalTime.of(13, 0)), "Asia/Dhaka"),
                )
            starts.start(created.id, AgendaOrigin.TEMPLATE, null)
            val withGuest = guests.add(created.id, guest.id, "Chief Risk Officer")
            if (schedule) lifecycle.schedule(created.id, withGuest.version) else withGuest
        }
    }

    fun version(meeting: MeetingRow): Long = asUser(secretary.account) { views.draft(meeting.id) }.version

    fun section(
        meeting: MeetingRow,
        title: String,
    ): UUID = asUser(secretary.account) { views.draft(meeting.id) }.sections.single { it.title == title }.id

    /** A new members-only (in camera) section. */
    fun inCamera(meeting: MeetingRow): UUID =
        asUser(secretary.account) {
            sections.add(meeting.id, version(meeting), SectionInput("In camera", AgendaSectionKind.STANDARD, membersOnly = true), null)
        }

    fun addItem(
        meeting: MeetingRow,
        title: String,
        section: String = "For decision",
        sectionId: UUID? = null,
    ): UUID =
        asUser(secretary.account) {
            items.add(
                meeting.id,
                version(meeting),
                ItemInput(ItemKind.ITEM, sectionId = sectionId ?: section(meeting, section), title = title, minutes = 15),
            )
        }

    fun change(
        meeting: MeetingRow,
        itemId: UUID,
        change: ItemChange,
    ) = asUser(secretary.account) { items.update(meeting.id, itemId, version(meeting), change) }

    /** Uploads a `meeting.paper` for [meeting]; its file id. */
    fun upload(
        meeting: MeetingRow,
        name: String,
        bytes: ByteArray = Samples.pdf(),
    ): UUID {
        val uploaded = upload(secretary.session, bytes, "/api/v1/files?purpose=${MeetingPaperPurpose.KEY}&owner=${meeting.id}", name)
        assertThat(uploaded).hasStatus(HttpStatus.CREATED)
        return UUID.fromString(uploaded.json()["fileId"].asString())
    }

    /** A new version of an uploaded paper's file (the draft's papers take it; revisions keep theirs). */
    fun replace(
        fileId: UUID,
        bytes: ByteArray = Samples.pdf(2),
    ) {
        val file = read("/api/v1/files/$fileId", secretary.session).json()
        val replaced =
            upload(secretary.session, bytes, "/api/v1/files/$fileId/versions", "v2.pdf", mapOf("If-Match" to "\"${file["version"]}\""))
        assertThat(replaced).hasStatus(HttpStatus.CREATED)
    }

    /** Puts [fileId] on [itemId] as a paper; its id. */
    fun paper(
        meeting: MeetingRow,
        itemId: UUID,
        fileId: UUID,
        title: String? = null,
        restrictedTo: List<UUID>? = null,
        level: UUID? = null,
    ): UUID = asUser(secretary.account) { papers.add(meeting.id, itemId, version(meeting), PaperInput(fileId, title, restrictedTo, level)) }

    /** The chair agrees (before revision 1) and the secretary publishes; the revision's number. */
    fun publish(
        meeting: MeetingRow,
        note: String? = null,
    ): Int {
        if (asUser(secretary.account) { views.draft(meeting.id) }.currentRevision == 0) {
            asUser(chair.account) { chairing.agree(meeting.id, version(meeting), false, null) }
        }
        return asUser(secretary.account) { publisher.publish(meeting.id, version(meeting), note, true) }
    }

    /** Runs [statement] as the app role in the tenant, committed (AppRoleJdbc rolls back): test set-up the API cannot make. */
    fun commit(
        statement: String,
        vararg params: Any,
    ) {
        transactions.inTenant(tenant.id) { tenantDsl.current().execute(statement, *params) }
    }

    /** Runs revision [revision]'s notification job in place: the system in a tenant transaction. */
    fun send(
        meeting: MeetingRow,
        revision: Int,
    ): Int =
        TenantContext.call(tenant.id) {
            ActorContext.asSystem("test") { transactions.inTenant(tenant.id) { notices.send(meeting.id, revision) } }
        }

    fun notified(
        person: Person,
        kind: String,
    ): Long =
        AppRoleJdbc.inTransaction(tenant.id) { c ->
            c.long("select count(*) from notification_item where user_id = ? and kind = ?", person.id, kind)
        }

    /** The members' agenda as [person] reads it over HTTP. */
    fun agendaOf(
        person: Person,
        meeting: MeetingRow,
        revision: Int? = null,
    ): MvcTestResult = read("/api/v1/meetings/${meeting.id}/agenda" + (revision?.let { "?revision=$it" } ?: ""), person.session)

    fun agenda(
        person: Person,
        meeting: MeetingRow,
        revision: Int? = null,
    ): JsonNode {
        val result = agendaOf(person, meeting, revision)
        assertThat(result).hasStatusOk()
        return result.json()
    }

    /** A paper's content as [person] opens it. */
    fun open(
        person: Person,
        meeting: MeetingRow,
        paperId: UUID,
        query: String = "",
    ): MvcTestResult = read("/api/v1/meetings/${meeting.id}/papers/$paperId/content$query", person.session)

    fun send(
        method: HttpMethod,
        uri: String,
        person: Person,
        body: String = "{}",
        headers: Map<String, String> = mapOf("Idempotency-Key" to UUID.randomUUID().toString()),
    ): MvcTestResult = mvc.sendJson(method, tenant, uri, person.session, body, headers)

    /** Every item line of an agenda response, sub-items included, by id. */
    fun JsonNode.lines(): Map<UUID, JsonNode> =
        get("sections")
            .list()
            .flatMap { it["items"].list() }
            .flatMap { listOf(it) + it["subItems"].list() }
            .associateBy { UUID.fromString(it["id"].asString()) }

    /** How [person] sees each of [ids] ("FULL", "TITLE", "HIDDEN", or null when the line is absent). */
    fun JsonNode.access(vararg ids: UUID): List<String?> {
        val lines = lines()
        return ids.map { lines[it]?.get("access")?.asString() }
    }

    /** The papers of the line [itemId]. */
    fun JsonNode.papersOf(itemId: UUID): List<UUID> =
        lines()[itemId]
            ?.get("papers")
            ?.list()
            ?.map { UUID.fromString(it["id"].asString()) }
            .orEmpty()

    fun JsonNode.sectionTitles(): List<String> = get("sections").list().map { it["title"].asString() }

    /** The elements of a JSON array. */
    fun JsonNode.list(): List<JsonNode> = (0 until size()).map { get(it) }

    companion object {
        const val DAYS_AHEAD = 40L
    }
}
